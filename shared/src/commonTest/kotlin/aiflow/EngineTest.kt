package aiflow

import aiflow.engine.*
import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import kotlin.time.Instant
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

internal class EngineHarness(val executor: ProcessExecutor, val fs: FileSystem = FakeFileSystem(), val preflight: Preflight = Preflight.None) {
    val lease = object : RepositoryLease {
        override val writeMutex = Mutex()
        override val repoPath = "/repo".toPath()
        var held = true
        override fun requireHeld() { check(held) }
        override fun release() { held = false }
    }
    init { fs.createDirectories(lease.repoPath) }
    val store = WorkflowStore(fs, lease)
    val recorder = RunRecorder(fs, lease)
    val removed = mutableListOf<Path>()
    val temporary = object : TempFiles {
        override fun createScript(content: String): Path = "/repo/body.zsh".toPath().also { fs.write(it) { writeUtf8(content) } }
        override fun delete(path: Path) { removed.add(path); fs.delete(path) }
    }
    val engine = RunOrchestrator(store, recorder, executor, temporary, preflight = preflight, drainMs = 50, cleanupMs = 50)
    suspend fun version(workflow: Workflow): WorkflowVersion {
        val draft = store.importYaml(WorkflowCodec().encode(workflow))
        return store.saveVersion(draft, warningsAcknowledged = true)
    }
    suspend fun run(workflow: Workflow) = engine.start(version(workflow), AppSettings(codexPath = "/codex", agyPath = "/agy"))
    fun text(state: RunState, relative: String) = fs.read(lease.repoPath / ".aiflow/runs" / state.runId / relative) { readUtf8() }
}
internal fun codex(id: String = "thread-1", exitCode: Int = 0, delayMs: Long = 0) = FakeResult(listOf(
    """{"type":"thread.started","thread_id":"$id"}""",
    """{"type":"item.completed","item":{"type":"agent_message","text":"done"}}""",
    """{"type":"turn.completed","usage":{"input_tokens":10}}""",
), exitCode = exitCode, delayMs = delayMs)
internal fun agents(vararg steps: Step) = workflow(*steps).copy(sessions = mapOf("s" to SessionDef(Provider.CODEX, Workspace.Local)))

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {
    @Test fun explicitGraphIgnoresOrderAndLayout() = runTest {
        val original = workflow(shell("a", Target.StepId("b")), shell("b", Target.StepId("c")), shell("c"))
        val reordered = original.copy(steps = original.steps.reversed(), editor = EditorLayout(mapOf("c" to NodePosition(-90f, 30f))))
        for (w in listOf(original, reordered)) {
            val fake = FakeProcessExecutor(FakeResult(), FakeResult(), FakeResult())
            val h = EngineHarness(fake)
            val result = h.run(w)
            assertEquals(RunStatus.COMPLETED, result.status)
            assertEquals(listOf("a", "b", "c"), result.visits.map { it.stepId })
            assertEquals(3, result.transitionCounts.values.sum())
            assertEquals(result, h.recorder.list().single())
            assertTrue(h.text(result, "visits/1-a/attempts/1/result.json").contains("SUCCEEDED"))
        }
    }
    @Test fun firstMatchingTransitionWinsAndOrderIsMeaningful() = runTest {
        for (target in listOf("b", "c")) {
            val other = if (target == "b") "c" else "b"
            val a = shell("a").copy(transitions = listOf(Transition(Condition.Success, Target.StepId(target)), Transition(Condition.Success, Target.StepId(other))))
            val h = EngineHarness(FakeProcessExecutor(FakeResult(), FakeResult()))
            val result = h.run(workflow(a, shell("b"), shell("c")))
            assertEquals(listOf("a", target), result.visits.map { it.stepId })
        }
    }
    @Test fun newResumeAndUsagePersist() = runTest {
        val fake = FakeProcessExecutor(codex(), codex())
        val h = EngineHarness(fake)
        val result = h.run(agents(agent("new", SessionMode.NEW, Target.StepId("again")), agent("again", SessionMode.RESUME)))
        assertEquals(RunStatus.COMPLETED, result.status)
        assertTrue("resume" in fake.requests[1].command)
        assertTrue("thread-1" in fake.requests[1].command)
        assertEquals("thread-1", result.visits[1].boundSessionId)
        assertNotNull(result.visits[0].result!!.providerReport!!.usage)
        assertEquals("/codex", result.visits[0].attempts[0].metadata!!.binaryPath)
    }
    @Test fun completionFailureRetriesBoundSessionAndOriginalPromptIsPreserved() = runTest {
        val fake = FakeProcessExecutor(codex(), FakeResult(exitCode = 9), codex(), FakeResult())
        val step = agent("a", SessionMode.NEW).copy(completion = Completion.Command("check"), retryScript = "  retry only\n")
        val h = EngineHarness(fake)
        val result = h.run(agents(step))
        assertEquals(1, result.visits.size)
        assertEquals(2, result.visits.single().attempts.size)
        assertTrue(result.visits.single().result!!.success)
        assertTrue("resume" in fake.requests[2].command)
        assertEquals("  retry only\n", fake.requests[2].stdin)
        assertEquals(step.script, h.text(result, "visits/1-a/attempts/1/script.txt"))
        assertEquals(step.retryScript, h.text(result, "visits/1-a/attempts/2/script.txt"))
    }
    @Test fun exhaustedRetryFollowsFailureEdge() = runTest {
        val a = shell("a").copy(transitions = listOf(Transition(Condition.Failure, Target.StepId("fix")), Transition(Condition.Success, Target.End)))
        val result = EngineHarness(FakeProcessExecutor(FakeResult(exitCode = 4), FakeResult(exitCode = 4), FakeResult())).run(workflow(a, shell("fix")))
        assertEquals(listOf("a", "fix"), result.visits.map { it.stepId })
        assertFalse(result.visits[0].result!!.success)
    }
    @Test fun skipUsesExplicitSuccessEdgeAndPreservesFailure() = runTest {
        val fake = FakeProcessExecutor(FakeResult(exitCode = 4), FakeResult(exitCode = 4), FakeResult())
        val h = EngineHarness(fake)
        val a = shell("a").copy(transitions = listOf(Transition(Condition.Success, Target.StepId("review"))))
        val job = async { h.run(workflow(a, shell("review"))) }
        runCurrent()
        assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
        h.engine.decide(UserDecision.Skip)
        val state = job.await()
        assertEquals(RunStatus.COMPLETED, state.status)
        assertFalse(state.visits[0].result!!.success)
        assertEquals("Skip", state.visits[0].controls.single().action)
    }
    @Test fun externalChecksAreCachedAcrossSkipAndExplicitAskIsCountedOnce() = runTest {
        val fake = FakeProcessExecutor(FakeResult(), FakeResult())
        val h = EngineHarness(fake)
        val step = shell().copy(transitions = listOf(Transition(Condition.Command("approve"), Target.Ask), Transition(Condition.Otherwise, Target.End)))
        val job = async { h.run(workflow(step)) }
        runCurrent()
        repeat(2) { h.engine.decide(UserDecision.Skip); runCurrent() }
        assertEquals(2, fake.requests.size)
        assertEquals(1, h.engine.state.value!!.transitionCounts.values.sum())
        assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
        h.engine.abort()
        assertEquals(RunStatus.ABORTED, job.await().status)
    }
    @Test fun conditionErrorDoesNotFallThroughOrRerunOnSkip() = runTest {
        val fake = FakeProcessExecutor(FakeResult(), FakeResult(exitCode = 2))
        val h = EngineHarness(fake)
        val step = shell().copy(transitions = listOf(Transition(Condition.Command("test"), Target.End), Transition(Condition.Otherwise, Target.End)))
        val job = async { h.run(workflow(step)) }
        runCurrent()
        h.engine.decide(UserDecision.Skip); runCurrent()
        assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
        assertEquals(2, fake.requests.size)
        assertTrue(h.engine.state.value!!.transitionCounts.isEmpty())
        h.engine.abort(); job.await()
    }
    @Test fun pauseStopsOnlyAtNextStepAndResumeDoesNotRepeatChecks() = runTest {
        val fake = FakeProcessExecutor(FakeResult(delayMs = 100), FakeResult(), FakeResult())
        val h = EngineHarness(fake)
        val first = shell("a").copy(transitions = listOf(Transition(Condition.Command("check"), Target.StepId("b")), Transition(Condition.Otherwise, Target.End)))
        val job = async { h.run(workflow(first, shell("b"))) }
        runCurrent(); h.engine.pause(); advanceTimeBy(100); runCurrent()
        assertEquals(RunStatus.PAUSED, h.engine.state.value!!.status)
        assertEquals(2, fake.requests.size)
        h.engine.resume()
        assertEquals(RunStatus.COMPLETED, job.await().status)
        assertEquals(3, fake.requests.size)
    }
    @Test fun pauseIsConsumedByEndAndAsk() = runTest {
        for (target in listOf(Target.End, Target.Ask)) {
            val h = EngineHarness(FakeProcessExecutor(FakeResult(delayMs = 100)))
            val job = async { h.run(workflow(shell(next = target))) }
            runCurrent(); h.engine.pause(); advanceTimeBy(100); runCurrent()
            assertEquals(if (target == Target.End) RunStatus.COMPLETED else RunStatus.AWAITING_USER, h.engine.state.value!!.status)
            if (target == Target.Ask) h.engine.abort()
            job.await()
        }
    }
    @Test fun transitionAndTotalVisitCapsCannotBeSkipped() = runTest {
        for (cap in listOf(true, false)) {
            val fake = FakeProcessExecutor(FakeResult(), FakeResult())
            val h = EngineHarness(fake)
            val a = shell().copy(transitions = listOf(Transition(Condition.Otherwise, Target.StepId("one"), if (cap) 1 else null)))
            val job = async { h.run(workflow(a).copy(maxSteps = if (cap) 20 else 2)) }
            runCurrent(); h.engine.decide(UserDecision.Skip); runCurrent()
            assertEquals(2, fake.requests.size)
            assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
            if (!cap) {
                h.engine.decide(UserDecision.Retry); runCurrent()
                assertEquals(2, fake.requests.size)
                assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
            }
            h.engine.abort(); job.await()
        }
    }
    @Test fun manualRetryIsANewVisitUsesOriginalScriptAndNewMode() = runTest {
        val fake = FakeProcessExecutor(codex("old"), codex("fresh"))
        val h = EngineHarness(fake)
        val step = agent("a", SessionMode.NEW, Target.Ask)
        val job = async { h.run(agents(step)) }
        runCurrent(); h.engine.decide(UserDecision.Retry); runCurrent()
        val state = h.engine.state.value!!
        assertEquals(2, state.visits.size)
        assertEquals(1, state.visits[1].manualRetryOf)
        assertEquals(listOf(1, 1), state.visits.map { it.attempts.size })
        assertFalse("resume" in fake.requests[1].command)
        assertEquals(step.script, fake.requests[1].stdin)
        assertEquals("old", state.visits[0].boundSessionId)
        assertEquals("fresh", state.sessionIds["s"])
        h.engine.abort(); job.await()
    }
    @Test fun missingIdNeverStartsAutomaticNewAndOldNewIdIsCleared() = runTest {
        val fake = FakeProcessExecutor(codex("old"), FakeResult(exitCode = 1))
        val h = EngineHarness(fake)
        val w = agents(agent("a", SessionMode.NEW, Target.StepId("b")), agent("b", SessionMode.NEW))
        val state = h.run(w)
        assertEquals(2, fake.requests.size)
        assertNull(state.sessionIds["s"])
        assertEquals("session id unavailable", state.visits.last().attempts.single().retrySkippedReason)
    }
    @Test fun resetSessionOccursAtEntryAndManualRetryKeepsEffectiveNew() = runTest {
        val fake = FakeProcessExecutor(codex("old", delayMs = 100), codex("new"), codex("manual"))
        val h = EngineHarness(fake)
        val a = agent("a", SessionMode.NEW).copy(transitions = listOf(Transition(Condition.Otherwise, Target.StepId("b"), resetSession = true)))
        val job = async { h.run(agents(a, agent("b", SessionMode.RESUME, Target.Ask))) }
        runCurrent(); h.engine.pause(); advanceTimeBy(100); runCurrent()
        assertEquals("old", h.engine.state.value!!.sessionIds["s"])
        h.engine.resume(); runCurrent(); h.engine.decide(UserDecision.Retry); runCurrent()
        assertEquals(listOf(SessionMode.NEW, SessionMode.NEW, SessionMode.NEW), h.engine.state.value!!.visits.map { it.effectiveMode })
        assertTrue(fake.requests.all { "resume" !in it.command })
        h.engine.abort(); job.await()
    }
    @Test fun sessionRejectionClearsActiveBindingAndManualRetryCannotReviveIt() = runTest {
        val rejected = FakeResult(listOf("""{"type":"turn.failed","error":{"message":"thread not found"}}"""), exitCode = 1)
        val fake = FakeProcessExecutor(codex(), rejected)
        val h = EngineHarness(fake)
        val job = async { h.run(agents(agent("a", SessionMode.NEW, Target.StepId("b")), agent("b", SessionMode.RESUME, Target.Ask))) }
        runCurrent()
        assertTrue(h.engine.state.value!!.sessionIds.isEmpty())
        h.engine.decide(UserDecision.Retry); runCurrent()
        assertEquals(2, fake.requests.size)
        assertEquals(FailureKind.SESSION_MISSING, h.engine.state.value!!.visits.last().result!!.failure!!.kind)
        h.engine.abort(); job.await()
    }
    @Test fun timeoutRetriesShellOnceAndKeepsObservedExit() = runTest {
        val fake = FakeProcessExecutor(FakeResult(delayMs = 5_000), FakeResult())
        val h = EngineHarness(fake)
        val state = h.run(workflow(shell().copy(timeoutSec = 1)))
        assertEquals(2, fake.requests.size)
        assertEquals(1, fake.killed)
        assertEquals(Termination.TIMED_OUT, state.visits.single().attempts[0].result!!.termination)
        assertNull(state.visits.single().attempts[0].result!!.exitCode)
        assertTrue(state.visits.single().result!!.success)
    }
    @Test fun providerFailureAtZeroCannotBeOverwrittenByCompletion() = runTest {
        val fake = FakeProcessExecutor(FakeResult(listOf("""{"type":"turn.failed","error":{"message":"401 Unauthorized"}}""")))
        val state = EngineHarness(fake).run(agents(agent("a", SessionMode.NEW).copy(completion = Completion.Command("true"))))
        assertEquals(1, fake.requests.size)
        assertFalse(state.visits.single().result!!.success)
        assertEquals(FailureKind.AUTH, state.visits.single().result!!.failure!!.kind)
    }
    @Test fun abortAndShutdownCancelBodiesWithoutRetryOrNext() = runTest {
        for (shutdown in listOf(false, true)) {
            val fake = FakeProcessExecutor(FakeResult(delayMs = 20_000))
            val h = EngineHarness(fake)
            val job = async { h.run(workflow(shell("a", Target.StepId("b")), shell("b"))) }
            runCurrent()
            if (shutdown) h.engine.interruptForShutdown() else h.engine.abort()
            val state = job.await()
            assertEquals(if (shutdown) RunStatus.INTERRUPTED else RunStatus.ABORTED, state.status)
            assertEquals(1, fake.requests.size)
            assertTrue(fake.killed > 0)
            assertEquals(Termination.CANCELLED, state.visits.single().attempts.single().result!!.termination)
        }
    }
    @Test fun cleanupFailureIsRecordedAndBlocksAllFurtherCommands() = runTest {
        val fake = FakeProcessExecutor(FakeResult(delayMs = 5_000, cleanupFailure = ProcessCleanupException("permission denied")))
        val h = EngineHarness(fake)
        val state = h.run(workflow(shell().copy(timeoutSec = 1)))
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(1, fake.requests.size)
        assertTrue(state.visits.single().attempts.single().result!!.cleanupError!!.contains("permission denied"))
        assertTrue(h.text(state, "visits/1-one/attempts/1/result.json").contains("permission denied"))
    }
    @Test fun multilineScriptsAreDeletedAndSnapshotUsesStoredVersion() = runTest {
        val fake = FakeProcessExecutor(FakeResult())
        val h = EngineHarness(fake)
        val w = workflow(shell().copy(script = "!echo a\necho b"))
        val saved = h.version(w)
        val state = h.engine.start(saved.copy(workflow = w.copy(name = "tampered")))
        assertEquals(w, state.workflow)
        assertEquals(saved.versionId, state.versionId)
        assertEquals(listOf("/bin/zsh", "-l", "/repo/body.zsh"), fake.requests.single().command)
        assertEquals(1, h.removed.size)
    }
    @Test fun initialRecordingFailureStartsNoProcesses() = runTest {
        val backing = FakeFileSystem()
        val failing = object : ForwardingFileSystem(backing) {
            override fun atomicMove(source: Path, target: Path) {
                if (target.name == "run.json") throw IOException("disk full")
                super.atomicMove(source, target)
            }
        }
        val fake = FakeProcessExecutor()
        val h = EngineHarness(fake, failing)
        assertEquals(RunStatus.FAILED, h.run(workflow(shell())).status)
        assertTrue(fake.requests.isEmpty())
    }
    @Test fun recoveryIsIdempotentPreservesFinalEvidenceAndNeverExecutes() = runTest {
        val fake = FakeProcessExecutor(FakeResult())
        val h = EngineHarness(fake)
        val complete = h.run(workflow(shell()))
        val old = complete.copy(runId = "20261005-120000-abcdef1234567890", status = RunStatus.RUNNING,
            visits = complete.visits + StepVisit(2, "one", StepStatus.EXECUTING, complete.lastUpdatedAt, attempts = listOf(AttemptRecord(1, StepStatus.EXECUTING, complete.lastUpdatedAt))), currentVisit = 2)
        h.recorder.save(old)
        val discovery = Instant.parse("2026-10-05T15:00:00Z")
        val recovery = RunRecovery(h.recorder) { discovery }
        val first = recovery.recover()
        assertEquals(first, recovery.recover())
        assertEquals(complete, first.first { it.runId == complete.runId })
        val stopped = first.first { it.runId == old.runId }
        assertEquals(RunStatus.INTERRUPTED, stopped.status)
        assertEquals(RunStatus.RUNNING, stopped.previousStatus)
        assertEquals(discovery, stopped.interruptedAt)
        assertEquals(complete.visits.single(), stopped.visits.first())
        assertNull(stopped.visits.last().endedAt)
        assertNull(stopped.visits.last().attempts.single().result)
        assertEquals(1, fake.requests.size)
    }
}
