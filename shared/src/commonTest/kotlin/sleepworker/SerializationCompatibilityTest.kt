package sleepworker

import sleepworker.engine.*
import sleepworker.provider.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SerializationCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun agentEventSubclassesPreserveTypeNames() {
        val cases: List<Pair<AgentEvent, String>> = listOf(
            AgentEvent.Raw("hello", Stream.STDOUT) to "sleepworker.provider.AgentEvent.Raw",
            AgentEvent.SessionStarted("s-1") to "sleepworker.provider.AgentEvent.SessionStarted",
            AgentEvent.Message("msg") to "sleepworker.provider.AgentEvent.Message",
            AgentEvent.ToolCall("tool") to "sleepworker.provider.AgentEvent.ToolCall",
            AgentEvent.Diagnostic("diag") to "sleepworker.provider.AgentEvent.Diagnostic",
            AgentEvent.Completed to "sleepworker.provider.AgentEvent.Completed",
            AgentEvent.Failed(FailureInfo(FailureKind.EXIT_CODE, FailurePhase.EXECUTING, "bad")) to "sleepworker.provider.AgentEvent.Failed"
        )
        for ((event, expectedType) in cases) {
            val encoded = json.encodeToString<AgentEvent>(event)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<AgentEvent>(encoded)
            assertEquals(event, decoded)
        }
    }

    @Test
    fun checkResultSubclassesPreserveTypeNames() {
        val cases: List<Pair<CheckResult, String>> = listOf(
            CheckResult.Met to "sleepworker.engine.CheckResult.Met",
            CheckResult.NotMet to "sleepworker.engine.CheckResult.NotMet",
            CheckResult.Error("boom") to "sleepworker.engine.CheckResult.Error"
        )
        for ((check, expectedType) in cases) {
            val encoded = json.encodeToString<CheckResult>(check)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<CheckResult>(encoded)
            assertEquals(check, decoded)
        }
    }

    @Test
    fun decisionSubclassesPreserveTypeNames() {
        val cases: List<Pair<Decision, String>> = listOf(
            Decision.NextStep("step-2", false, 0) to "sleepworker.engine.Decision.NextStep",
            Decision.End to "sleepworker.engine.Decision.End",
            Decision.Ask("user confirmation") to "sleepworker.engine.Decision.Ask"
        )
        for ((decision, expectedType) in cases) {
            val encoded = json.encodeToString<Decision>(decision)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<Decision>(encoded)
            assertEquals(decision, decoded)
        }
    }

    @Test
    fun deserializesEventsJsonlLine() {
        val rawLine = """{"type":"sleepworker.provider.AgentEvent.Raw","text":"sync ready","stream":"STDOUT"}"""
        val decoded = json.decodeFromString<AgentEvent>(rawLine)
        assertEquals(AgentEvent.Raw("sync ready", Stream.STDOUT), decoded)
    }
}
