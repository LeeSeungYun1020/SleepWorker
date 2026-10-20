package sleepworker.provider

import sleepworker.model.Provider
import sleepworker.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.*

class CodexAdapter : ProviderAdapter {
    override val id = Provider.CODEX
    override fun buildCommand(req: ExecRequest): ProcessSpec {
        req.validate()
        val args = mutableListOf(req.binaryPath, "exec", "--json", "-C", req.cwd)
        req.model?.let { args.addAll(listOf("-m", it)) }
        req.effort?.let { args.addAll(listOf("-c", "model_reasoning_effort=${it.name.lowercase()}")) }
        args.addAll(listOf("-c", "sandbox_mode=danger-full-access", "-c", "approval_policy=never"))
        req.resumeSessionId?.let { args.addAll(listOf("resume", it)) }
        args.add("-")
        return ProcessSpec(args, req.cwd, stdin = req.script)
    }
    override fun createParser(req: ExecRequest): ProviderParser = CodexParser(req)
    override suspend fun probeAuth(exec: ProcessProbe, cfg: ProviderConfig): AuthStatus = try {
        val result = exec.capture(ProcessSpec(listOf(cfg.binaryPath, "login", "status"), cfg.cwd))
        when {
            result.exitCode == 0 -> AuthStatus.LoggedIn
            result.exitCode == 1 && result.stderr.any { it.trim() == "Not logged in" } -> AuthStatus.LoggedOut
            else -> AuthStatus.Unknown("exit=${result.exitCode}\n${(result.stdout + result.stderr).joinToString("\n")}")
        }
    } catch (e: TimeoutCancellationException) { AuthStatus.Unknown("Authentication probe timed out: ${e.message}") } catch (e: CancellationException) { throw e } catch (e: Exception) { AuthStatus.Unknown(e.toString()) }
    /** `codex debug models` catalog; malformed entries and those hidden from the picker (`visibility != "list"`) are skipped. */
    fun parseModels(text: String): List<String>? {
        val models = try { (Json.parseToJsonElement(text) as? JsonObject)?.get("models") as? JsonArray } catch (_: Exception) { null } ?: return null
        val slugs = models.mapNotNull { entry ->
            val model = entry as? JsonObject ?: return@mapNotNull null
            val slug = (model["slug"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            slug.takeIf { (model["visibility"] as? JsonPrimitive)?.contentOrNull == "list" }
        }
        return slugs.distinct().ifEmpty { null }
    }
    override suspend fun listModels(exec: ProcessProbe, cfg: ProviderConfig): List<String>? {
        val result = exec.capture(ProcessSpec(listOf(cfg.binaryPath, "debug", "models"), cfg.cwd))
        return if (result.exitCode == 0) parseModels(result.stdout.joinToString("\n")) else null
    }
}
