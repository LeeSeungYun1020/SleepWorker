package sleepworker

import sleepworker.model.*
import sleepworker.platform.*
import sleepworker.provider.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class Phase3CliFixtureTest {
    private val root = File(System.getProperty("sleepworker.fixtures")).parentFile.resolve("phase3/cli")
    @Test fun measuredModelPairsAndResume() {
        for ((name, adapter) in listOf("codex1600-luna-new" to CodexAdapter(), "codex1600-luna-resume" to CodexAdapter(), "agy131-login-new" to AntigravityAdapter(), "agy131-login-resume" to AntigravityAdapter(), "luna-new" to CodexAdapter(), "luna-resume" to CodexAdapter(), "astra-new" to CodexAdapter(), "sol-new" to CodexAdapter(), "agy-logged-out-bounded" to AntigravityAdapter(), "agy-high-resume" to AntigravityAdapter())) {
            val dir = root.resolve(name)
            val metadata = Json.parseToJsonElement(dir.resolve("result.json").readText()).jsonObject
            assertEquals("measured", metadata["source"]!!.jsonPrimitive.content)
            assertTrue(metadata["outputComplete"]!!.jsonPrimitive.boolean)
            assertEquals(JsonNull, metadata["termination"])
            val parser = adapter.createParser(ExecRequest("/fixture", "unused", binaryPath = "unused"))
            dir.resolve("stdout.log").readLines().forEach { parser.accept(it, Stream.STDOUT) }
            dir.resolve("stderr.log").readLines().forEach { parser.accept(it, Stream.STDERR) }
            val report = parser.finalizeOutput(metadata["exitCode"]!!.jsonPrimitive.int, Termination.NORMAL)
            assertEquals(ProviderOutcome.SUCCEEDED, report.outcome, name)
            assertNotNull(report.sessionId)
            assertNotNull(report.usage)
        }
    }
    @Test fun measuredLoggedOutProbeAndExecution() = kotlinx.coroutines.test.runTest {
        val dir = root.resolve("agy131-logged-out-models")
        val output = FakeResult(dir.resolve("stdout.log").readLines(), dir.resolve("stderr.log").readLines(), exitCode = 1)
        val adapter = AntigravityAdapter()
        assertEquals(AuthStatus.LoggedOut, adapter.probeAuth(FakeProcessExecutor(output), ProviderConfig("/agy", "/repo", "1.3.1")))
        assertIs<AuthStatus.Unknown>(adapter.probeAuth(FakeProcessExecutor(output), ProviderConfig("/agy", "/repo", "1.2.16")))
        val execution = root.resolve("agy131-logged-out-exec")
        val parser = adapter.createParser(ExecRequest("/repo", "probe", binaryPath = "/agy"))
        execution.resolve("stdout.log").readLines().forEach { parser.accept(it, Stream.STDOUT) }
        execution.resolve("stderr.log").readLines().forEach { parser.accept(it, Stream.STDERR) }
        assertEquals(FailureKind.AUTH, parser.finalizeOutput(1, Termination.NORMAL).failure?.kind)
        val ambiguous = adapter.createParser(ExecRequest("/repo", "probe", binaryPath = "/agy"))
        execution.resolve("stdout.log").readLines().forEach { ambiguous.accept(it, Stream.STDOUT) }
        assertEquals(FailureKind.PROVIDER, ambiguous.finalizeOutput(1, Termination.NORMAL).failure?.kind)
    }
    @Test fun quotaStopsImmediateRetry() {
        val parser = CodexAdapter().createParser(ExecRequest("/repo", "probe", binaryPath = "/codex"))
        parser.accept("""{"type":"turn.failed","error":{"message":"You’ve hit your usage limit."}}""", Stream.STDOUT)
        val report = parser.finalizeOutput(1, Termination.NORMAL)
        assertEquals(FailureKind.QUOTA, report.failure?.kind)
        assertEquals("non-retryable failure", sleepworker.engine.RetryPolicy.skippedReason(agent("x", SessionMode.NEW), sleepworker.engine.StepResult(false, failure = report.failure, bodyStarted = true), "session"))
    }
    @Test fun evidenceRemainsVersionAndEffortSpecificWithoutBlockingExecution() {
        val request = ExecRequest("/fixture", "x", "gpt-6-luna", Effort.MEDIUM, binaryPath = "/codex")
        assertTrue(VerifiedCliContract.forVersion(Provider.CODEX, "0.160.1")!!.validateRequest("0.160.1", request).isEmpty())
        assertTrue(VerifiedCliContract.codex.validateRequest("0.160.0", request).isEmpty())
        assertTrue(VerifiedCliContract.codex1601.validateRequest("0.160.1", request.copy(effort = Effort.LOW)).isEmpty())
        assertNull(VerifiedCliContract.codex1601.modelEfforts[ModelEffort("gpt-6-luna", Effort.LOW)])
        assertNull(VerifiedCliContract.forVersion(Provider.CODEX, "0.160.2"))
        assertEquals(Verification.VERIFIED, VerifiedCliContract.antigravity131.features["unauthenticated"]!!.status)
    }
}
