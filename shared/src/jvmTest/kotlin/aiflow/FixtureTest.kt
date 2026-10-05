package aiflow

import aiflow.model.*
import aiflow.provider.*
import aiflow.storage.WorkflowCodec
import kotlinx.serialization.json.*
import okio.fakefilesystem.FakeFileSystem
import java.io.File
import kotlin.test.*

/** Replays committed measured evidence; never starts a model or uses authenticated CLI state. */
class FixtureTest {
    private val root = File(System.getProperty("aiflow.fixtures"))
    @Test fun exampleRoundtripAndValidation() {
        val source = javaClass.getResource("/feature-dev.yaml")!!.readText()
        val codec = WorkflowCodec()
        val workflow = codec.decode(source)
        assertEquals(workflow, codec.decode(codec.encode(workflow)))
        assertTrue(WorkflowValidator(FakeFileSystem()).validate(workflow).none { it.severity == Severity.ERROR })
        assertEquals("sync", workflow.start)
        assertEquals(10, workflow.steps.size)
    }
    private fun replay(path: String, adapter: ProviderAdapter, source: String = "measured"): ProviderReport {
        val dir = root.resolve(path)
        val meta = Json.parseToJsonElement(dir.resolve("result.json").readText()).jsonObject
        assertEquals(source, meta["source"]!!.jsonPrimitive.content)
        val code = meta["exitCode"]!!.jsonPrimitive.intOrNull
        val termination = when(meta["termination"]?.jsonPrimitive?.contentOrNull) {
            "timeout" -> Termination.TIMED_OUT
            "cancelled" -> Termination.CANCELLED
            else -> Termination.NORMAL
        }
        val parser = adapter.createParser(ExecRequest("/fixture", "unused", binaryPath = "unused"))
        dir.resolve("stdout.log").readLines().forEach { parser.accept(it, Stream.STDOUT) }
        dir.resolve("stderr.log").readLines().forEach { parser.accept(it, Stream.STDERR) }
        return parser.finalizeOutput(code, termination)
    }
    @Test fun measuredCodex() {
        listOf("codex/bundled-live/new", "codex/bundled-live/resume", "additional/manicule-v2/codex-explicit-model").forEach {
            val report = replay(it, CodexAdapter())
            assertEquals(ProviderOutcome.SUCCEEDED, report.outcome, "$it: $report")
            assertFalse(report.sessionId.isNullOrBlank())
        }
        assertEquals(ProviderOutcome.FAILED, replay("codex/bundled-live/invalid-model", CodexAdapter()).outcome)
        val unauthenticated = replay("additional/manicule-v2/codex-unauthenticated-exec", CodexAdapter())
        assertEquals(ProviderOutcome.FAILED, unauthenticated.outcome)
        assertEquals(FailureKind.AUTH, unauthenticated.failure?.kind)
    }
    @Test fun measuredAntigravity() {
        listOf("agy/live/new", "agy/live/resume", "additional/manicule-v2/agy-new-manicule", "additional/manicule-v2/agy-resume-other-cwd").forEach {
            val report = replay(it, AntigravityAdapter())
            assertEquals(ProviderOutcome.SUCCEEDED, report.outcome, "$it: $report")
            assertTrue(report.finalEvents.any { it is AgentEvent.SessionStarted })
        }
        listOf("additional/manicule-v2/agy-invalid-model-no-effort", "additional/manicule-v2/agy-interrupt-retry-d1039d").forEach {
            assertEquals(ProviderOutcome.FAILED, replay(it, AntigravityAdapter()).outcome)
        }
        val models = AntigravityAdapter().parseModels(root.resolve("agy/live/models/stdout.log").readText())!!
        assertTrue("gemini-3.8-flash-low" in models)
    }
    @Test fun syntheticEvidenceRemainsDistinguished() {
        val cases = listOf(
            Triple("codex-explicit-failure", CodexAdapter(), ProviderOutcome.FAILED),
            Triple("agy-explicit-failure", AntigravityAdapter(), ProviderOutcome.FAILED),
            Triple("agy-truncated", AntigravityAdapter(), ProviderOutcome.PROTOCOL_ERROR),
            Triple("codex-missing-session", CodexAdapter(), ProviderOutcome.PROTOCOL_ERROR),
            Triple("codex-unknown-log", CodexAdapter(), ProviderOutcome.PROTOCOL_ERROR),
            Triple("shell-empty-success", ShellAdapter(), ProviderOutcome.NOT_APPLICABLE),
        )
        cases.forEach { (path, adapter, expected) -> assertEquals(expected, replay("synthetic/$path", adapter, "synthetic").outcome, path) }
    }
}
