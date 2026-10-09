package sleepworker

import sleepworker.engine.*
import sleepworker.model.*
import sleepworker.platform.*
import sleepworker.provider.*
import sleepworker.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreflightLifecycleTest {
    private fun platform(h: EngineHarness) = Platform(h.executor, h.fs, h.temporary,
        object : Notifier { override suspend fun notify(title: String, body: String) {} },
        object : PathDetector { override suspend fun detect(binary: String) = "/$binary" },
        object : RepositoryLock { override fun acquire(repoPath: Path) = h.lease }, "/settings.json".toPath())
    private fun harness(fake: FakeProcessExecutor): EngineHarness {
        lateinit var h: EngineHarness
        h = EngineHarness(fake, preflight = Preflight { v, s, io ->
            val report = CliPreflight(platform(h)).inspect(v, s, io)
            check(report.passed) { "Preflight rejected" }
        })
        h.fs.write("/codex".toPath()) { writeUtf8("") }
        return h
    }
    private fun version() = FakeResult(listOf("codex-cli 0.160.0"))
    private fun git() = arrayOf(FakeResult(listOf("true")), FakeResult(), FakeResult(listOf("worktree /repo", "branch refs/heads/main", "")))
    private fun workflow() = agents(agent("a", SessionMode.NEW).copy(model = "gpt-6-astra", effort = Effort.LOW))

    @Test fun timeoutCleanupFailureStopsVersionAndAuthProbesWithoutFurtherCommands() = runTest {
        for (duringAuth in listOf(false, true)) {
            val refusing = FakeResult(delayMs = 60_000, cleanupFailure = ProcessCleanupException("probe termination refused"))
            val fake = if (duringAuth) FakeProcessExecutor(version(), refusing) else FakeProcessExecutor(refusing)
            val h = harness(fake)
            val state = h.run(workflow())
            assertEquals(RunStatus.FAILED, state.status)
            assertEquals(if (duringAuth) 2 else 1, fake.requests.size)
            assertTrue(state.visits.isEmpty())
            assertTrue(state.failure!!.contains("cleanup", ignoreCase = true))
            val n = if (duringAuth) 2 else 1
            assertTrue(h.text(state, "preflight/probes/$n/process.json").contains("probe termination refused"))
            assertFalse(h.recorder.json.decodeFromString<PreflightReport>(h.text(state, "preflight/report.json")).passed)
        }
    }
    @Test fun abortWithRefusingAuthProbeIsFailedRatherThanAborted() = runTest {
        val fake = FakeProcessExecutor(version(), FakeResult(delayMs = 60_000, cleanupFailure = ProcessCleanupException("refused")))
        val h = harness(fake)
        val task = async { h.run(workflow()) }
        runCurrent()
        assertEquals(2, fake.requests.size)
        h.engine.abort()
        val state = task.await()
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(2, fake.requests.size)
        assertTrue(h.text(state, "preflight/probes/2/process.json").contains("refused"))
        assertTrue(h.text(state, "preflight/report.json").contains("ERROR"))
    }
    @Test fun successfulProbeCleanupPreservesAbortAndItsEvidence() = runTest {
        val fake = FakeProcessExecutor(version(), FakeResult(delayMs = 60_000))
        val h = harness(fake)
        val task = async { h.run(workflow()) }
        runCurrent()
        h.engine.abort()
        val state = task.await()
        assertEquals(RunStatus.ABORTED, state.status)
        assertEquals(2, fake.requests.size)
        assertTrue(h.text(state, "preflight/probes/2/process.json").contains("CANCELLED"))
        assertFalse(h.recorder.json.decodeFromString<PreflightReport>(h.text(state, "preflight/report.json")).passed)
    }
    @Test fun failedAuthRetainsCommandsOutputsAndReportAfterHistoryReload() = runTest {
        val fake = FakeProcessExecutor(version(), FakeResult(stderr = listOf("Not logged in"), exitCode = 1), *git())
        val h = harness(fake)
        val state = h.run(workflow())
        assertEquals(RunStatus.FAILED, state.status)
        assertTrue(state.visits.isEmpty())
        assertTrue(h.text(state, "preflight/probes/2/command.txt").contains("login"))
        assertEquals("Not logged in\n", h.text(state, "preflight/probes/2/stderr.log"))
        assertTrue(h.text(state, "preflight/probes/2/process.json").contains("\"exitCode\": 1"))
        val restored = RunHistory(RunRecorder(h.fs, h.lease)).list().single()
        val report = h.recorder.json.decodeFromString<PreflightReport>(h.text(restored, "preflight/report.json"))
        assertFalse(report.passed)
        assertTrue(report.items.any { it.detail == "로그인 필요" })
        assertTrue(h.text(restored, "logs.jsonl").contains("preflight/probes/2"))
    }
    @Test fun successfulShellPreflightIsRecordedBeforeBody() = runTest {
        val fake = FakeProcessExecutor(*git(), FakeResult(listOf("BODY_OK")))
        val h = harness(fake)
        val state = h.run(sleepworker.workflow(shell()))
        assertEquals(RunStatus.COMPLETED, state.status)
        assertTrue(h.recorder.json.decodeFromString<PreflightReport>(h.text(state, "preflight/report.json")).passed)
        assertEquals("true\n", h.text(state, "preflight/probes/1/stdout.log"))
        assertEquals(4, fake.requests.size)
    }
    @Test fun unmeasuredVersionsAndModelsExecuteNewAndResumeForBothProviders() = runTest {
        for (provider in Provider.entries) {
            val isCodex = provider == Provider.CODEX
            val binary = if (isCodex) "codex" else "agy"
            val version = if (isCodex) "0.161.0" else "1.3.2"
            val success = if (isCodex) codex() else FakeResult(listOf("""{"conversation_id":"thread-1","status":"SUCCESS","response":"done"}"""))
            val auth = if (isCodex) FakeResult() else FakeResult(listOf("catalog-model\tCatalog model"))
            val catalog = if (isCodex) emptyArray() else arrayOf(auth)
            val fake = FakeProcessExecutor(FakeResult(listOf("$binary $version")), auth, *catalog, *git(), success, success)
            val h = harness(fake)
            h.fs.write("/$binary".toPath()) { writeUtf8("") }
            val w = agents(agent("new", SessionMode.NEW, sleepworker.model.Target.StepId("resume")), agent("resume", SessionMode.RESUME))
                .let { it.copy(sessions = mapOf("s" to SessionDef(provider, Workspace.Local)),
                    steps = it.steps.map { step -> step.copy(model = "new-model", effort = Effort.MAX) }) }
            val state = h.run(w)
            assertEquals(RunStatus.COMPLETED, state.status, state.failure)
            assertEquals(listOf("new", "resume"), state.visits.map { it.stepId })
            val commands = fake.requests.takeLast(2).map { it.command }
            assertTrue(commands.all { "new-model" in it && ("max" in it || "model_reasoning_effort=max" in it) })
            assertTrue("thread-1" in commands.last())
            assertTrue((if (isCodex) "resume" else "--conversation") in commands.last())
            val report = h.recorder.json.decodeFromString<PreflightReport>(h.text(state, "preflight/report.json"))
            assertTrue(report.passed)
            assertEquals(version to null, report.metadata[provider])
        }
    }
    @Test fun previewAlsoStopsAfterCleanupFailure() = runTest {
        val fake = FakeProcessExecutor(version(), FakeResult(delayMs = 60_000, cleanupFailure = ProcessCleanupException("refused")))
        val h = harness(fake)
        assertFailsWith<UnsafeCleanup> {
            CliPreflight(platform(h)).inspect(h.version(workflow()), AppSettings(codexPath = "/codex"))
        }
        assertEquals(2, fake.requests.size)
    }
    @Test fun modelCatalogCleanupFailureCannotReachGitOrBody() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("agy 1.3.1")),
            FakeResult(listOf("gemini-3.8-flash-high\tFlash high")),
            FakeResult(delayMs = 60_000, cleanupFailure = ProcessCleanupException("catalog cleanup refused")))
        val h = harness(fake)
        h.fs.write("/agy".toPath()) { writeUtf8("") }
        val w = workflow().copy(sessions = mapOf("s" to SessionDef(Provider.ANTIGRAVITY, Workspace.Local)),
            steps = listOf(agent("a", SessionMode.NEW).copy(model = "gemini-3.8-flash-high", effort = Effort.HIGH)))
        val state = h.run(w)
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(3, fake.requests.size)
        assertTrue(state.visits.isEmpty())
        assertTrue(h.text(state, "preflight/probes/3/process.json").contains("catalog cleanup refused"))
    }

}
