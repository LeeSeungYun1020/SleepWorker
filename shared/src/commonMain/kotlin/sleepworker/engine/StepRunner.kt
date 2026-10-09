package sleepworker.engine

import sleepworker.git.WorktreeManager
import sleepworker.model.*
import sleepworker.platform.TempFiles
import sleepworker.provider.*
import sleepworker.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import okio.Path

class StepRunner(
    private val registry: ProviderRegistry,
    private val tempFiles: TempFiles,
    private val io: ExecutionIO,
    private val worktrees: WorktreeManager,
    private val checks: CheckRunner,
) {
    suspend fun run(step: Step, workflow: Workflow, visit: StepVisit, attemptNo: Int,
                    workspace: String, resumeId: String?, script: String, metadata: ExecutionMetadata,
                    onStatus: suspend (StepStatus) -> Unit, onSession: suspend (String) -> Unit): StepResult {
        val path = io.recorder.attemptPath(visit, attemptNo)
        var temporary: Path? = null
        suspend fun executeAttempt(): StepResult {
            var process: CommandResult? = null
            var parser: ProviderParser? = null
            var report: ProviderReport? = null
            var completion: CheckRecord? = null
            var sessionId = resumeId
            var phase = FailurePhase.PREPARING
            try {
                onStatus(StepStatus.PREPARING)
                val workspaceDef = if (step.effectiveKind == StepKind.SHELL) step.workspace!! else step.workspace ?: workflow.sessions.getValue(step.session!!.ref).workspace
                if (workspaceDef is Workspace.Worktree) worktrees.ensure(workflow, workflow.worktrees.first { it.name == workspaceDef.name }, "$path/preparing")
                if (step.effectiveKind == StepKind.SHELL && ('\n' in script || '\r' in script)) temporary = tempFiles.createScript(script)
                val adapter = registry.adapterFor(step, workflow)
                val request = ExecRequest(workspace, script, step.model, step.effort, resumeId, metadata.binaryPath, temporary?.toString())
                val command = adapter.buildCommand(request)
                parser = adapter.createParser(request)
                io.recorder.write(io.runId, "$path/script.txt", script)
                io.recorder.write(io.runId, "$path/events.jsonl", "")
                suspend fun events(events: List<AgentEvent>) {
                    for (event in events) {
                        io.recorder.append(io.runId, "$path/events.jsonl", kotlinx.serialization.json.Json.encodeToString(event) + "\n")
                        if (event is AgentEvent.Message || event is AgentEvent.ToolCall) {
                            val line = LogLine(visit.visitNo, attemptNo, "$path/events", Stream.STDOUT,
                                when (event) { is AgentEvent.Message -> event.text; is AgentEvent.ToolCall -> event.raw; else -> "" },
                                kotlin.time.Clock.System.now(), event)
                            io.recorder.append(io.runId, "logs.jsonl", kotlinx.serialization.json.Json.encodeToString(line) + "\n")
                            io.logs.emit(line)
                        }
                        if (event is AgentEvent.SessionStarted) { sessionId = event.id; onSession(event.id) }
                    }
                }
                phase = FailurePhase.EXECUTING
                onStatus(StepStatus.EXECUTING)
                process = io.command(path, command, step.timeoutSec?.times(1000L), visit.visitNo, attemptNo) { line, stream -> events(parser.accept(line, stream)) }
                // A cancelled body still finalizes its collected evidence exactly once.
                withContext(NonCancellable) {
                    phase = FailurePhase.FINALIZING
                    onStatus(StepStatus.FINALIZING)
                    report = parser.finalizeOutput(process.exitCode, process.termination)
                    events(report.finalEvents)
                    report.sessionId?.let { sessionId = it; onSession(it) }
                }
                val failure = when {
                    process.cleanupError != null -> FailureInfo(FailureKind.OUTPUT_IO, phase, process.cleanupError)
                    process.termination == Termination.TIMED_OUT -> FailureInfo(FailureKind.TIMEOUT, FailurePhase.EXECUTING, "Body timeout")
                    process.termination != Termination.NORMAL -> FailureInfo(FailureKind.OUTPUT_IO, phase, process.error ?: process.termination.name)
                    report!!.failure != null -> report.failure
                    report.outcome !in setOf(ProviderOutcome.SUCCEEDED, ProviderOutcome.NOT_APPLICABLE) -> FailureInfo(FailureKind.PROVIDER, phase, "Provider did not succeed")
                    process.exitCode != 0 -> FailureInfo(FailureKind.EXIT_CODE, phase, "Exit ${process.exitCode}")
                    else -> null
                }
                if (failure != null) return StepResult(false, process.exitCode, process.termination, report, failure = failure, sessionId = sessionId, bodyStarted = process.bodyStarted, finalOutput = report?.finalOutput, cleanupError = process.cleanupError)
                currentCoroutineContext().ensureActive()
                phase = FailurePhase.CHECKING
                onStatus(StepStatus.CHECKING)
                completion = checks.completion(step.completion, workspace, step.checkTimeoutSec, "$path/checks/completion")
                val success = completion.result == CheckResult.Met
                return StepResult(success, process.exitCode, process.termination, report, completion,
                    if (success) null else FailureInfo(FailureKind.COMPLETION, phase, completion.result.toString()), sessionId, true, report?.finalOutput)
            } catch (e: RecordingException) {
                throw e
            } catch (e: Exception) {
                if (e is UnsafeCleanup) completion = e.check
                if (e is CancelledCheck) completion = e.check
                val kind = when {
                    e is ProviderConfigException -> FailureKind.CONFIG
                    phase == FailurePhase.PREPARING -> FailureKind.PREPARATION
                    phase == FailurePhase.EXECUTING -> FailureKind.OUTPUT_IO
                    phase == FailurePhase.FINALIZING -> FailureKind.PROTOCOL
                    else -> FailureKind.COMPLETION
                }
                return StepResult(false, process?.exitCode, if (e is CancellationException) Termination.CANCELLED else process?.termination ?: Termination.START_FAILED,
                    report, completion, FailureInfo(kind, phase, e.message ?: e.toString()), sessionId, process?.bodyStarted ?: false, report?.finalOutput,
                    if (e is UnsafeCleanup) e.message else process?.cleanupError)
            }
        }
        var result: StepResult? = null
        try { result = executeAttempt() }
        finally {
            try { temporary?.let { tempFiles.delete(it) } }
            catch (e: Exception) {
                val completed = result
                if (completed == null) throw e
                result = completed.copy(success = false, cleanupError = "Temporary script cleanup failed: ${e.message}",
                    failure = FailureInfo(FailureKind.PREPARATION, FailurePhase.FINALIZING, e.message ?: e.toString()))
            }
        }
        return result
    }
}
