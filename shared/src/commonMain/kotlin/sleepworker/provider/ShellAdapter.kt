package sleepworker.provider

import sleepworker.platform.*

class ShellAdapter : ProviderAdapter {
    override val id = null
    override fun buildCommand(req: ExecRequest): ProcessSpec {
        val args = if ('\n' in req.script || '\r' in req.script) {
            val path = req.scriptFilePath?.takeIf { it.startsWith('/') } ?: throw ProviderConfigException("Multiline shell requires an absolute script file path")
            listOf("/bin/zsh", "-l", path)
        } else listOf("/bin/zsh", "-lc", req.script)
        return ProcessSpec(args, req.cwd)
    }
    override fun createParser(req: ExecRequest): ProviderParser = object : ProviderParser {
        private var finalized = false
        override fun accept(line: String, stream: Stream): List<AgentEvent> { check(!finalized); return listOf(AgentEvent.Raw(line, stream)) }
        override fun finalizeOutput(exitCode: Int?, termination: Termination): ProviderReport {
            check(!finalized); finalized = true
            return ProviderReport(ProviderOutcome.NOT_APPLICABLE, null, null, null, emptyList())
        }
    }
    override suspend fun probeAuth(exec: ProcessProbe, cfg: ProviderConfig) = AuthStatus.NotApplicable
    override suspend fun listModels(exec: ProcessProbe, cfg: ProviderConfig): List<String>? = null
}
