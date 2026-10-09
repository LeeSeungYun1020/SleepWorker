package aiflow.provider

import kotlinx.serialization.json.*

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
internal abstract class AgentParser(private val req: ExecRequest) : ProviderParser {
    protected var sessionId = req.resumeSessionId
    protected var output: String? = null
    protected var usage: JsonElement? = null
    protected var complete = false
    protected var malformed = false
    protected var conflict = false
    protected var failure: FailureInfo? = null
    protected var finalized = false
    protected fun checkOpen() { check(!finalized) { "Parser already finalized" } }
    protected fun session(value: String?): List<AgentEvent> {
        if (value.isNullOrBlank()) { malformed = true; return emptyList() }
        if (sessionId != null && sessionId != value) { conflict = true; return emptyList() }
        val first = sessionId == null
        sessionId = value
        return if (first) listOf(AgentEvent.SessionStarted(value)) else emptyList()
    }
    protected fun fail(detail: String, explicitKind: FailureKind? = null): AgentEvent.Failed {
        // Only called for known structured terminal errors, never arbitrary stderr text.
        val kind = explicitKind ?: when {
            req.resumeSessionId != null && explicitSessionRejection(detail) -> FailureKind.SESSION_INVALID
            "401 Unauthorized" in detail -> FailureKind.AUTH
            "You’ve hit your usage limit." in detail -> FailureKind.QUOTA
            else -> FailureKind.PROVIDER
        }
        val info = FailureInfo(kind, FailurePhase.EXECUTING, detail)
        if (failure == null) failure = info
        return AgentEvent.Failed(info)
    }
    private fun explicitSessionRejection(detail: String): Boolean {
        // Only terminal provider errors reach this function. General logs and tool diagnostics do not.
        // Keep the requested ID for evidence; rejecting it never silently starts a new session.
        val subject = "(?:session|thread|conversation)(?:[ _-]?id)?"
        val requested = Regex.escape(req.resumeSessionId!!)
        val rejected = "(?:not found|does not exist|invalid|expired)"
        return Regex(
            "$subject(?:\\s+['\"]?$requested['\"]?)?\\s+(?:(?:was|is)\\s+)?$rejected|(?:invalid|expired)\\s+$subject(?:\\s+['\"]?$requested['\"]?)?",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(detail)
    }
    protected fun report(code: Int?, termination: Termination, events: List<AgentEvent>): ProviderReport {
        finalized = true
        val protocol = when {
            conflict -> "Conflicting session IDs"
            malformed -> "Malformed required provider response"
            !complete -> "Missing terminal response"
            sessionId.isNullOrBlank() -> "Missing session ID"
            output.isNullOrBlank() -> "Empty agent output is not verified"
            termination != Termination.NORMAL -> "Output did not complete normally: $termination"
            code == null -> "No observed exit code"
            else -> null
        }
        val outcome: ProviderOutcome
        val info: FailureInfo?
        when {
            failure != null -> { outcome = ProviderOutcome.FAILED; info = failure }
            protocol != null -> { outcome = ProviderOutcome.PROTOCOL_ERROR; info = FailureInfo(FailureKind.PROTOCOL, FailurePhase.FINALIZING, protocol) }
            code != 0 -> { outcome = ProviderOutcome.FAILED; info = FailureInfo(FailureKind.EXIT_CODE, FailurePhase.FINALIZING, "Exit $code") }
            else -> { outcome = ProviderOutcome.SUCCEEDED; info = null }
        }
        return ProviderReport(outcome, sessionId, output, info, events, usage)
    }
}
internal class CodexParser(req: ExecRequest) : AgentParser(req) {
    override fun accept(line: String, stream: Stream): List<AgentEvent> {
        checkOpen()
        if (stream == Stream.STDERR) return listOf(AgentEvent.Diagnostic(line))
        val obj = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
        if (obj == null) {
            if (line.trimStart().startsWith('{')) malformed = true
            return listOf(AgentEvent.Raw(line, stream))
        }
        return when (obj.string("type")) {
            "thread.started" -> session(obj.string("thread_id"))
            "item.completed" -> {
                val item = obj["item"] as? JsonObject
                when(item?.string("type")) {
                    "agent_message" -> {
                        val text = item.string("text")
                        if (text == null) { malformed = true; emptyList() }
                        else { output = listOfNotNull(output, text).joinToString("\n"); listOf(AgentEvent.Message(text)) }
                    }
                    "command_execution", "mcp_tool_call", "file_change", "web_search" -> listOf(AgentEvent.ToolCall(line))
                    else -> listOf(AgentEvent.Raw(line, stream))
                }
            }
            "turn.completed" -> { usage = obj["usage"]; complete = true; listOf(AgentEvent.Completed) }
            "error" -> if (obj.string("message")?.startsWith("Reconnecting...") == true) listOf(AgentEvent.Diagnostic(line))
                else listOf(fail(obj["error"]?.toString() ?: obj.string("message") ?: line))
            "turn.failed" -> listOf(fail(obj["error"]?.toString() ?: obj.string("message") ?: line))
            else -> listOf(AgentEvent.Raw(line, stream))
        }
    }
    override fun finalizeOutput(exitCode: Int?, termination: Termination): ProviderReport {
        checkOpen()
        return report(exitCode, termination, emptyList())
    }
}
internal class AntigravityParser(req: ExecRequest) : AgentParser(req) {
    private val buffer = StringBuilder()
    private var authenticationRequired = false
    override fun accept(line: String, stream: Stream): List<AgentEvent> {
        checkOpen()
        return if (stream == Stream.STDERR) {
            if (line.trim() == "Error: authentication required. Run 'agy' to log in, then retry.") authenticationRequired = true
            listOf(AgentEvent.Diagnostic(line))
        } else { buffer.appendLine(line); listOf(AgentEvent.Raw(line, stream)) }
    }
    override fun finalizeOutput(exitCode: Int?, termination: Termination): ProviderReport {
        checkOpen()
        val events = mutableListOf<AgentEvent>()
        val obj = try { Json.parseToJsonElement(buffer.toString()) as? JsonObject } catch (_: Exception) { null }
        if (obj == null) malformed = true
        else {
            usage = obj["usage"]
            obj.string("conversation_id")?.takeIf { it.isNotBlank() }?.let { events.addAll(session(it)) }
            obj.string("response")?.let { output = it; events.add(AgentEvent.Message(it)) }
            when {
                obj.string("status") == "ERROR" || (obj["error"] != null && obj["error"] != JsonNull) -> events.add(fail(obj["error"]?.toString() ?: obj.string("response") ?: obj.toString(),
                    if (authenticationRequired && exitCode == 1 && obj.string("error") == "authentication failed or timed out" && obj.string("conversation_id").isNullOrEmpty()) FailureKind.AUTH else null))
                obj.string("status") == "SUCCESS" -> { complete = true; events.add(AgentEvent.Completed) }
                else -> malformed = true
            }
        }
        return report(exitCode, termination, events)
    }
}
