package aiflow.provider

import aiflow.model.Provider
import aiflow.platform.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class AntigravityAdapter : ProviderAdapter {
    override val id = Provider.ANTIGRAVITY
    override fun buildCommand(req: ExecRequest): ProcessSpec {
        req.validate()
        val args = mutableListOf(req.binaryPath, "-p", req.script, "--output-format", "json")
        req.model?.let { args.addAll(listOf("--model", it)) }
        req.effort?.let { args.addAll(listOf("--effort", it.name.lowercase())) }
        args.add("--dangerously-skip-permissions")
        req.resumeSessionId?.let { args.addAll(listOf("--conversation", it)) }
        return ProcessSpec(args, req.cwd)
    }
    override fun createParser(req: ExecRequest): ProviderParser = AntigravityParser(req)
    private suspend fun models(exec: ProcessExecutor, cfg: ProviderConfig) = captureProcess(exec, ProcessSpec(listOf(cfg.binaryPath, "models"), cfg.cwd))
    fun parseModels(text: String): List<String>? {
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return null
        val rows = lines.map { it.split('\t') }
        if (rows.any { it.size != 2 || !Regex("[A-Za-z0-9][A-Za-z0-9._:-]*").matches(it[0]) || it[1].isBlank() }) return null
        return rows.map { it[0] }.distinct()
    }
    override suspend fun probeAuth(exec: ProcessExecutor, cfg: ProviderConfig): AuthStatus = try {
        val result = models(exec, cfg)
        if (result.exitCode == 0 && parseModels(result.stdout.joinToString("\n")) != null) AuthStatus.LoggedIn
        else AuthStatus.Unknown("agy unauthenticated contract NOT_VERIFIED; exit=${result.exitCode}\n${(result.stdout + result.stderr).joinToString("\n")}")
    } catch (e: TimeoutCancellationException) { AuthStatus.Unknown("Authentication probe timed out: ${e.message}") } catch (e: CancellationException) { throw e } catch (e: Exception) { AuthStatus.Unknown(e.toString()) }
    override suspend fun listModels(exec: ProcessExecutor, cfg: ProviderConfig): List<String>? {
        val result = models(exec, cfg)
        return if (result.exitCode == 0) parseModels(result.stdout.joinToString("\n")) else null
    }
}
