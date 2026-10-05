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
        ), mapOf(ModelEffort("gpt-6-astra", Effort.LOW) to measured("additional/manicule-v2/codex-explicit-model", "Explicit -m + low succeeded; Luna not yet measured")))
        val antigravity = VerifiedCliContract("agy-1.2.16-phase0", Provider.ANTIGRAVITY, "1.2.16", mapOf(
            "new" to measured("agy/live/new", "Single stdout JSON with status SUCCESS"),
            "resume" to measured("agy/live/resume", "Explicit conversation ID"),
            "modelsTsv" to measured("agy/live/models", "slug and label TSV"),
            "resumeOriginalWorkspace" to measured("additional/manicule-v2/agy-resume-other-cwd", "Session stays bound to original workspace"),
            "modelsJson" to ContractEvidence(Verification.UNSUPPORTED, "scripts/fixtures/phase0/agy", "models --output-format json rejected"),
            "unauthenticated" to ContractEvidence(Verification.NOT_VERIFIED, "scripts/phase0-result.md", "Deferred until Phase 3 authentication handling"),
            "effortHelp" to measured("agy", "Help lists low/medium/high/xhigh/max; this does not verify execution"),
        ), mapOf(ModelEffort("gemini-3.8-flash-low", Effort.LOW) to measured("agy/live/new", "Explicit model + low succeeded")))
    }
}
