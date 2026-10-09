package aiflow.engine

import aiflow.git.WorktreeManager
import aiflow.model.*
import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One instance owns one run; UI commands and state writes share one serialization gate. */
@OptIn(ExperimentalUuidApi::class)
class RunOrchestrator(
    private val store: WorkflowStore,
    private val recorder: RunRecorder,
    executor: ProcessExecutor,
    private val tempFiles: TempFiles,
    private val fs: FileSystem = recorder.fs,
    private val registry: ProviderRegistry = ProviderRegistry(),
    private val preflight: Preflight = Preflight.None,
    private val metadata: Map<Provider, Pair<String?, String?>> = emptyMap(),
    private val now: () -> Instant = { Clock.System.now() },
    private val drainMs: Long = 5_000,
    private val cleanupMs: Long = 5_000,
    private val appLog: (String) -> Unit = { println(it) },
) {
    private val gate = Mutex()
    private val owner = ManagedProcessRunner(executor, cleanupMs)
    private val mutableState = MutableStateFlow<RunState?>(null)
    val state: StateFlow<RunState?> = mutableState.asStateFlow()
    private val mutableLogs = MutableSharedFlow<LogLine>(extraBufferCapacity = 4096)
    val logs: SharedFlow<LogLine> = mutableLogs.asSharedFlow()
    private val choices = Channel<UserDecision>(1)
    private val resumes = Channel<Unit>(1)
    private var active: Job? = null
    private var started = false
    private var choicePending = false
    private var resumePending = false
    private var stop: RunStatus? = null
    private lateinit var io: ExecutionIO
    private lateinit var worktrees: WorktreeManager
    private lateinit var checks: CheckRunner
    private lateinit var steps: StepRunner
    private lateinit var transitions: TransitionEvaluator
    private fun current() = mutableState.value ?: error("Run not initialized")
    private suspend fun update(block: (RunState) -> RunState) = gate.withLock {
        val before = current()
        if (!before.status.terminal) {
            val after = block(before).copy(lastUpdatedAt = now())
            recorder.save(after)
            mutableState.value = after
        }
    }
    private suspend fun visitUpdate(block: (StepVisit) -> StepVisit) = update { state ->
        state.copy(visits = state.visits.map { if (it.visitNo == state.currentVisit) block(it) else it })
    }
    private fun visit() = current().visits.last()
    private fun runId(): String {
        val stamp = now().toString().take(19).replace("-", "").replace(":", "").replace('T', '-')
        return "$stamp-${Uuid.random().toString().replace("-", "").take(16)}"
    }
    suspend fun start(version: WorkflowVersion, settings: AppSettings = AppSettings()): RunState = supervisorScope {
        gate.withLock { check(!started); started = true }
        // Reload by identity. Caller-owned mutable collections can never change the execution snapshot.
        val stored = store.loadVersion(version.workflowId, version.versionId)
        require(stored.workflow.repoPath.toPath(normalize = true) == recorder.lease.repoPath) { "Repository lease does not match workflow" }
        require(WorkflowValidator(fs).validate(stored.workflow).none { it.severity == Severity.ERROR }) { "Invalid workflow" }
        val initial = RunState(runId(), stored.workflowId, stored.versionId, stored.workflow, RunStatus.PREFLIGHT, lastUpdatedAt = now(), startedAt = now())
        try { recorder.save(initial) } catch (e: Exception) {
            appLog(e.stackTraceToString())
            mutableState.value = initial.copy(status = RunStatus.FAILED, failure = e.message)
            return@supervisorScope current()
        }
        io = ExecutionIO(CommandRunner(owner, drainMs, cleanupMs), recorder, initial.runId, mutableLogs)
        worktrees = WorktreeManager(fs, io)
        checks = CheckRunner(fs, io)
        steps = StepRunner(registry, tempFiles, io, worktrees, checks)
        transitions = TransitionEvaluator(checks)
        val task = async(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                preflight.check(stored, settings, io)
                currentCoroutineContext().ensureActive()
                update { it.copy(status = RunStatus.RUNNING) }
                execute(settings)
            } catch (e: CancellationException) {
                finishStopped()
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    val cleanup = cleanupAll()
                    fail(e.stackTraceToString(), cleanup)
                }
            }
        }
        gate.withLock { active = task; mutableState.value = initial; task.start() }
        try { task.await() } catch (e: CancellationException) {
            withContext(NonCancellable) { if (!current().status.terminal) finishStopped() }
            currentCoroutineContext().ensureActive()
        }
        current()
    }
    private suspend fun cleanupAll(): String? = try { withTimeout(cleanupMs) { owner.cancelAll() }; null } catch (e: Exception) { e.message ?: e.toString() }
    private suspend fun fail(reason: String, cleanup: String? = null) {
        val failure = listOfNotNull(reason, cleanup).joinToString("; ")
        appLog(failure)
        try {
            update { state -> state.copy(status = RunStatus.FAILED, failure = failure,
                visits = state.visits.map { visit -> if (visit.result != null) visit else visit.copy(status = StepStatus.FAILED,
                    attempts = visit.attempts.map { attempt -> if (attempt.result != null) attempt else attempt.copy(status = StepStatus.FAILED, failureDetail = failure) }) }) }
            current().visits.forEach { visit -> visit.attempts.filter { it.result == null }.forEach { attempt ->
                recorder.write(current().runId, "${recorder.attemptPath(visit, attempt.attemptNo)}/result.json", recorder.json.encodeToString(attempt), immutable = true)
            } }
        }
        catch (e: Exception) {
            // Storage may be unavailable; preserve the last durable state for recovery and expose failure.
            appLog(e.stackTraceToString())
            mutableState.value = current().copy(status = RunStatus.FAILED, failure = "$failure; ${e.message}", lastUpdatedAt = now())
        }
    }
    private suspend fun finishStopped() = withContext(NonCancellable) {
        val cleanup = cleanupAll()
        if (cleanup != null) { fail("Process cleanup unconfirmed", cleanup); return@withContext }
        val terminal = stop ?: RunStatus.INTERRUPTED
        try { update { state ->
            if (terminal == RunStatus.INTERRUPTED) RunRecovery.interrupted(state, now(), "app_shutdown")
            else state.copy(status = RunStatus.ABORTED, visits = state.visits.map { if (it.result == null) it.copy(status = StepStatus.INTERRUPTED) else it })
        } } catch (e: Exception) { fail(e.stackTraceToString()) }
    }
    suspend fun pause() = update { if (it.status == RunStatus.RUNNING) it.copy(status = RunStatus.PAUSE_REQUESTED) else it }
    suspend fun resume() = gate.withLock {
        if (current().status == RunStatus.PAUSED && stop == null && !resumePending) { resumePending = true; resumes.trySend(Unit) }
        Unit
    }
    suspend fun decide(decision: UserDecision) {
        if (decision == UserDecision.Abort) { abort(); return }
        gate.withLock { if (current().status == RunStatus.AWAITING_USER && stop == null && !choicePending) { choicePending = true; choices.trySend(decision) } }
    }
    suspend fun abort() = stopRun(RunStatus.ABORTED)
    suspend fun interruptForShutdown() = stopRun(RunStatus.INTERRUPTED)
    private suspend fun stopRun(status: RunStatus) {
        gate.withLock {
            if (mutableState.value == null || current().status.terminal || stop != null) return
            stop = status
            owner.stopStarts()
            active?.cancel()
        }
        withContext(NonCancellable) {
            active?.join()
        }
    }
    private suspend fun execute(settings: AppSettings) {
        val workflow = current().workflow
        val byId = workflow.steps.associateBy { it.id }
        var nextId = workflow.start
        var reset = false
        var manual: StepVisit? = null
        var pendingTransition: TransitionTaken? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            check(stop == null) { "Run stopping" }
            val step = byId.getValue(nextId)
            val prior = manual
            manual = null
            val agent = step.effectiveKind == StepKind.AGENT
            val reference = if (agent) step.session!!.ref else null
            val definition = reference?.let { workflow.sessions.getValue(it) }
            var preparationFailure: FailureInfo? = null
            val workspace = try {
                worktrees.resolvePath(workflow, if (agent) step.workspace ?: definition!!.workspace else step.workspace!!)
            } catch (e: Exception) {
                preparationFailure = FailureInfo(FailureKind.PREPARATION, FailurePhase.PREPARING, e.message ?: e.toString())
                workflow.repoPath
            }
            val mode = if (!agent) null else if (reset) SessionMode.NEW else prior?.effectiveMode ?: step.session!!.mode
            val binding = reference?.let { current().sessionBindings[it] }
            if (mode == SessionMode.RESUME && preparationFailure == null) {
                preparationFailure = when {
                    binding == null -> FailureInfo(FailureKind.SESSION_MISSING, FailurePhase.PREPARING, "No active session ID")
                    prior != null && prior.boundSessionId != binding.id -> FailureInfo(FailureKind.SESSION_INVALID, FailurePhase.PREPARING, "Manual retry binding is no longer active")
                    binding.provider != definition!!.provider || binding.workspace != workspace -> FailureInfo(FailureKind.CONFIG, FailurePhase.PREPARING, "Session provider/workspace mismatch")
                    else -> null
                }
            }
            val bound = if (mode == SessionMode.RESUME && preparationFailure == null) binding?.id else null
            update { state ->
                check(state.visits.size < workflow.maxSteps)
                val number = state.visits.size + 1
                val taken = pendingTransition
                val updatedVisits = state.visits.map { if (it.visitNo == state.currentVisit && taken != null) it.copy(transitionTaken = taken) else it }
                val transitionCounts = if (taken == null) state.transitionCounts else {
                    val key = TransitionEvaluator.key(taken.stepId, taken.index)
                    state.transitionCounts + (key to ((state.transitionCounts[key] ?: 0) + 1))
                }
                state.copy(transitionCounts = transitionCounts, currentVisit = number, visits = updatedVisits + StepVisit(number, step.id, StepStatus.PREPARING, now(), effectiveMode = mode, manualRetryOf = prior?.visitNo, boundSessionId = bound),
                    visitCounts = state.visitCounts + (step.id to ((state.visitCounts[step.id] ?: 0) + 1)),
                    sessionIds = if (mode == SessionMode.NEW) state.sessionIds - reference!! else state.sessionIds,
                    sessionBindings = if (mode == SessionMode.NEW) state.sessionBindings - reference!! else state.sessionBindings)
            }
            reset = false
            pendingTransition = null
            var result: StepResult
            var attemptNo = 1
            while (true) {
                currentCoroutineContext().ensureActive()
                val binary = when (definition?.provider) { Provider.CODEX -> settings.codexPath.orEmpty(); Provider.ANTIGRAVITY -> settings.agyPath.orEmpty(); null -> "/bin/zsh" }
                val meta = ExecutionMetadata(binary, metadata[definition?.provider]?.first, metadata[definition?.provider]?.second, step.model, step.effort)
                visitUpdate { it.copy(attempts = it.attempts + AttemptRecord(attemptNo, StepStatus.PREPARING, now(), sessionId = it.boundSessionId, metadata = meta)) }
                val script = if (attemptNo == 1) { if (agent) step.script else step.shellScript } else RetryPolicy.script(step, visit().attempts.first().result!!)
                val path = recorder.attemptPath(visit(), attemptNo)
                recorder.write(current().runId, "$path/script.txt", script)
                result = if (preparationFailure != null) StepResult(false, termination = Termination.START_FAILED, failure = preparationFailure)
                else steps.run(step, workflow, visit(), attemptNo, workspace, visit().boundSessionId, script, meta,
                    onStatus = { status -> visitUpdate { it.copy(status = status, attempts = it.attempts.map { a -> if (a.attemptNo == attemptNo) a.copy(status = status) else a }) } },
                    onSession = { id -> update { state ->
                        state.copy(sessionIds = state.sessionIds + (reference!! to id), sessionBindings = state.sessionBindings + (reference to SessionBinding(id, definition!!.provider, workspace)),
                            visits = state.visits.map { if (it.visitNo == state.currentVisit) it.copy(boundSessionId = id, attempts = it.attempts.map { a -> if (a.attemptNo == attemptNo) a.copy(sessionId = id) else a }) else it })
                    } })
                withContext(NonCancellable) {
                    if (result.failure?.kind == FailureKind.SESSION_INVALID && reference != null) update { it.copy(sessionIds = it.sessionIds - reference, sessionBindings = it.sessionBindings - reference) }
                    val skipped = if (!result.success && attemptNo == 1) RetryPolicy.skippedReason(step, result, visit().boundSessionId) else null
                    visitUpdate { it.copy(attempts = it.attempts.map { a -> if (a.attemptNo == attemptNo) a.copy(status = if (result.termination == Termination.CANCELLED) StepStatus.INTERRUPTED else if (result.success) StepStatus.SUCCEEDED else StepStatus.FAILED, endedAt = now(), result = result, retrySkippedReason = skipped) else a }) }
                    recorder.write(current().runId, "$path/result.json", recorder.json.encodeToString(visit().attempts.last()), immutable = true)
                }
                currentCoroutineContext().ensureActive()
                if (result.cleanupError != null) throw UnsafeCleanup(result.cleanupError)
                if (attemptNo == 2 || RetryPolicy.skippedReason(step, result, visit().boundSessionId) != null) break
                attemptNo++
                visitUpdate { it.copy(status = StepStatus.RETRYING) }
            }
            visitUpdate { it.copy(status = if (result.success) StepStatus.SUCCEEDED else StepStatus.FAILED, endedAt = now(), result = result) }
            var success = result.success
            decisionLoop@ while (true) {
                currentCoroutineContext().ensureActive()
                val evaluation = transitions.evaluate(step, success, workspace, visit(), current().transitionCounts, "${recorder.visitPath(visit())}/checks/transitions") { index, record ->
                    visitUpdate { it.copy(conditionEvaluations = it.conditionEvaluations + (index to record)) }
                }
                var decision = evaluation.decision
                if (decision is Decision.NextStep && current().visits.size >= workflow.maxSteps) decision = Decision.Ask("maxSteps reached")
                val follow = evaluation.selected?.takeUnless { evaluation.decision is Decision.NextStep }
                update { state ->
                    val counts = if (follow == null) state.transitionCounts else {
                        val key = TransitionEvaluator.key(follow.stepId, follow.index)
                        state.transitionCounts + (key to ((state.transitionCounts[key] ?: 0) + 1))
                    }
                    state.copy(transitionCounts = counts, visits = state.visits.map { if (it.visitNo == state.currentVisit) it.copy(conditionEvaluations = evaluation.cache, decision = decision, transitionTaken = follow ?: it.transitionTaken) else it })
                }
                when (val selected = decision) {
                    Decision.End -> { update { it.copy(status = RunStatus.COMPLETED) }; return }
                    is Decision.NextStep -> {
                        var paused = false
                        update { if (it.status == RunStatus.PAUSE_REQUESTED) { paused = true; it.copy(status = RunStatus.PAUSED) } else it }
                        if (paused) { resumes.receive(); update { resumePending = false; it.copy(status = RunStatus.RUNNING) } }
                        pendingTransition = evaluation.selected
                        nextId = selected.id; reset = selected.resetSession
                        break@decisionLoop
                    }
                    is Decision.Ask -> {
                        update { choicePending = false; it.copy(status = RunStatus.AWAITING_USER) }
                        while (true) {
                            when (val choice = choices.receive()) {
                                UserDecision.Abort -> throw CancellationException("Aborted")
                                UserDecision.Retry -> {
                                    visitUpdate { it.copy(controls = it.controls + UserControl(choice.name, now())) }
                                    if (current().visits.size >= workflow.maxSteps) { gate.withLock { choicePending = false }; continue }
                                    manual = visit(); nextId = step.id
                                    update { it.copy(status = RunStatus.RUNNING) }
                                    break@decisionLoop
                                }
                                UserDecision.Skip -> {
                                    visitUpdate { it.copy(controls = it.controls + UserControl(choice.name, now())) }
                                    success = true
                                    update { it.copy(status = RunStatus.RUNNING) }
                                    continue@decisionLoop
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
