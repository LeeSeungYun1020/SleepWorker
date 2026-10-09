package aiflow

import aiflow.engine.*
import aiflow.ui.run.DEFAULT_MAX_ATTEMPTS
import aiflow.ui.run.visitAttemptLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class RunAttemptLabelTest {
    private val started = Instant.parse("2026-10-05T01:00:00Z")
    private val ended = Instant.parse("2026-10-05T01:00:10Z")

    private fun visit(
        status: StepStatus = StepStatus.PREPARING,
        attempts: List<AttemptRecord> = emptyList(),
        manualRetryOf: Int? = null,
        visitNo: Int = 1,
    ): StepVisit = StepVisit(
        visitNo = visitNo,
        stepId = "step1",
        status = status,
        startedAt = started,
        endedAt = if (status in setOf(StepStatus.SUCCEEDED, StepStatus.FAILED, StepStatus.INTERRUPTED)) ended else null,
        manualRetryOf = manualRetryOf,
        attempts = attempts,
    )

    private fun attempt(
        attemptNo: Int,
        status: StepStatus = StepStatus.EXECUTING,
        endedAt: Instant? = null,
        retrySkippedReason: String? = null,
    ): AttemptRecord = AttemptRecord(
        attemptNo = attemptNo,
        status = status,
        startedAt = started,
        endedAt = endedAt,
        retrySkippedReason = retrySkippedReason,
    )

    @Test
    fun noAttemptsDisplaysZeroOutOfTwo() {
        val pending = visit(status = StepStatus.PENDING, attempts = emptyList())
        val preparing = visit(status = StepStatus.PREPARING, attempts = emptyList())
        assertEquals("시도 0/2", visitAttemptLabel(pending))
        assertEquals("시도 0/2", visitAttemptLabel(preparing))
    }

    @Test
    fun singleAttemptDisplaysOneOutOfTwoNotOneOutOfOne() {
        val activeAttempt = attempt(1, StepStatus.EXECUTING)
        val activeVisit = visit(status = StepStatus.EXECUTING, attempts = listOf(activeAttempt))
        assertEquals("시도 1/2", visitAttemptLabel(activeVisit))

        val succeededAttempt = attempt(1, StepStatus.SUCCEEDED, endedAt = ended)
        val succeededVisit = visit(status = StepStatus.SUCCEEDED, attempts = listOf(succeededAttempt))
        assertEquals("시도 1/2", visitAttemptLabel(succeededVisit))

        val skippedAttempt = attempt(1, StepStatus.FAILED, endedAt = ended, retrySkippedReason = "cancelled")
        val failedVisit = visit(status = StepStatus.FAILED, attempts = listOf(skippedAttempt))
        assertEquals("시도 1/2", visitAttemptLabel(failedVisit))
    }

    @Test
    fun activeRetryDisplaysTwoOutOfTwoWithoutClaimingCompletion() {
        val firstFailed = attempt(1, StepStatus.FAILED, endedAt = ended)

        // Lifecycle stage 1: Orchestrator transitions visit to RETRYING before attempt 2 record is appended
        val retryingVisit = visit(status = StepStatus.RETRYING, attempts = listOf(firstFailed))
        assertEquals("시도 2/2", visitAttemptLabel(retryingVisit))

        // Lifecycle stage 2: Second attempt record appended and actively executing
        val secondActive = attempt(2, StepStatus.EXECUTING)
        val activeRetryVisit = visit(status = StepStatus.EXECUTING, attempts = listOf(firstFailed, secondActive))
        assertEquals("시도 2/2", visitAttemptLabel(activeRetryVisit))

        // Label remains "시도 2/2" while execution is still running and does not claim completion
        assertEquals(null, activeRetryVisit.endedAt)
        assertEquals(null, secondActive.endedAt)
        assertEquals("시도 2/2", visitAttemptLabel(activeRetryVisit))
    }

    @Test
    fun completedRetryDisplaysTwoOutOfTwo() {
        val firstFailed = attempt(1, StepStatus.FAILED, endedAt = ended)
        val secondSucceeded = attempt(2, StepStatus.SUCCEEDED, endedAt = ended)
        val succeededRetry = visit(status = StepStatus.SUCCEEDED, attempts = listOf(firstFailed, secondSucceeded))
        assertEquals("시도 2/2", visitAttemptLabel(succeededRetry))

        val secondFailed = attempt(2, StepStatus.FAILED, endedAt = ended)
        val failedRetry = visit(status = StepStatus.FAILED, attempts = listOf(firstFailed, secondFailed))
        assertEquals("시도 2/2", visitAttemptLabel(failedRetry))
    }

    @Test
    fun manualRetryIsSeparateVisitAndDoesNotCountAsPredecessorAttempt() {
        val visit1Attempt1 = attempt(1, StepStatus.FAILED, endedAt = ended)
        val visit1Attempt2 = attempt(2, StepStatus.FAILED, endedAt = ended)
        val visit1 = visit(visitNo = 1, status = StepStatus.FAILED, attempts = listOf(visit1Attempt1, visit1Attempt2))
        assertEquals("시도 2/2", visitAttemptLabel(visit1))

        // User triggers manual retry -> new visit with manualRetryOf = 1
        val visit2Initial = visit(visitNo = 2, status = StepStatus.PREPARING, manualRetryOf = 1, attempts = emptyList())
        assertEquals("시도 0/2", visitAttemptLabel(visit2Initial))

        val visit2Attempt1 = attempt(1, StepStatus.EXECUTING)
        val visit2Active = visit(visitNo = 2, status = StepStatus.EXECUTING, manualRetryOf = 1, attempts = listOf(visit2Attempt1))
        assertEquals("시도 1/2", visitAttemptLabel(visit2Active))
        assertEquals(1, visit2Active.manualRetryOf)
    }
}
