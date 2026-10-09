package aiflow.ui.run

import aiflow.engine.*
import aiflow.model.*
import aiflow.platform.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path.Companion.toPath

/** All filesystem/process work runs off the Compose dispatcher. Owns the repository lease. */
class RunViewModel(private val platform: Platform) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var lease: RepositoryLease? = null
    private var store: WorkflowStore? = null
    private var recorder: RunRecorder? = null
    private var engine: RunOrchestrator? = null
    private var runJob: Job? = null
    private var settings = AppSettings()
    private val checker = CliPreflight(platform)
    val versions = MutableStateFlow<List<WorkflowVersion>>(emptyList())
    val selected = MutableStateFlow<WorkflowVersion?>(null)
    val report = MutableStateFlow<PreflightReport?>(null)
    val state = MutableStateFlow<RunState?>(null)
    val history = MutableStateFlow<List<RunState>>(emptyList())
    val logs = MutableStateFlow<List<LogLine>>(emptyList())
    val truncated = MutableStateFlow<Set<Int?>>(emptySet())
    val pendingImport = MutableStateFlow<WorkflowDraft?>(null)
    val importWarnings = MutableStateFlow<List<Issue>>(emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val repository = MutableStateFlow<String?>(null)
    val active get() = runJob?.isActive == true
    private fun action(block: suspend () -> Unit) { scope.launch { gate.withLock {
        busy.value = true
        try { error.value = null; block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error.value = e.message ?: e.toString() }
        finally { busy.value = false }
    } } }
    fun openRepository(path: String) = action {
        check(!active) { "실행 종료 후 저장소를 변경하세요" }
        val next = platform.repositoryLock.acquire(path.toPath())
        try {
            val nextStore = WorkflowStore(platform.files, next)
            val nextRecorder = RunRecorder(platform.files, next)
            val recovered = RunRecovery(nextRecorder).recover()
            val available = nextStore.listWorkflows().flatMap { nextStore.listVersions(it) }
            settings = try { SettingsStore(platform.files, platform.settingsPath).load() } catch (e: Exception) {
                error.value = "설정 로드 실패 — 기본값 사용: ${e.message}"; AppSettings()
            }
            lease?.release(); lease = next; store = nextStore; recorder = nextRecorder
            pendingImport.value = null; importWarnings.value = emptyList()
            repository.value = next.repoPath.toString(); versions.value = available
            selected.value = available.lastOrNull(); report.value = null
            history.value = recovered; state.value = null; logs.value = emptyList(); engine = null
        } catch (e: Throwable) { next.release(); throw e }
    }
    fun select(version: WorkflowVersion) = action {
        check(!active); selected.value = version; report.value = null; state.value = null; logs.value = emptyList()
    }
    fun importYaml(path: String) = action {
        check(!active)
        val target = store ?: error("저장소를 먼저 여세요")
        val text = platform.files.read(path.toPath()) { readUtf8() }
        val workflow = WorkflowCodec().decode(text)
        require(workflow.repoPath.toPath(normalize = true) == lease!!.repoPath) { "YAML repoPath가 열린 저장소와 다릅니다: ${lease!!.repoPath}" }
        val draft = target.importYaml(text)
        val issues = WorkflowValidator(platform.files).validate(draft.workflow)
        require(issues.none { it.severity == Severity.ERROR }) { "초안만 저장됨: $issues" }
        val warnings = issues.filter { it.severity == Severity.WARNING }
        if (warnings.isNotEmpty()) {
            pendingImport.value = draft; importWarnings.value = warnings
            return@action
        }
        publishImport(draft, false)
    }
    fun confirmImport() = action {
        check(!active)
        publishImport(pendingImport.value ?: error("가져올 초안 없음"), true)
    }
    fun cancelImport() { pendingImport.value = null; importWarnings.value = emptyList() }
    private suspend fun publishImport(draft: WorkflowDraft, acknowledged: Boolean) {
        val target = store!!
        val saved = target.saveVersion(draft, warningsAcknowledged = acknowledged)
        versions.value = target.listWorkflows().flatMap { target.listVersions(it) }
        selected.value = saved; report.value = null; state.value = null; logs.value = emptyList()
        pendingImport.value = null; importWarnings.value = emptyList()
    }
    fun runPreflight() = action {
        check(!active)
        report.value = null
        report.value = checker.inspect(selected.value ?: error("저장 버전 선택 필요"), settings)
    }
    fun start() = action {
        check(!active)
        val version = selected.value ?: error("저장 버전 선택 필요")
        val checked = report.value ?: error("프리플라이트 필요")
        check(checked.version == version && checked.passed) { "선택 버전 프리플라이트 실패" }
        logs.value = emptyList(); truncated.value = emptySet()
        val orchestrator = RunOrchestrator(store!!, recorder!!, platform.processes, platform.tempFiles,
            preflight = Preflight { saved, config, io ->
                io.recorder.write(io.runId, "preflight/expected.json", io.recorder.json.encodeToString(checked), immutable = true)
                val fresh = checker.inspect(saved, config, io)
                report.value = fresh
                check(fresh.passed && fresh.settings == checked.settings && fresh.metadata == checked.metadata) { "실행 직전 프리플라이트 실패 또는 CLI 변경" }
            }, metadata = checked.metadata)
        engine = orchestrator
        runJob = scope.launch {
            val buffer = VisitLogBuffer()
            val logGate = Mutex()
            val logCollector = launch(start = CoroutineStart.UNDISPATCHED) { orchestrator.logs.collect { logGate.withLock { buffer.add(it) } } }
            val publisher = launch { while (isActive) { delay(100); logGate.withLock { logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet() } } }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                var previous: RunStatus? = null
                orchestrator.state.filterNotNull().collect { current ->
                    state.value = current
                    if (current.status != previous && current.status in setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.AWAITING_USER) && settings.notificationsEnabled) {
                        launch { try { platform.notifier.notify("aiflow · ${current.status}", current.workflow.name) } catch (_: Exception) { } }
                    }
                    previous = current.status
                }
            }
            try { orchestrator.start(version, checked.settings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message }
            finally {
                logCollector.cancelAndJoin(); publisher.cancelAndJoin(); observer.cancelAndJoin()
                logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet()
                state.value = orchestrator.state.value
                history.value = RunHistory(recorder!!).list()
            }
        }
    }
    fun pause() { scope.launch { engine?.pause() } }
    fun resume() { scope.launch { engine?.resume() } }
    fun abort() { scope.launch { engine?.abort() } }
    fun answer(decision: UserDecision) { scope.launch { engine?.decide(decision) } }
    fun showHistory(run: RunState) = action {
        check(!active); state.value = run; engine = null; logs.value = emptyList()
        val buffer = VisitLogBuffer()
        recorder!!.readLogs(run.runId, buffer::add)
        logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet()
        selected.value = versions.value.firstOrNull { it.workflowId == run.workflowId && it.versionId == run.versionId }
        report.value = null
    }
    suspend fun close() {
        gate.withLock {
            engine?.interruptForShutdown(); runJob?.join()
            scope.coroutineContext[Job]?.cancelAndJoin()
            lease?.release(); lease = null
        }
    }
}

class VisitLogBuffer(private val limit: Int = 20_000) {
    private val lines = linkedMapOf<Int?, ArrayDeque<LogLine>>()
    val truncated = mutableSetOf<Int?>()
    fun add(line: LogLine) {
        val visit = lines.getOrPut(line.visitNo) { ArrayDeque() }
        visit.addLast(line)
        if (visit.size > limit) { visit.removeFirst(); truncated += line.visitNo }
    }
    fun snapshot(): List<LogLine> = lines.values.flatMap { it.toList() }
}
