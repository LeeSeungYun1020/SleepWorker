package aiflow

import aiflow.engine.*
import aiflow.provider.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class SerializationCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun agentEventSubclassesPreserveHistoricalTypeNames() {
        val cases: List<Pair<AgentEvent, String>> = listOf(
            AgentEvent.Raw("hello", Stream.STDOUT) to "aiflow.provider.AgentEvent.Raw",
            AgentEvent.SessionStarted("s-1") to "aiflow.provider.AgentEvent.SessionStarted",
            AgentEvent.Message("msg") to "aiflow.provider.AgentEvent.Message",
            AgentEvent.ToolCall("tool") to "aiflow.provider.AgentEvent.ToolCall",
            AgentEvent.Diagnostic("diag") to "aiflow.provider.AgentEvent.Diagnostic",
            AgentEvent.Completed to "aiflow.provider.AgentEvent.Completed",
            AgentEvent.Failed(FailureInfo(FailureKind.EXIT_CODE, FailurePhase.EXECUTING, "bad")) to "aiflow.provider.AgentEvent.Failed"
        )
        for ((event, expectedType) in cases) {
            val encoded = json.encodeToString<AgentEvent>(event)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<AgentEvent>(encoded)
            assertEquals(event, decoded)
        }
    }

    @Test
    fun checkResultSubclassesPreserveHistoricalTypeNames() {
        val cases: List<Pair<CheckResult, String>> = listOf(
            CheckResult.Met to "aiflow.engine.CheckResult.Met",
            CheckResult.NotMet to "aiflow.engine.CheckResult.NotMet",
            CheckResult.Error("boom") to "aiflow.engine.CheckResult.Error"
        )
        for ((check, expectedType) in cases) {
            val encoded = json.encodeToString<CheckResult>(check)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<CheckResult>(encoded)
            assertEquals(check, decoded)
        }
    }

    @Test
    fun decisionSubclassesPreserveHistoricalTypeNames() {
        val cases: List<Pair<Decision, String>> = listOf(
            Decision.NextStep("step-2", false, 0) to "aiflow.engine.Decision.NextStep",
            Decision.End to "aiflow.engine.Decision.End",
            Decision.Ask("user confirmation") to "aiflow.engine.Decision.Ask"
        )
        for ((decision, expectedType) in cases) {
            val encoded = json.encodeToString<Decision>(decision)
            assertTrue(encoded.contains("\"type\":\"$expectedType\""), "Expected $expectedType in $encoded")
            val decoded = json.decodeFromString<Decision>(encoded)
            assertEquals(decision, decoded)
        }
    }

    @Test
    fun deserializesHistoricalEventsJsonlLine() {
        val rawLine = """{"type":"aiflow.provider.AgentEvent.Raw","text":"sync ready","stream":"STDOUT"}"""
        val decoded = json.decodeFromString<AgentEvent>(rawLine)
        assertEquals(AgentEvent.Raw("sync ready", Stream.STDOUT), decoded)
    }
}
