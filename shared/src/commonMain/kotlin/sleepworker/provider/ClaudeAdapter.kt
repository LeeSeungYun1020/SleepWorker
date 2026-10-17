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
    override suspend fun listModels(exec: ProcessProbe, cfg: ProviderConfig): List<String>? = null
}
