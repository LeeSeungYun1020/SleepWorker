package sleepworker.storage

import sleepworker.engine.*
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.serialization.encodeToString

class RunRecovery(private val recorder: RunRecorder, private val now: () -> Instant = { Clock.System.now() }) {
    suspend fun recover(): List<RunState> = recorder.list().map { state ->
        if (state.status.terminal || state.status == RunStatus.IDLE) state else {
            val recovered = interrupted(state, now(), "previous_app_session_ended; logs may be partial; external processes unverified")
            recovered.visits.forEach { visit -> visit.attempts.filter { it.result == null }.forEach { attempt ->
                recorder.write(state.runId, "${recorder.attemptPath(visit, attempt.attemptNo)}/result.json", recorder.json.encodeToString(attempt), immutable = true)
            } }
            recorder.save(recovered)
            recovered
        }
    }.sortedByDescending { it.lastUpdatedAt }
    companion object {
        fun interrupted(state: RunState, time: Instant, reason: String): RunState = state.copy(
            status = RunStatus.INTERRUPTED, previousStatus = state.status, previousUpdatedAt = state.lastUpdatedAt,
            interruptedAt = time, interruptionReason = reason, lastUpdatedAt = time,
            visits = state.visits.map { visit -> if (visit.result != null) visit else visit.copy(status = StepStatus.INTERRUPTED,
                attempts = visit.attempts.map { if (it.result != null) it else it.copy(status = StepStatus.INTERRUPTED) }) },
        )
    }
}
