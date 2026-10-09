package aiflow.ui.run

import aiflow.engine.RunStatus
import kotlin.time.Instant

internal sealed interface ElapsedTime {
    data class Known(val seconds: Long) : ElapsedTime
    data object Unknown : ElapsedTime
}

internal fun visitElapsedTime(status: RunStatus, startedAt: Instant, endedAt: Instant?, now: Instant): ElapsedTime =
    when {
        endedAt != null -> elapsedBetween(startedAt, endedAt)
        status.terminal -> ElapsedTime.Unknown
        else -> elapsedBetween(startedAt, now)
    }

internal fun runElapsedTime(status: RunStatus, firstVisitStartedAt: Instant?, lastUpdatedAt: Instant, now: Instant): ElapsedTime =
    when {
        status == RunStatus.INTERRUPTED -> ElapsedTime.Unknown
        firstVisitStartedAt == null -> ElapsedTime.Known(0)
        else -> elapsedBetween(firstVisitStartedAt, if (status.terminal) lastUpdatedAt else now)
    }

internal fun ElapsedTime.displayText(): String = when (this) {
    is ElapsedTime.Known -> "${seconds}초"
    ElapsedTime.Unknown -> "소요 시간 미확인"
}

private fun elapsedBetween(startedAt: Instant, endedAt: Instant): ElapsedTime.Known =
    ElapsedTime.Known((endedAt - startedAt).inWholeSeconds.coerceAtLeast(0))
