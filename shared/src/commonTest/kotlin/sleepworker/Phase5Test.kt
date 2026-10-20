package sleepworker

import sleepworker.engine.*
import sleepworker.model.*
import sleepworker.platform.*
import sleepworker.provider.*
import sleepworker.storage.*
import sleepworker.ui.history.*
import sleepworker.ui.run.*
import sleepworker.ui.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*
import kotlin.time.Instant

class Phase5Test {
    private val fs = FakeFileSystem().apply { allowSymlinks = true; createDirectories("/repo".toPath()) }
    private val lease = object : RepositoryLease {
        override val writeMutex = Mutex(); override val repoPath = "/repo".toPath()
        override fun requireHeld() {}; override fun release() {}
    }
    private val recorder = RunRecorder(fs, lease)
    private val now = Instant.parse("2026-10-09T00:00:00Z")
    private fun run(status: RunStatus = RunStatus.COMPLETED) = RunState("20261009-090000-12345678", "workflow", "version", workflow(shell()), status, lastUpdatedAt = now)
    private fun platform(exec: ProcessExecutor) = Platform(exec, fs, object : TempFiles {
        override fun createScript(content: String) = error("unused"); override fun delete(path: okio.Path) {}
    }, object : Notifier { override suspend fun notify(title: String, body: String) {} }, object : PathDetector {
        override suspend fun detect(binary: String) = "/bin/$binary"
    }, object : RepositoryLock { override fun acquire(repoPath: okio.Path) = lease }, "/settings.json".toPath())

