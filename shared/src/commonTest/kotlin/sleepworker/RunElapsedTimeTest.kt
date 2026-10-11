package sleepworker

import sleepworker.engine.RunStatus
import sleepworker.ui.run.ElapsedTime
import sleepworker.ui.run.displayText
import sleepworker.ui.run.runElapsedTime
import sleepworker.ui.run.visitElapsedTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RunElapsedTimeTest {
    private val started = Instant.parse("2026-10-05T01:00:00Z")
    private val before = Instant.parse("2026-10-05T00:59:59Z")
    private val ended = Instant.parse("2026-10-05T01:00:12Z")
    private val later = Instant.parse("2026-10-05T01:00:30Z")
    private val discovered = Instant.parse("2026-10-05T04:00:00Z")
    private val muchLater = Instant.parse("2026-10-05T05:00:00Z")

    @Test fun liveVisitsAdvanceButRecordedEndsStayFixed() {
        assertEquals(ElapsedTime.Known(12), visitElapsedTime(RunStatus.RUNNING, started, null, ended))
        assertEquals(ElapsedTime.Known(30), visitElapsedTime(RunStatus.RUNNING, started, null, later))
        assertEquals(ElapsedTime.Known(12), visitElapsedTime(RunStatus.RUNNING, started, ended, later))
        assertEquals(ElapsedTime.Known(12), visitElapsedTime(RunStatus.COMPLETED, started, ended, discovered))
    }

    @Test fun terminalVisitsWithoutRecordedEndsAreUnknown() {
        listOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.ABORTED, RunStatus.INTERRUPTED).forEach { status ->
            assertEquals(ElapsedTime.Unknown, visitElapsedTime(status, started, null, ended), "$status at first clock time")
            assertEquals(ElapsedTime.Unknown, visitElapsedTime(status, started, null, discovered), "$status at later clock time")
        }
        assertEquals("소요 시간 미확인", ElapsedTime.Unknown.displayText())
        assertEquals("12초", ElapsedTime.Known(12).displayText())
    }

    @Test fun interruptedRunNeverUsesRecoveryDiscoveryAsExecutionEnd() {
        assertEquals(ElapsedTime.Unknown, runElapsedTime(RunStatus.INTERRUPTED, started, discovered, discovered))
        assertEquals(ElapsedTime.Unknown, runElapsedTime(RunStatus.INTERRUPTED, started, discovered, muchLater))
        assertEquals(ElapsedTime.Unknown, runElapsedTime(RunStatus.INTERRUPTED, null, discovered, muchLater))
        assertEquals(ElapsedTime.Known(12), visitElapsedTime(RunStatus.INTERRUPTED, started, ended, discovered))
        assertEquals(ElapsedTime.Unknown, visitElapsedTime(RunStatus.INTERRUPTED, started, null, discovered))
    }

    @Test fun otherRunTotalsKeepExistingClockFallbackAndClamping() {
        assertEquals(ElapsedTime.Known(12), runElapsedTime(RunStatus.RUNNING, started, discovered, ended))
        assertEquals(ElapsedTime.Known(30), runElapsedTime(RunStatus.RUNNING, started, discovered, later))
        listOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.ABORTED).forEach { status ->
            assertEquals(ElapsedTime.Known(12), runElapsedTime(status, started, ended, discovered), status.toString())
        }
        assertEquals(ElapsedTime.Known(0), runElapsedTime(RunStatus.RUNNING, null, ended, later))
        assertEquals(ElapsedTime.Known(0), runElapsedTime(RunStatus.COMPLETED, null, ended, later))
        assertEquals(ElapsedTime.Known(0), runElapsedTime(RunStatus.RUNNING, started, ended, before))
        assertEquals(ElapsedTime.Known(0), runElapsedTime(RunStatus.COMPLETED, started, before, later))
        assertEquals(ElapsedTime.Known(0), visitElapsedTime(RunStatus.COMPLETED, started, before, later))
    }
}
