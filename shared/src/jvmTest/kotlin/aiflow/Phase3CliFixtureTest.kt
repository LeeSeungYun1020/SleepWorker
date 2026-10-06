package aiflow

import aiflow.model.*
import aiflow.platform.*
import aiflow.provider.*
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

class Phase3CliFixtureTest {
    private val root = File(System.getProperty("aiflow.fixtures")).parentFile.resolve("phase3/cli")
    @Test fun measuredModelPairsAndResume() {
        for ((name, adapter) in listOf("luna-new" to CodexAdapter(), "luna-resume" to CodexAdapter(), "astra-new" to CodexAdapter(), "sol-new" to CodexAdapter(), "agy-logged-out-bounded" to AntigravityAdapter(), "agy-high-resume" to AntigravityAdapter())) {
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
    @Test fun contractsAreVersionAndEffortSpecific() {
        val request = ExecRequest("/fixture", "x", "gpt-6-luna", Effort.MEDIUM, binaryPath = "/codex")
        assertTrue(VerifiedCliContract.forVersion(Provider.CODEX, "0.160.1")!!.validateRequest("0.160.1", request).isEmpty())
        assertTrue(VerifiedCliContract.codex.validateRequest("0.160.0", request).isNotEmpty())
        assertTrue(VerifiedCliContract.codex1601.validateRequest("0.160.1", request.copy(effort = Effort.LOW)).isNotEmpty())
        assertNull(VerifiedCliContract.forVersion(Provider.CODEX, "0.160.2"))
        assertEquals(Verification.NOT_VERIFIED, VerifiedCliContract.antigravity130.features["unauthenticated"]!!.status)
    }
}