    @Test fun pickerErrorsAreInlineAndCancellationStillPropagates() = runTest {
        var diagnosed = 0
        val failingDialogs = object : FileDialogs {
            override suspend fun directory(): String? = error("picker unavailable")
            override suspend fun file(): String? = error("picker unavailable")
            override suspend fun openYaml(): String? = error("picker unavailable")
            override suspend fun saveYaml(suggestedName: String): String? = error("unused")
        }
        val desktop = platform(FakeProcessExecutor()).copy(fileDialogs = failingDialogs, diagnostics = { diagnosed++ })
        val settings = SettingsViewModel(desktop, backgroundScope)
        val run = sleepworker.ui.run.RunViewModel(desktop)
        try {
            assertNull(settings.chooseFile()); assertNull(settings.chooseDirectory())
            assertNull(run.chooseRepository()); assertNull(run.chooseYaml())
            assertContains(settings.error.value!!, "picker unavailable")
            assertContains(run.error.value!!, "picker unavailable")
            assertNull(run.fatalError.value); assertEquals(4, diagnosed)
        } finally { run.close() }
        val cancelled = SettingsViewModel(desktop.copy(fileDialogs = object : FileDialogs by failingDialogs {
            override suspend fun file(): String? = throw CancellationException("cancelled")
        }), backgroundScope)
        assertFailsWith<CancellationException> { cancelled.chooseFile() }
        assertNull(cancelled.error.value)
    }
    @Test fun workflowUndoNeverRunsOutsideEditorOrWhileTyping() = runTest {
        val run = sleepworker.ui.run.RunViewModel(platform(FakeProcessExecutor()))
        val editor = sleepworker.ui.editor.EditorViewModel(WorkflowStore(fs, lease), fs, "/repo", scope = backgroundScope)
        editor.newWorkflow(sleepworker.ui.editor.WorkflowTemplate.LINEAR)
        val original = editor.workflow!!.name
        editor.edit { it.copy(name = "changed") }; run.editor.value = editor
        try {
            run.undoWorkflow(); assertEquals("changed", editor.workflow!!.name)
            run.editorVisible.value = true
            val field = Any(); run.textInputFocus.update(field, true)
            run.undoWorkflow(); assertEquals("changed", editor.workflow!!.name)
            run.textInputFocus.update(field, false)
            run.undoWorkflow(); assertEquals(original, editor.workflow!!.name)
            run.undoWorkflow(true); assertEquals("changed", editor.workflow!!.name)
        } finally { run.close() }
    }
    @Test fun legacySettingsAndUnknownKeysLoadWithDefaults() = runTest {
        fs.write("/settings.json".toPath()) { writeUtf8("""{"codexPath":"/bin/codex","futureKey":true}""") }
        val settings = SettingsStore(fs, "/settings.json".toPath()).load()
        assertEquals(20_000, settings.logBufferLimit); assertEquals(4, settings.notificationEvents.size)
        assertEquals("/bin/codex", settings.codexPath); assertEquals(ThemeMode.SYSTEM, settings.themeMode)
    }
    @Test fun themeChoicePersistsWithoutChangingCliSettings() = runTest {
        val store = SettingsStore(fs, "/settings.json".toPath())
        store.save(AppSettings(codexPath = "/bin/codex", themeMode = ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, store.load().themeMode)
        assertEquals("/bin/codex", store.load().codexPath)
    }
    @Test fun invalidSettingsDoNotReplacePersistedFile() = runTest {
        val store = SettingsStore(fs, "/settings.json".toPath())
        store.save(AppSettings(codexPath = "/bin/codex"))
        assertFails { store.save(AppSettings(logBufferLimit = 0)) }
        assertEquals("/bin/codex", store.load().codexPath)
    }
    @Test fun settingsTransformsPreserveConcurrentFieldsAndWindow() = runTest {
        val vm = SettingsViewModel(platform(FakeProcessExecutor()), backgroundScope)
        vm.current()
        awaitAll(async { vm.save { it.copy(codexPath = "/bin/codex") } }, async { vm.save { it.copy(window = WindowGeometry(900, 700, 20, 30)) } })
        assertEquals("/bin/codex", vm.current().codexPath)
        assertEquals(900, SettingsStore(fs, "/settings.json".toPath()).load().window.width)
    }
    @Test fun modelRefreshUsesTsvAndFailurePreservesPastCache() = runTest {
        val executor = FakeProcessExecutor(FakeResult(listOf("agy 1.3.2")), FakeResult(listOf("new-model\tNew model")), FakeResult(listOf("agy 1.3.2")), FakeResult(stderr = listOf("offline"), exitCode = 1))
        val vm = SettingsViewModel(platform(executor), backgroundScope)
        vm.save { it.copy(agyPath = "/bin/agy") }
        vm.refreshModels().join()
        val cache = vm.settings.value.agyModelsCache!!
        assertEquals(listOf("new-model"), cache.models)
        assertEquals("1.3.2", cache.binaryVersion)
        assertNull(vm.settings.value.cliChecks["agy"]?.contractId)
        vm.refreshModels().join()
        assertEquals(cache, vm.settings.value.agyModelsCache); assertNotNull(vm.error.value)
        assertEquals(listOf("/bin/agy", "models"), executor.requests[1].command)
        assertTrue(executor.requests.none { "--output-format" in it.command })
    }
    @Test fun codexModelRefreshCachesSeparatelyAndKeepsManualCandidates() = runTest {
        val executor = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.160.0")), FakeResult(listOf("""{"models":[{"slug":"gpt-a","visibility":"list"}]}""")))
        val vm = SettingsViewModel(platform(executor), backgroundScope)
        vm.save { it.copy(codexPath = "/bin/codex", codexModels = listOf("manual", "gpt-a")) }
        vm.refreshModels(Provider.CODEX).join()
        assertEquals(listOf("gpt-a"), vm.settings.value.codexModelsCache?.models)
        assertNull(vm.settings.value.agyModelsCache)
        assertEquals(listOf("gpt-a", "manual"), vm.settings.value.modelCandidates(Provider.CODEX))
        assertEquals(listOf("gpt-a", "manual"), vm.settings.value.modelCandidates(Provider.CODEX, "/bin/codex"))
        assertEquals(listOf("manual", "gpt-a"), vm.settings.value.modelCandidates(Provider.CODEX, "/other/codex"))
    }
    @Test fun claudeModelRefreshSendsInitializeOverStdin() = runTest {
        val init = """{"type":"control_response","response":{"subtype":"success","request_id":"sleepworker-models","response":{"models":[{"value":"opus","resolvedModel":"claude-opus-5-5"}]}}}"""
        val executor = FakeProcessExecutor(FakeResult(listOf("2.1.302 (Claude Code)")), FakeResult(listOf(init)))
        val vm = SettingsViewModel(platform(executor), backgroundScope)
        vm.save { it.copy(claudePath = "/bin/claude") }
        vm.refreshModels(Provider.CLAUDE).join()
        assertNull(vm.error.value)
        assertTrue(executor.requests[1].stdin!!.contains("\"subtype\":\"initialize\""))
        assertEquals("/", executor.requests[1].cwd)
        assertEquals(listOf("opus", "claude-opus-5-5"), vm.settings.value.claudeModelsCache?.models)
        assertNull(vm.settings.value.agyModelsCache); assertNull(vm.settings.value.codexModelsCache)
    }
    @Test fun pathAndCustomVersionAreRecordedIndependently() = runTest {
        val executor = FakeProcessExecutor(FakeResult(listOf("codex 0.146.0")), FakeResult(listOf("codex 0.160.0")))
        val vm = SettingsViewModel(platform(executor), backgroundScope)
        vm.detect("codex").join()
        assertNull(vm.candidates.value["codex"]!!.single().contractId)
        assertNull(vm.settings.value.codexPath)
        vm.save { it.copy(codexPath = "/custom/codex") }; vm.verify("codex").join()
        assertEquals("codex-0.160.0-phase0", vm.settings.value.cliChecks["codex"]?.contractId)
        assertEquals("/custom/codex", vm.settings.value.cliChecks["codex"]?.path)
    }
    @Test fun prereleaseVersionCannotBorrowStableContract() = runTest {
        val vm = SettingsViewModel(platform(FakeProcessExecutor(FakeResult(listOf("codex 0.160.0-alpha.1")))), backgroundScope)
        vm.save { it.copy(codexPath = "/bin/codex") }; vm.verify("codex").join()
        assertEquals("0.160.0-alpha.1", vm.settings.value.cliChecks["codex"]?.version)
        assertNull(vm.settings.value.cliChecks["codex"]?.contractId)
    }
    @Test fun executionSettingsIgnoreWindowChangesButDetectPathRemoval() {
        val a = AppSettings(codexPath = "/bin/codex")
        assertTrue(sameExecutionSettings(a, a.copy(window = WindowGeometry(800, 600))))
        assertFalse(sameExecutionSettings(a, a.copy(codexPath = null)))
    }
    @Test fun fourNotificationsRespectEventSelection() {
        val settings = AppSettings()
        listOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.PAUSED, RunStatus.AWAITING_USER).forEach { assertTrue(notificationEnabled(it, settings)) }
        assertFalse(notificationEnabled(RunStatus.ABORTED, settings))
        assertFalse(notificationEnabled(RunStatus.PAUSED, settings.copy(notificationEvents = setOf(NotificationEvent.COMPLETED))))
        assertFalse(notificationEnabled(RunStatus.COMPLETED, settings.copy(notificationsEnabled = false)))
    }
    @Test fun lazyArtifactPreviewIsBoundedAndDeletionRetainsVersions() = runTest {
        val run = run(); recorder.save(run); recorder.write(run.runId, "stdout.log", "a".repeat(1000))
        fs.createDirectories("/repo/.sleepworker/workflows/w/versions".toPath()); fs.write("/repo/.sleepworker/workflows/w/versions/v.json".toPath()) { writeUtf8("immutable") }
        val history = HistoryViewModel(recorder, FakeProcessExecutor())
        history.select(run); assertEquals("", history.text.value)
        assertTrue(recorder.read(run.runId, "stdout.log", 100).contains("상한"))
        history.read("stdout.log"); assertEquals(1000, history.text.value.length)
        assertFails { recorder.read(run.runId, "../escape") }
        assertFails { recorder.delete(run.runId, false) }
        history.delete(true); assertTrue(recorder.list().isEmpty())
        assertTrue(fs.exists("/repo/.sleepworker/workflows/w/versions/v.json".toPath()))
    }
    @Test fun activeAndSymlinkedRecordsCannotBeDeletedOrRead() = runTest {
        val active = run(RunStatus.RUNNING); recorder.save(active)
        assertFails { recorder.delete(active.runId, true) }
        fs.write("/outside".toPath()) { writeUtf8("private") }
        fs.createSymlink("/repo/.sleepworker/runs/${active.runId}/stdout.log".toPath(), "/outside".toPath())
        assertFails { recorder.files(active.runId) }; assertFails { recorder.read(active.runId, "stdout.log") }
        assertEquals("private", fs.read("/outside".toPath()) { readUtf8() })
    }
    @Test fun worktreeParsingHandlesSpacesLocksAndDetached() {
        val entries = HistoryViewModel.parseWorktrees("worktree /repo\nHEAD abc\nbranch refs/heads/main\n\nworktree /path with spaces\nHEAD def\nbranch refs/heads/topic\nlocked reason\n\nworktree /detached\nHEAD def\ndetached\n")
        assertTrue(entries.first().main); assertEquals("/path with spaces", entries[1].path)
        assertTrue(entries[1].locked); assertNull(entries[2].branch)
    }
    @Test fun resumeCommandAllowsUnmeasuredRequestsAndQuotesShellValues() {
        val step = Step("agent", "prompt", session = SessionRef("s", SessionMode.NEW), model = "gpt-6-luna", effort = Effort.MEDIUM)
        val w = workflow(step).copy(repoPath = "/repo with 'quote'", sessions = mapOf("s" to SessionDef(Provider.CODEX, Workspace.Local)))
        val run = run().copy(workflow = w)
        val attempt = AttemptRecord(1, StepStatus.SUCCEEDED, now, sessionId = "session'1", metadata = ExecutionMetadata("/codex path", "0.160.0", "codex-0.160.0-phase0", "gpt-6-luna", Effort.MEDIUM))
        val visit = StepVisit(1, "agent", StepStatus.SUCCEEDED, now)
        val command = resumeCommand(run, visit, attempt)!!
        assertTrue(command.contains("'session'\\''1'")); assertTrue(command.contains("'resume'"))
        val unmeasured = attempt.copy(metadata = attempt.metadata!!.copy(binaryVersion = "9.9.9", contractId = null, requestedModel = "new-model", requestedEffort = Effort.MAX))
        assertTrue(resumeCommand(run, visit, unmeasured)!!.contains("'new-model'"))
        assertNotNull(resumeCommand(run, visit, unmeasured.copy(metadata = unmeasured.metadata!!.copy(binaryVersion = null))))
        assertNull(resumeCommand(run, visit, attempt.copy(sessionId = "")))
    }
}
