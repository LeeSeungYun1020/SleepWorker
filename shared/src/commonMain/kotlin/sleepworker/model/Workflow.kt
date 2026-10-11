package sleepworker.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Workflow(
    val name: String,
    val repoPath: String,
    val baseBranch: String,
    val start: String,
    val worktreeRoot: String = "../${repoPath.trimEnd('/').substringAfterLast('/')}.worktrees",
    val maxSteps: Int = 50,
    val worktrees: List<WorktreeDef> = emptyList(),
    val sessions: Map<String, SessionDef> = emptyMap(),
    val steps: List<Step> = emptyList(),
    val editor: EditorLayout? = null,
)
@Serializable data class WorkflowDraft(val workflowId: String, val restoredFrom: String? = null, val workflow: Workflow)
@Serializable data class WorkflowVersion(val workflowId: String, val versionId: String, val createdAt: Instant, val restoredFrom: String? = null, val workflow: Workflow)
@Serializable data class EditorLayout(val nodes: Map<String, NodePosition>)
@Serializable data class NodePosition(val x: Float, val y: Float)
@Serializable data class WorktreeDef(val name: String, val branch: String)
@Serializable data class SessionDef(val provider: Provider, val workspace: Workspace)
@Serializable enum class Provider { @SerialName("codex") CODEX, @SerialName("antigravity") ANTIGRAVITY }
@Serializable(with = WorkspaceSerializer::class)
sealed interface Workspace {
    data object Local : Workspace
    data class Worktree(val name: String) : Workspace
}
@Serializable enum class StepKind { @SerialName("agent") AGENT, @SerialName("shell") SHELL }
@Serializable enum class SessionMode { @SerialName("new") NEW, @SerialName("resume") RESUME }
@Serializable enum class Effort { @SerialName("low") LOW, @SerialName("medium") MEDIUM, @SerialName("high") HIGH, @SerialName("xhigh") XHIGH, @SerialName("max") MAX }
@Serializable data class SessionRef(val ref: String, val mode: SessionMode)
@Serializable
data class Step(
    val id: String,
    val script: String,
    val title: String? = null,
    val kind: StepKind = StepKind.AGENT,
    val session: SessionRef? = null,
    val model: String? = null,
    val effort: Effort? = null,
    val workspace: Workspace? = null,
    val retryScript: String? = null,
    val timeoutSec: Int? = null,
    val checkTimeoutSec: Int = 300,
    val completion: Completion? = null,
    val transitions: List<Transition> = emptyList(),
) {
    val effectiveKind: StepKind get() = if (kind == StepKind.SHELL || script.trimStart().startsWith('!')) StepKind.SHELL else StepKind.AGENT
    val shellScript: String get() {
        val first = script.indexOfFirst { !it.isWhitespace() }
        return if (first >= 0 && script[first] == '!') script.removeRange(first, first + 1) else script
    }
}
@Serializable data class Transition(val `when`: Condition, val next: Target, val maxVisits: Int? = null, val resetSession: Boolean = false)
@Serializable(with = TargetSerializer::class)
sealed interface Target {
    data class StepId(val id: String) : Target
    data object End : Target
    data object Ask : Target
}
@Serializable(with = CompletionSerializer::class)
sealed interface Completion {
    data object ExitCode : Completion
    data class FileExists(val path: String) : Completion
    data class Command(val cmd: String) : Completion
}
@Serializable(with = ConditionSerializer::class)
sealed interface Condition {
    data object Success : Condition
    data object Failure : Condition
    data object Otherwise : Condition
    data class FileExists(val path: String) : Condition
    data class FileContains(val path: String, val text: String) : Condition
    data class Command(val cmd: String) : Condition
}
