package aiflow

import aiflow.engine.*
import aiflow.model.*
import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.*
import aiflow.ui.run.VisitLogBuffer
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import okio.Path.Companion.toPath
import kotlin.test.*

class PreflightTest {
    private fun platform(h: EngineHarness) = Platform(h.executor, h.fs, h.temporary,
        object : Notifier { override suspend fun notify(title: String, body: String) {} },
        object : PathDetector { override suspend fun detect(binary: String) = "/$binary" },
        object : RepositoryLock { override fun acquire(repoPath: okio.Path) = h.lease }, "/settings.json".toPath())
    private fun git() = arrayOf(FakeResult(listOf("true")), FakeResult(), FakeResult(listOf("worktree /repo", "branch refs/heads/main", "")))
    @Test fun shellNeedsNoAgentProbes() = runTest {
        val fake = FakeProcessExecutor(*git())
        val h = EngineHarness(fake)
        val report = CliPreflight(platform(h)).inspect(h.version(workflow(shell())), AppSettings())
        assertTrue(report.passed, report.items.toString())
        assertTrue(fake.requests.all { it.command.first() == "git" })
    }
    @Test fun wrongVersionBlocksWithoutAuthOrModelExecution() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.146.0")), *git())
        val h = EngineHarness(fake)
        h.fs.write("/codex".toPath()) { writeUtf8("") }
        val report = CliPreflight(platform(h)).inspect(h.version(agents(agent("a", SessionMode.NEW))), AppSettings())
        assertFalse(report.passed)
        assertEquals(1, fake.requests.count { it.command.first() == "/codex" })
        assertTrue(report.items.any { "0.146.0" in it.detail })
    }
    @Test fun loggedOutAndUnknownAreDistinctAndBlock() = runTest {
        for (result in listOf(FakeResult(stderr = listOf("Not logged in"), exitCode = 1), FakeResult(stderr = listOf("network"), exitCode = 2))) {
            val fake = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.160.0")), result, *git())
            val h = EngineHarness(fake)
            h.fs.write("/codex".toPath()) { writeUtf8("") }
            val report = CliPreflight(platform(h)).inspect(h.version(agents(agent("a", SessionMode.NEW).copy(model = "gpt-6-astra", effort = Effort.LOW))), AppSettings())
            assertFalse(report.passed)
            assertTrue(report.items.any { it.name == "codex 인증" && it.detail.contains(if (result.exitCode == 1) "로그인 필요" else "인증 상태 확인 불가") })
        }
    }
    @Test fun verifiedRequestPassesAndPreservesExplicitPath() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.160.0")), FakeResult(), *git())
        val h = EngineHarness(fake)
        h.fs.write("/chosen".toPath()) { writeUtf8("") }
        val report = CliPreflight(platform(h)).inspect(h.version(agents(agent("a", SessionMode.NEW).copy(model = "gpt-6-astra", effort = Effort.LOW))), AppSettings(codexPath = "/chosen"))
        assertTrue(report.passed, report.items.toString())
        assertEquals("/chosen", report.settings.codexPath)
        assertEquals("0.160.0", report.metadata[Provider.CODEX]?.first)
    }
    @Test fun unmeasuredModelIsNotSilentlySubstituted() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.160.0")), FakeResult(), *git())
        val h = EngineHarness(fake)
        h.fs.write("/codex".toPath()) { writeUtf8("") }
        val report = CliPreflight(platform(h)).inspect(h.version(agents(agent("a", SessionMode.NEW).copy(model = "gpt-6-luna", effort = Effort.LOW))), AppSettings())
        assertFalse(report.passed)
        assertTrue(report.items.any { "model/effort" in it.detail })
    }
    @Test fun authTimeoutBlocksAndCleansProbe() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("codex-cli 0.160.0")), FakeResult(delayMs = 31_000), *git())
        val h = EngineHarness(fake)
        h.fs.write("/codex".toPath()) { writeUtf8("") }
        val report = CliPreflight(platform(h)).inspect(h.version(agents(agent("a", SessionMode.NEW))), AppSettings())
        assertFalse(report.passed)
        assertEquals(1, fake.killed)
    }
    @Test fun worktreeBranchMismatchBlocksWithoutMutation() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("true")), FakeResult(),
            FakeResult(listOf("worktree /repo.worktrees/test", "branch refs/heads/wrong", "")), FakeResult())
        val h = EngineHarness(fake)
        h.fs.createDirectories("/repo.worktrees/test".toPath())
        val w = workflow(shell()).copy(worktrees = listOf(WorktreeDef("test", "ai/test")))
        val report = CliPreflight(platform(h)).inspect(h.version(w), AppSettings())
        assertFalse(report.passed)
        assertTrue(report.items.any { "불일치" in it.detail })
        assertFalse(fake.requests.any { "add" in it.command })
    }
    @Test fun logsAreBoundedPerVisitAndRetainAttemptAndPhase() {
        val buffer = VisitLogBuffer(2)
        val time = Instant.parse("2026-10-06T00:00:00Z")
        for (n in 1..3) buffer.add(LogLine(1, n, "phase$n", Stream.STDOUT, "$n", time))
        buffer.add(LogLine(2, 1, "completion", Stream.STDERR, "check", time))
        assertEquals(listOf("2", "3", "check"), buffer.snapshot().map { it.text })
        assertEquals(setOf<Int?>(1), buffer.truncated)
        assertEquals(2, buffer.snapshot().first().attemptNo)
    }
}
