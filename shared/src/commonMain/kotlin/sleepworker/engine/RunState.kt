package sleepworker.engine

import sleepworker.model.*
import sleepworker.model.Target
import sleepworker.provider.*
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable enum class RunStatus { IDLE, PREFLIGHT, RUNNING, PAUSE_REQUESTED, PAUSED, AWAITING_USER, COMPLETED, FAILED, ABORTED, INTERRUPTED;
    val terminal: Boolean get() = this in setOf(COMPLETED, FAILED, ABORTED, INTERRUPTED)
}
@Serializable enum class StepStatus { PENDING, PREPARING, EXECUTING, FINALIZING, CHECKING, SUCCEEDED, RETRYING, AWAITING_USER, SKIPPED, FAILED, INTERRUPTED }
@Serializable sealed interface CheckResult {
    @Serializable @SerialName("sleepworker.engine.CheckResult.Met") data object Met : CheckResult
    @Serializable @SerialName("sleepworker.engine.CheckResult.NotMet") data object NotMet : CheckResult
    @Serializable @SerialName("sleepworker.engine.CheckResult.Error") data class Error(val reason: String) : CheckResult
}
@Serializable data class CommandResult(val exitCode: Int? = null, val termination: Termination = Termination.NORMAL, val stdout: List<String> = emptyList(), val stderr: List<String> = emptyList(), val elapsedMs: Long = 0, val error: String? = null, val cleanupError: String? = null, val bodyStarted: Boolean = false)
@Serializable data class CheckRecord(val result: CheckResult, val command: CommandResult? = null)
@Serializable data class ExecutionMetadata(val binaryPath: String, val binaryVersion: String? = null, val contractId: String? = null, val requestedModel: String? = null, val requestedEffort: Effort? = null)
@Serializable data class StepResult(val success: Boolean, val exitCode: Int? = null, val termination: Termination = Termination.NORMAL, val providerReport: ProviderReport? = null, val completionResult: CheckRecord? = null, val failure: FailureInfo? = null, val sessionId: String? = null, val bodyStarted: Boolean = false, val finalOutput: String? = null, val cleanupError: String? = null)
@Serializable data class AttemptRecord(val attemptNo: Int, val status: StepStatus, val startedAt: Instant, val endedAt: Instant? = null, val sessionId: String? = null, val metadata: ExecutionMetadata? = null, val result: StepResult? = null, val retrySkippedReason: String? = null, val failureDetail: String? = null)
@Serializable data class SessionBinding(val id: String, val provider: Provider, val workspace: String)
@Serializable data class TransitionTaken(val stepId: String, val index: Int, val condition: Condition, val target: Target)
@Serializable sealed interface Decision {
    @Serializable @SerialName("sleepworker.engine.Decision.NextStep") data class NextStep(val id: String, val resetSession: Boolean, val transitionIndex: Int) : Decision
    @Serializable @SerialName("sleepworker.engine.Decision.End") data object End : Decision
    @Serializable @SerialName("sleepworker.engine.Decision.Ask") data class Ask(val reason: String) : Decision
}
@Serializable data class UserControl(val action: String, val timestamp: Instant)
@Serializable data class StepVisit(val visitNo: Int, val stepId: String, val status: StepStatus, val startedAt: Instant, val endedAt: Instant? = null, val effectiveMode: SessionMode? = null, val manualRetryOf: Int? = null, val boundSessionId: String? = null, val attempts: List<AttemptRecord> = emptyList(), val result: StepResult? = null, val transitionTaken: TransitionTaken? = null, val conditionEvaluations: Map<Int, CheckRecord> = emptyMap(), val decision: Decision? = null, val controls: List<UserControl> = emptyList())
@Serializable data class RunState(val runId: String, val workflowId: String, val versionId: String, val workflow: Workflow, val status: RunStatus, val visits: List<StepVisit> = emptyList(), val currentVisit: Int? = null, val sessionIds: Map<String, String> = emptyMap(), val sessionBindings: Map<String, SessionBinding> = emptyMap(), val visitCounts: Map<String, Int> = emptyMap(), val transitionCounts: Map<String, Int> = emptyMap(), val interruptedAt: Instant? = null, val interruptionReason: String? = null, val previousStatus: RunStatus? = null, val previousUpdatedAt: Instant? = null, val lastUpdatedAt: Instant, val failure: String? = null, val startedAt: Instant? = null)
@Serializable data class LogLine(val visitNo: Int?, val attemptNo: Int?, val phase: String, val stream: Stream, val text: String, val timestamp: Instant, val event: AgentEvent? = null)
enum class UserDecision { Retry, Skip, Abort }
