package aiflow.provider

import aiflow.model.*
import aiflow.platform.*

interface ProviderAdapter {
    val id: Provider?
    fun buildCommand(req: ExecRequest): ProcessSpec
    fun createParser(req: ExecRequest): ProviderParser
    suspend fun probeAuth(exec: ProcessExecutor, cfg: ProviderConfig): AuthStatus
    suspend fun listModels(exec: ProcessExecutor, cfg: ProviderConfig): List<String>?
}
interface ProviderParser {
    fun accept(line: String, stream: Stream): List<AgentEvent>
    fun finalizeOutput(exitCode: Int?, termination: Termination): ProviderReport
}
data class ExecRequest(val cwd: String, val script: String, val model: String? = null, val effort: Effort? = null, val resumeSessionId: String? = null, val binaryPath: String, val scriptFilePath: String? = null)
data class ProviderConfig(val binaryPath: String, val cwd: String, val version: String? = null)
enum class Stream { STDOUT, STDERR }
enum class ProviderOutcome { SUCCEEDED, FAILED, PROTOCOL_ERROR, NOT_APPLICABLE }
enum class Termination { NORMAL, TIMED_OUT, CANCELLED, START_FAILED, OUTPUT_INCOMPLETE }
enum class FailureKind { PREPARATION, AUTH, CONFIG, SESSION_MISSING, SESSION_INVALID, PROVIDER, PROTOCOL, EXIT_CODE, TIMEOUT, OUTPUT_IO, COMPLETION }
enum class FailurePhase { PREPARING, EXECUTING, FINALIZING, CHECKING }
data class FailureInfo(val kind: FailureKind, val phase: FailurePhase, val detail: String)
data class ProviderReport(val outcome: ProviderOutcome, val sessionId: String?, val finalOutput: String?, val failure: FailureInfo?, val finalEvents: List<AgentEvent>)
sealed interface AgentEvent {
    data class SessionStarted(val id: String) : AgentEvent
    data class Message(val text: String) : AgentEvent
    data class ToolCall(val raw: String) : AgentEvent
    data class Diagnostic(val text: String) : AgentEvent
    data class Raw(val text: String, val stream: Stream) : AgentEvent
    data object Completed : AgentEvent
    data class Failed(val failure: FailureInfo) : AgentEvent
}
sealed interface AuthStatus {
    data object LoggedIn : AuthStatus
    data object LoggedOut : AuthStatus
    data class Unknown(val detail: String) : AuthStatus
    data object NotApplicable : AuthStatus
}
class ProviderConfigException(message: String) : IllegalArgumentException(message)
internal fun ExecRequest.validate() {
    if (binaryPath.isBlank() || resumeSessionId?.isBlank() == true || model?.isBlank() == true) throw ProviderConfigException("Binary, model and explicit session ID must not be blank")
}
