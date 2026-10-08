package aiflow.provider

import aiflow.model.*

enum class Verification { VERIFIED, UNSUPPORTED, NOT_VERIFIED }
data class ContractEvidence(val status: Verification, val fixture: String, val detail: String)
data class ModelEffort(val model: String, val effort: Effort)
data class VerifiedCliContract(
    val id: String,
    val provider: Provider,
    val version: String,
    val features: Map<String, ContractEvidence>,
    val modelEfforts: Map<ModelEffort, ContractEvidence>,
) {
    /** Fail closed; matching versions or listing a model is not a successful execution measurement. */
    fun validateRequest(actualVersion: String, request: ExecRequest, requiredFeatures: Set<String> = emptySet()): List<FailureInfo> = buildList {
        fun reject(detail: String) { add(FailureInfo(FailureKind.CONFIG, FailurePhase.PREPARING, detail)) }
        if (actualVersion != version) reject("CLI version $actualVersion does not match contract $id ($version)")
        val execution = if (request.resumeSessionId == null) "new" else "resume"
        (requiredFeatures + execution).forEach { feature ->
            if (features[feature]?.status != Verification.VERIFIED) reject("$feature is ${features[feature]?.status ?: Verification.NOT_VERIFIED}")
        }
        if (request.model == null || request.effort == null || modelEfforts[ModelEffort(request.model, request.effort)]?.status != Verification.VERIFIED) reject("Requested model/effort has no verified execution contract")
    }
    companion object {
        private fun measured(path: String, detail: String) = ContractEvidence(Verification.VERIFIED, "scripts/fixtures/phase0/$path", detail)
        val codex = VerifiedCliContract("codex-0.160.0-phase0", Provider.CODEX, "0.160.0", mapOf(
            "new" to measured("codex/bundled-live/new", "exec JSONL, stdin closed, explicit cwd"),
            "resume" to measured("codex/bundled-live/resume", "Explicit thread ID; stdin prompt"),
            "jsonl" to measured("codex/bundled-live/new", "thread.started, agent_message, turn.completed"),
            "unauthenticated" to measured("additional/manicule-v2/codex-unauthenticated-exec", "401 and turn.failed"),
            "resumeSandboxFlag" to ContractEvidence(Verification.UNSUPPORTED, "scripts/fixtures/phase0/codex", "--sandbox after resume rejected; use config overrides"),
        ), mapOf(ModelEffort("gpt-6-astra", Effort.LOW) to measured("additional/manicule-v2/codex-explicit-model", "Explicit -m + low succeeded"), ModelEffort("gpt-6-luna", Effort.MEDIUM) to phase3("codex1600-luna-new", "Explicit Luna medium new/resume measured on 0.160.0")))
        val antigravity = VerifiedCliContract("agy-1.2.16-phase0", Provider.ANTIGRAVITY, "1.2.16", mapOf(
            "new" to measured("agy/live/new", "Single stdout JSON with status SUCCESS"),
            "resume" to measured("agy/live/resume", "Explicit conversation ID"),
            "modelsTsv" to measured("agy/live/models", "slug and label TSV"),
            "resumeOriginalWorkspace" to measured("additional/manicule-v2/agy-resume-other-cwd", "Session stays bound to original workspace"),
            "modelsJson" to ContractEvidence(Verification.UNSUPPORTED, "scripts/fixtures/phase0/agy", "models --output-format json rejected"),
            "unauthenticated" to ContractEvidence(Verification.NOT_VERIFIED, "scripts/phase0-result.md", "Deferred until Phase 3 authentication handling"),
            "effortHelp" to measured("agy", "Help lists low/medium/high/xhigh/max; this does not verify execution"),
        ), mapOf(ModelEffort("gemini-3.8-flash-low", Effort.LOW) to measured("agy/live/new", "Explicit model + low succeeded")))

        private fun phase3(path: String, detail: String) = ContractEvidence(Verification.VERIFIED, "scripts/fixtures/phase3/cli/$path", detail)
        val codex1601 = VerifiedCliContract("codex-0.160.1-phase3", Provider.CODEX, "0.160.1", mapOf(
            "new" to phase3("luna-new", "exec JSONL with stdin and explicit cwd"),
            "resume" to phase3("luna-resume", "Same thread ID resumed successfully"),
            "jsonl" to phase3("luna-new", "thread.started, agent_message, turn.completed"),
        ), mapOf(
            ModelEffort("gpt-6-luna", Effort.MEDIUM) to phase3("luna-new", "CLI execution plus separate user desktop session evidence"),
            ModelEffort("gpt-6-astra", Effort.MEDIUM) to phase3("astra-new", "Explicit requested Astra medium succeeded"),
            ModelEffort("gpt-6-sol", Effort.MEDIUM) to phase3("sol-new", "Explicit requested Sol medium succeeded"),
        ))
        val antigravity130 = VerifiedCliContract("agy-1.3.0-phase3", Provider.ANTIGRAVITY, "1.3.0", mapOf(
            "new" to phase3("agy-logged-out-bounded", "Actual SUCCESS despite reported desktop logout; this is authenticated execution evidence"),
            "resume" to phase3("agy-high-resume", "Same conversation ID resumed with Flash high"),
            "modelsTsv" to phase3("agy-logged-out-models", "TSV catalog; listing alone does not establish authentication"),
            "unauthenticated" to ContractEvidence(Verification.NOT_VERIFIED, "scripts/fixtures/phase3/cli/README.md", "Successful execution measured on 1.3.0; logged-out signature measured separately on 1.3.1"),
        ), mapOf(ModelEffort("gemini-3.8-flash-high", Effort.HIGH) to phase3("agy-logged-out-bounded", "Explicit Flash high model + high effort SUCCESS")))
        val antigravity131 = VerifiedCliContract("agy-1.3.1-phase3", Provider.ANTIGRAVITY, "1.3.1", mapOf(
            "unauthenticated" to phase3("agy131-logged-out-exec", "Exit 1 + JSON ERROR + explicit authentication required; model-list sign-in message also measured"),
            "new" to phase3("agy131-login-new", "Authenticated Flash high execution after login"),
            "resume" to phase3("agy131-login-resume", "Same conversation ID resumed successfully"),
            "modelsTsv" to phase3("agy131-login-models", "Authenticated TSV catalog"),
        ), mapOf(ModelEffort("gemini-3.8-flash-high", Effort.HIGH) to phase3("agy131-login-new", "Explicit Flash high + high succeeded")))
        fun forVersion(provider: Provider, version: String): VerifiedCliContract? =
            listOf(codex, codex1601, antigravity, antigravity130, antigravity131).firstOrNull { it.provider == provider && it.version == version }
    }
}
