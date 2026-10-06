package aiflow

import aiflow.engine.*
import aiflow.model.*
import aiflow.model.Target
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.time.Instant
import okio.Path.Companion.toPath
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EngineRecoveryTest {
    @Test fun pauseAbortDoesNotCountUntraversedEdge() = runTest {
        val fake = FakeProcessExecutor(FakeResult(delayMs = 100))
        val h = EngineHarness(fake)
        val job = async { h.run(workflow(shell("a", Target.StepId("b")), shell("b"))) }
        runCurrent(); h.engine.pause(); advanceTimeBy(100); runCurrent()
        val paused = h.engine.state.value!!
        assertEquals(RunStatus.PAUSED, paused.status)
        assertIs<Decision.NextStep>(paused.visits.single().decision)
        assertNull(paused.visits.single().transitionTaken)
        assertTrue(paused.transitionCounts.isEmpty())
        h.engine.abort()
        assertTrue(job.await().transitionCounts.isEmpty())
    }
    @Test fun allIncompleteStatusesRecoverWithoutEstimatedTimesOrExits() = runTest {
        val h = EngineHarness(FakeProcessExecutor())
        val version = h.version(workflow(shell()))
        val time = Instant.parse("2026-10-05T01:00:00Z")
        val found = Instant.parse("2026-10-05T02:00:00Z")
        val statuses = RunStatus.entries.filter { it != RunStatus.IDLE }
        statuses.forEachIndexed { index, status ->
            h.recorder.save(RunState("20261005-010000-${index.toString(16).padStart(8, '0')}", version.workflowId, version.versionId, version.workflow, status,
                visits = listOf(StepVisit(1, "one", StepStatus.EXECUTING, time, attempts = listOf(AttemptRecord(1, StepStatus.EXECUTING, time)))), lastUpdatedAt = time))
        }
        val recovery = RunRecovery(h.recorder) { found }
        val before = h.recorder.list()
        val after = recovery.recover()
        before.forEach { old ->
            val state = after.first { it.runId == old.runId }
            if (old.status.terminal) assertEquals(old, state) else {
                assertEquals(RunStatus.INTERRUPTED, state.status)
                assertEquals(old.status, state.previousStatus)
                assertEquals(time, state.previousUpdatedAt)
                assertEquals(found, state.interruptedAt)
                assertNull(state.visits.single().endedAt)
                assertNull(state.visits.single().attempts.single().endedAt)
                assertNull(state.visits.single().attempts.single().result)
            }
        }
        assertEquals(after, recovery.recover())
        assertTrue((h.executor as FakeProcessExecutor).requests.isEmpty())
    }
    @Test fun newRunAfterRecoveryStartsWithNewIdentityAndEmptyRuntimeState() = runTest {
        val fake = FakeProcessExecutor(codex("fresh"))
        val h = EngineHarness(fake)
        val version = h.version(agents(agent("a", SessionMode.NEW)))
        val time = Instant.parse("2026-10-05T01:00:00Z")
        val old = RunState("20261005-010000-abcdef1234567890", version.workflowId, version.versionId, version.workflow, RunStatus.PAUSED,
            sessionIds = mapOf("s" to "old"), visitCounts = mapOf("a" to 9), transitionCounts = mapOf("a:0" to 9), lastUpdatedAt = time)
        h.recorder.save(old)
        RunRecovery(h.recorder).recover()
        val fresh = h.engine.start(version, AppSettings(codexPath = "/codex"))
        assertNotEquals(old.runId, fresh.runId)
        assertEquals(mapOf("a" to 1), fresh.visitCounts)
        assertEquals(mapOf("a:0" to 1), fresh.transitionCounts)
        assertEquals(mapOf("s" to "fresh"), fresh.sessionIds)
        assertFalse("resume" in fake.requests.single().command)
        assertEquals(2, h.recorder.list().size)
    }
    @Test fun completionFileAppearingDuringRetryUsesSameSession() = runTest {
        lateinit var h: EngineHarness
        val fake = FakeProcessExecutor(codex(), codex())
        val wrapped = object : aiflow.platform.ProcessExecutor {
            override fun start(spec: aiflow.platform.ProcessSpec): aiflow.platform.RunningProcess {
                if (fake.requests.size == 1) h.fs.write("/repo/done.txt".toPath()) { writeUtf8("done") }
                return fake.start(spec)
            }
        }
        h = EngineHarness(wrapped)
        val state = h.run(agents(agent("a", SessionMode.NEW).copy(completion = Completion.FileExists("done.txt"))))
        assertEquals(CheckResult.NotMet, state.visits.single().attempts.first().result!!.completionResult!!.result)
        assertEquals(CheckResult.Met, state.visits.single().attempts.last().result!!.completionResult!!.result)
        assertTrue("resume" in fake.requests.last().command)
    }
    @Test fun skipRunsOnlyNewlyReachedExternalConditions() = runTest {
        val fake = FakeProcessExecutor(FakeResult(exitCode = 1), FakeResult(exitCode = 1), FakeResult())
        val h = EngineHarness(fake)
        val step = shell().copy(transitions = listOf(Transition(Condition.Failure, Target.Ask), Transition(Condition.Command("new check"), Target.End)))
        val job = async { h.run(workflow(step)) }
        runCurrent()
        assertEquals(2, fake.requests.size)
        h.engine.decide(UserDecision.Skip)
        val state = job.await()
        assertEquals(RunStatus.COMPLETED, state.status)
        assertEquals(3, fake.requests.size)
        assertFalse(state.visits.single().result!!.success)
        assertEquals(1, state.visits.single().conditionEvaluations.size)
    }
    @Test fun editingAndRestoringDraftDuringRunCannotChangeSnapshot() = runTest {
        val fake = FakeProcessExecutor(FakeResult(delayMs = 100), FakeResult())
        val h = EngineHarness(fake)
        val version = h.version(workflow(shell("a", Target.StepId("b")), shell("b")))
        val job = async { h.engine.start(version) }
        runCurrent()
        val changed = WorkflowDraft(version.workflowId, workflow = version.workflow.copy(name = "changed", start = "b"))
        h.store.saveDraft(changed)
        h.store.saveVersion(changed, warningsAcknowledged = true)
        h.store.restoreToDraft(version.workflowId, version.versionId)
        val state = job.await()
        assertEquals(version.workflow, state.workflow)
        assertEquals(version.versionId, state.versionId)
        assertEquals(listOf("a", "b"), state.visits.map { it.stepId })
        assertEquals(state, h.recorder.list().single())
    }
}
