package sleepworker.engine

import sleepworker.model.*
import sleepworker.provider.*

object RetryPolicy {
    fun skippedReason(step: Step, result: StepResult, boundId: String?): String? = when {
        result.success -> "succeeded"
        result.cleanupError != null -> "process cleanup unconfirmed"
        result.termination == Termination.CANCELLED -> "cancelled"
        !result.bodyStarted -> "body not started"
        result.failure?.kind in setOf(FailureKind.AUTH, FailureKind.QUOTA, FailureKind.CONFIG, FailureKind.SESSION_MISSING, FailureKind.SESSION_INVALID) -> "non-retryable failure"
        step.effectiveKind == StepKind.AGENT && boundId == null -> "session id unavailable"
        else -> null
    }
    fun script(step: Step, result: StepResult): String = if (step.effectiveKind == StepKind.SHELL) step.shellScript else step.retryScript
        ?: "Retry the current task. Previous attempt failed: ${result.failure?.detail ?: "unknown failure"}; exit code: ${result.exitCode ?: "unavailable"}; completion: ${result.completionResult?.result ?: "not checked"}."
}
