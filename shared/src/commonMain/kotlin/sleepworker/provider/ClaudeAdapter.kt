package sleepworker.provider

import sleepworker.model.Provider
import sleepworker.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*

class ClaudeAdapter : ProviderAdapter {
    override val id = Provider.CLAUDE
    override fun buildCommand(req: ExecRequest): ProcessSpec {
        req.validate()
        val args = mutableListOf(req.binaryPath, "-p", "--output-format", "stream-json", "--verbose")
        req.model?.let { args.addAll(listOf("--model", it)) }
        req.effort?.let { args.addAll(listOf("--effort", it.name.lowercase())) }
        args.add("--dangerously-skip-permissions")
        req.resumeSessionId?.let { args.addAll(listOf("--resume", it)) }
        return ProcessSpec(args, req.cwd, stdin = req.script)
    }
    override fun createParser(req: ExecRequest): ProviderParser = ClaudeParser(req)
    override suspend fun probeAuth(exec: ProcessProbe, cfg: ProviderConfig): AuthStatus = try {
        val result = exec.capture(ProcessSpec(listOf(cfg.binaryPath, "auth", "status"), cfg.cwd))
        val status = try { Json.parseToJsonElement(result.stdout.joinToString("\n")) as? JsonObject } catch (_: Exception) { null }
        when ((status?.get("loggedIn") as? JsonPrimitive)?.booleanOrNull) {
            true -> if (result.exitCode == 0) AuthStatus.LoggedIn else AuthStatus.Unknown("loggedIn=true with exit=${result.exitCode}")
            false -> AuthStatus.LoggedOut
            null -> AuthStatus.Unknown("claude auth status response unrecognized; version=${cfg.version}; exit=${result.exitCode}\n${(result.stdout + result.stderr).joinToString("\n")}")
        }
    } catch (e: TimeoutCancellationException) { AuthStatus.Unknown("Authentication probe timed out: ${e.message}") } catch (e: CancellationException) { throw e } catch (e: Exception) { AuthStatus.Unknown(e.toString()) }
    /** Reads `models` from the stream-json `initialize` control response; `default` is dropped because omitting the model already means it. */
    fun parseModels(lines: List<String>): List<String>? {
        val response = lines.firstNotNullOfOrNull { line ->
            val message = try { Json.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null }
            (message?.get("response") as? JsonObject)?.takeIf { message["type"] == JsonPrimitive("control_response") && it["request_id"] == JsonPrimitive(MODELS_REQUEST_ID) }
        } ?: return null
        if (response["subtype"] != JsonPrimitive("success")) return null
        val models = ((response["response"] as? JsonObject)?.get("models") as? JsonArray) ?: return null
        val values = models.map { ((it as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null }
        return values.filter { it != "default" }.distinct().ifEmpty { null }
    }
    override suspend fun listModels(exec: ProcessProbe, cfg: ProviderConfig): List<String>? {
        val request = """{"type":"control_request","request_id":"$MODELS_REQUEST_ID","request":{"subtype":"initialize"}}"""
        val result = exec.capture(ProcessSpec(listOf(cfg.binaryPath, "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose"), cfg.cwd, stdin = request + "\n"))
        return if (result.exitCode == 0) parseModels(result.stdout) else null
    }
    private companion object { const val MODELS_REQUEST_ID = "sleepworker-models" }
}
