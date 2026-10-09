package sleepworker.provider

import sleepworker.model.*

class ProviderRegistry(private val codex: ProviderAdapter = CodexAdapter(), private val antigravity: ProviderAdapter = AntigravityAdapter(), private val shell: ProviderAdapter = ShellAdapter()) {
    fun adapterFor(step: Step, workflow: Workflow): ProviderAdapter {
        if (step.effectiveKind == StepKind.SHELL) return shell
        return when (workflow.sessions[step.session?.ref]?.provider) {
            Provider.CODEX -> codex
            Provider.ANTIGRAVITY -> antigravity
            null -> throw ProviderConfigException("Missing agent session")
        }
    }
}
