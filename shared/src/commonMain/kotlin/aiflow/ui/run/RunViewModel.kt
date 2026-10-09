package aiflow.ui.run

import aiflow.engine.*
import aiflow.model.*
import aiflow.platform.*
import aiflow.storage.*
import aiflow.ui.editor.EditorViewModel
import aiflow.ui.components.TextInputFocus
import aiflow.ui.history.*
import aiflow.ui.settings.SettingsViewModel
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
    val settingsModel = SettingsViewModel(platform, scope)
    private val settings get() = settingsModel.settings.value
    val textInputFocus = TextInputFocus()
    val editorVisible = MutableStateFlow(false)
    fun undoWorkflow(redo: Boolean = false) {
        if (editorVisible.value && !textInputFocus.active.value && !busy.value) editor.value?.let { if (redo) it.redo() else it.undo() }
    }
    val menuActions = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val editorCommand = MutableStateFlow<Pair<Long, String>?>(null)
    val fatalError = MutableStateFlow<String?>(null)
    fun menu(command: String) { menuActions.tryEmit(command) }
    val historyController = MutableStateFlow<HistoryViewModel?>(null)
    private var previewSettings: AppSettings? = null
    private val checker = CliPreflight(platform)
    val editor = MutableStateFlow<EditorViewModel?>(null)
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
    val pendingRepository = MutableStateFlow<String?>(null)
    val active get() = runJob?.isActive == true
    init { scope.launch {
        var previous: AppSettings? = null
        settingsModel.settings.collect { value ->
            editor.value?.settings = value
            if (!active && previous?.let { it.codexPath != value.codexPath || it.agyPath != value.agyPath || it.codexModels != value.codexModels || it.agyModelsCache != value.agyModelsCache } == true) report.value = null
            previous = value
        }
    } }
    private fun action(block: suspend () -> Unit) { scope.launch { gate.withLock {
        busy.value = true
        try { error.value = null; block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { platform.diagnostics(e); error.value = e.message ?: e.toString() }
        finally { busy.value = false }
    } } }
    private suspend fun pickFile(pick: suspend () -> String?): String? = try { pick() }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { platform.diagnostics(e); error.value = "파일 선택 실패: ${e.message ?: e.toString()}"; null }
    suspend fun chooseRepository(): String? = pickFile { platform.fileDialogs?.directory() }
    suspend fun chooseYaml(): String? = pickFile { platform.fileDialogs?.openYaml() }
    fun openRepository(path: String) = action {
        check(!active) { "실행 종료 후 저장소를 변경하세요" }
        if (editor.value?.dirty?.value == true) { pendingRepository.value = path; return@action }
        if (lease?.repoPath == path.toPath(normalize = true)) return@action
        val next = platform.repositoryLock.acquire(path.toPath())
        try {
            val nextStore = WorkflowStore(platform.files, next)
            val nextRecorder = RunRecorder(platform.files, next)
            val recovered = RunRecovery(nextRecorder).recover()
            val available = nextStore.listWorkflows().flatMap { nextStore.listVersions(it) }
            settingsModel.current()
            historyController.value?.close()
            editor.value?.close(); lease?.release(); lease = next; store = nextStore; recorder = nextRecorder
            pendingImport.value = null; importWarnings.value = emptyList()
            repository.value = next.repoPath.toString(); versions.value = available
            selected.value = available.lastOrNull(); report.value = null
            editor.value = EditorViewModel(nextStore, platform.files, next.repoPath.toString(), settings, platform.fileDialogs,
                isRunning = { id -> active && state.value?.workflowId == id },
                onVersion = { saved -> gate.withLock {
                    if (lease !== next) return@withLock
                    versions.value = nextStore.listWorkflows().flatMap { nextStore.listVersions(it) }
                    if (!active) { selected.value = saved; report.value = null; state.value = null; logs.value = emptyList() }
                } })
            historyController.value = HistoryViewModel(nextRecorder, platform.processes)
            history.value = recovered; state.value = null; logs.value = emptyList(); engine = null
        } catch (e: Throwable) { next.release(); throw e }
    }
    fun cancelRepositoryChange() { pendingRepository.value = null }
    fun confirmRepositoryChange(save: Boolean) = action {
        val path = pendingRepository.value ?: return@action
        if (save) editor.value?.preserveDraftOnShutdown() else editor.value?.discard()
        pendingRepository.value = null
        openRepository(path)
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
    fun runEditorVersion(version: WorkflowVersion) {
        // Bind the request before it waits for the action gate, not after preflight starts.
        val source = editor.value
        val draft = source?.draft?.value
        val revision = source?.revision?.value
        val verifyEditor = {
            val matches = source != null && editor.value === source && source.revision.value == revision &&
                source.draft.value == draft && !source.dirty.value && !source.readOnly &&
                draft?.workflowId == version.workflowId && draft.workflow == version.workflow
            if (!matches) report.value = null
            require(matches) { "편집 내용과 저장 버전이 다릅니다 — 다시 저장·실행하세요" }
        }
        action {
            check(!active)
            verifyEditor()
            require(version.workflow.repoPath.toPath(normalize = true) == lease!!.repoPath) { "워크플로 경로가 열린 저장소와 다릅니다" }
            selected.value = version; state.value = null; report.value = null
            val inputSettings = settings
            val checked = checker.inspect(version, inputSettings)
            previewSettings = inputSettings
            verifyEditor()
            report.value = checked
            source!!.cliVersions.value = checked.metadata.mapNotNull { (key, value) -> value.first?.let { key to it } }.toMap()
            check(checked.passed) { "프리플라이트 실패 — 실행하지 않았습니다" }
            // Stay in the same action so another queued action cannot change the version first.
            startRun(version, checked, verifyEditor)
        }
    }
    fun runPreflight() = action {
        check(!active)
        report.value = null
        val inputSettings = settings
        report.value = checker.inspect(selected.value ?: error("저장 버전 선택 필요"), inputSettings)
        previewSettings = inputSettings
        editor.value?.cliVersions?.value = report.value!!.metadata.mapNotNull { (key, value) -> value.first?.let { key to it } }.toMap()
    }
    fun start() = action {
        startRun(selected.value ?: error("저장 버전 선택 필요"), report.value ?: error("프리플라이트 필요"))
    }
    private fun startRun(version: WorkflowVersion, checked: PreflightReport, verifyEditor: (() -> Unit)? = null) {
        check(!active)
        verifyEditor?.invoke()
        require(version.workflow.repoPath.toPath(normalize = true) == lease!!.repoPath) { "워크플로 경로가 열린 저장소와 다릅니다" }
        check(previewSettings?.let { sameExecutionSettings(it, settings) } == true) { "설정이 변경되었습니다 — 프리플라이트를 다시 수행하세요" }
        check(selected.value == version && checked.version == version && checked.passed) { "선택 버전 프리플라이트 실패" }
        logs.value = emptyList(); truncated.value = emptySet()
        val orchestrator = RunOrchestrator(store!!, recorder!!, platform.processes, platform.tempFiles,
            preflight = Preflight { saved, config, io ->
                verifyEditor?.invoke()
                io.recorder.write(io.runId, "preflight/expected.json", io.recorder.json.encodeToString(checked), immutable = true)
                val fresh = checker.inspect(saved, config, io)
                verifyEditor?.invoke()
                report.value = fresh
                check(fresh.passed && fresh.settings == checked.settings && fresh.metadata == checked.metadata) { "실행 직전 프리플라이트 실패 또는 CLI 변경" }
            }, metadata = checked.metadata)
        engine = orchestrator
        runJob = scope.launch {
            val buffer = VisitLogBuffer(settings.logBufferLimit)
            val logGate = Mutex()
            val logCollector = launch(start = CoroutineStart.UNDISPATCHED) { orchestrator.logs.collect { logGate.withLock { buffer.add(it) } } }
            val publisher = launch { while (isActive) { delay(100); logGate.withLock { logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet() } } }
            fun notifyState(current: RunState) {
                if (notificationEnabled(current.status, settings)) scope.launch {
                    try { platform.notifier.notify("aiflow", notificationLabel(current.status), current.workflow.name) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { platform.diagnostics(e) }
                }
            }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                var previous: RunStatus? = null
                orchestrator.state.filterNotNull().collect { current ->
                    state.value = current
                    if (current.status != previous && !current.status.terminal) notifyState(current)
                    previous = current.status
                }
            }
            try { orchestrator.start(version, checked.settings) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { platform.diagnostics(e); error.value = e.message }
            finally {
                logCollector.cancelAndJoin(); publisher.cancelAndJoin(); observer.cancelAndJoin()
                logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet()
                state.value = orchestrator.state.value
                state.value?.takeIf { it.status.terminal }?.let(::notifyState)
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
        val buffer = VisitLogBuffer(settings.logBufferLimit)
        recorder!!.readLogs(run.runId, buffer::add)
        logs.value = buffer.snapshot(); truncated.value = buffer.truncated.toSet()
        selected.value = versions.value.firstOrNull { it.workflowId == run.workflowId && it.versionId == run.versionId }
        report.value = null
    }
    fun refreshHistory() = action {
        check(!active); history.value = recorder?.list().orEmpty(); historyController.value?.refreshWorktrees()
    }
    fun selectHistory(run: RunState) = action { check(!active); historyController.value!!.select(run) }
    fun readHistoryFile(relative: String) = action { check(!active); historyController.value!!.read(relative) }
    fun deleteHistory() = action { check(!active); historyController.value!!.delete(true); history.value = recorder!!.list() }
    fun removeHistoryWorktree(entry: WorktreeEntry) = action { check(!active); historyController.value!!.removeWorktree(entry, true) }
    fun openHistoryVersion(restore: Boolean, import: Boolean = false) = action {
        check(!active)
        val run = historyController.value!!.selected.value ?: error("런 선택 필요")
        val target = editor.value ?: error("편집기 없음")
        target.preserveDraftOnShutdown()
        if (import) target.importSnapshot(run.workflow)
        else {
            val version = versions.value.firstOrNull { it.workflowId == run.workflowId && it.versionId == run.versionId } ?: error("원본 버전 파일 없음")
            target.open(version.workflowId)
            if (restore) target.restore(version, true) else target.showVersion(version)
        }
    }
    suspend fun refreshVersions() = gate.withLock {
        versions.value = store?.listWorkflows()?.flatMap { store!!.listVersions(it) }.orEmpty()
        if (selected.value !in versions.value) { selected.value = versions.value.lastOrNull(); report.value = null }
    }
    suspend fun close() {
        gate.withLock {
            settingsModel.close(); historyController.value?.close()
            engine?.interruptForShutdown(); runJob?.join()
            // Native Quit may bypass Compose confirmation; preserve unfinished work as a draft.
            editor.value?.let { editor ->
                try { editor.preserveDraftOnShutdown() } catch (e: Exception) { error.value = "종료 시 초안 저장 실패: ${e.message}" }
                editor.close()
            }
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

fun notificationEnabled(status: RunStatus, settings: AppSettings): Boolean = settings.notificationsEnabled &&
    NotificationEvent.entries.firstOrNull { it.name == status.name } in settings.notificationEvents
fun notificationLabel(status: RunStatus): String = when (status) {
    RunStatus.COMPLETED -> "실행 완료"; RunStatus.FAILED -> "실행 실패"; RunStatus.AWAITING_USER -> "사용자 확인 필요"; RunStatus.PAUSED -> "일시정지됨"; else -> status.name
}

fun sameExecutionSettings(a: AppSettings, b: AppSettings): Boolean =
    a.codexPath == b.codexPath && a.agyPath == b.agyPath && a.codexModels == b.codexModels && a.agyModelsCache == b.agyModelsCache
