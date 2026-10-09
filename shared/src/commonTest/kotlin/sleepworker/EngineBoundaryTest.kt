package sleepworker

import sleepworker.engine.*
import sleepworker.git.WorktreeManager
import sleepworker.model.*
import sleepworker.model.Target
import sleepworker.platform.*
import sleepworker.provider.*
import sleepworker.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EngineBoundaryTest {
    private fun io(h: EngineHarness) = ExecutionIO(CommandRunner(ManagedProcessRunner(h.executor), 50, 50), h.recorder, "20261005-110000-abcdef1234567890", MutableSharedFlow(extraBufferCapacity = 100))
    @Test fun delayedFinalOutputIsDrainedBeforeProviderFinalization() = runTest {
        val executor = object : ProcessExecutor {
            override fun start(spec: ProcessSpec) = object : RunningProcess {
                override val stdout = flow { delay(25); codex().stdout.forEach { emit(it) } }
                override val stderr = flowOf("warning only")
                override suspend fun awaitExit() = 0
                override suspend fun killTreeAndWait() = Unit
            }
        }
        val state = EngineHarness(executor).run(agents(agent("a", SessionMode.NEW)))
        assertTrue(state.visits.single().result!!.success)
        assertEquals("thread-1", state.sessionIds["s"])
    }
    @Test fun unclosedPipeAndIoFailureNeverBecomeSuccess() = runTest {
        for (broken in listOf(false, true)) {
            var starts = 0
            var kills = 0
            val executor = object : ProcessExecutor {
                override fun start(spec: ProcessSpec): RunningProcess {
                    starts++
                    return object : RunningProcess {
                        override val stdout = flow<String> { if (broken) throw IOException("read failed") else awaitCancellation() }
                        override val stderr = emptyFlow<String>()
                        override suspend fun awaitExit() = 0
                        override suspend fun killTreeAndWait() { kills++ }
                    }
                }
            }
            val state = EngineHarness(executor).run(workflow(shell()))
            assertFalse(state.visits.single().result!!.success)
            assertEquals(Termination.OUTPUT_INCOMPLETE, state.visits.single().result!!.termination)
            assertEquals(2, starts)
            assertEquals(2, kills)
        }
    }
    @Test fun cancellationAndTimeoutKeepAgyExitOneAndFinalOutput() = runTest {
        for (cancelled in listOf(false, true)) {
            val killed = CompletableDeferred<Unit>()
            val executor = object : ProcessExecutor {
                override fun start(spec: ProcessSpec) = object : RunningProcess {
                    override val stdout = flow { killed.await(); emit("""{"status":"ERROR","conversation_id":"agy-id","response":"context canceled"}""") }
                    override val stderr = emptyFlow<String>()
                    override suspend fun awaitExit(): Int { killed.await(); return 1 }
                    override suspend fun killTreeAndWait() { killed.complete(Unit) }
                }
            }
            val h = EngineHarness(executor)
            val step = agent("a", SessionMode.NEW).copy(timeoutSec = 1)
            val job = async { h.run(agents(step).copy(sessions = mapOf("s" to SessionDef(Provider.ANTIGRAVITY, Workspace.Local)))) }
            runCurrent()
            if (cancelled) h.engine.abort()
            val state = job.await()
            val first = state.visits.single().attempts.first().result!!
            assertEquals(1, first.exitCode)
            assertEquals(if (cancelled) Termination.CANCELLED else Termination.TIMED_OUT, first.termination)
            assertEquals("agy-id", first.sessionId)
            assertEquals(if (cancelled) 1 else 2, state.visits.single().attempts.size)
        }
    }
    @Test fun completionTimeoutRetriesButTransitionTimeoutStopsAtAsk() = runTest {
        for (transition in listOf(false, true)) {
            val fake = FakeProcessExecutor(FakeResult(), FakeResult(delayMs = 5_000), FakeResult(), FakeResult())
            val h = EngineHarness(fake)
            val step = if (transition) shell().copy(checkTimeoutSec = 1, transitions = listOf(Transition(Condition.Command("check"), Target.End), Transition(Condition.Otherwise, Target.End)))
                else shell().copy(checkTimeoutSec = 1, completion = Completion.Command("check"))
            val job = async { h.run(workflow(step)) }
            advanceUntilIdle()
            if (transition) {
                assertEquals(RunStatus.AWAITING_USER, h.engine.state.value!!.status)
                assertEquals(2, fake.requests.size)
                assertIs<CheckResult.Error>(h.engine.state.value!!.visits.single().conditionEvaluations[0]!!.result)
                h.engine.abort()
            } else {
                assertEquals(4, fake.requests.size)
                assertIs<CheckResult.Error>(h.engine.state.value!!.visits.single().attempts.first().result!!.completionResult!!.result)
            }
            job.await()
        }
    }
    @Test fun abortDuringChecksPreparationAndPreflightStopsAllFurtherWork() = runTest {
        for (phase in listOf("completion", "transition", "preparing", "preflight")) {
            val fake = if (phase in listOf("completion", "transition")) FakeProcessExecutor(FakeResult(), FakeResult(delayMs = 20_000)) else FakeProcessExecutor(FakeResult(delayMs = 20_000))
            val preflight = if (phase == "preflight") Preflight { _, _, io -> io.command("preflight/1", ProcessSpec(listOf("probe"), "/repo"), 30_000) } else Preflight.None
            val h = EngineHarness(fake, preflight = preflight)
            var step = shell()
            if (phase == "completion") step = step.copy(completion = Completion.Command("wait"))
            if (phase == "transition") step = step.copy(transitions = listOf(Transition(Condition.Command("wait"), Target.End), Transition(Condition.Otherwise, Target.End)))
            if (phase == "preparing") step = step.copy(workspace = Workspace.Worktree("impl"))
            val w = workflow(step).copy(worktrees = listOf(WorktreeDef("impl", "ai/impl")))
            val job = async { h.run(w) }
            runCurrent(); h.engine.abort()
            assertEquals(RunStatus.ABORTED, job.await().status, phase)
            assertEquals(if (phase in listOf("completion", "transition")) 2 else 1, fake.requests.size)
            assertTrue(fake.killed >= 1)
        }
    }
    @Test fun worktreeCreateUsesExistingBranchOrCreatesNewBranch() = runTest {
        for (exists in listOf(false, true)) {
            val fake = FakeProcessExecutor(FakeResult(listOf("worktree /repo", "branch refs/heads/main", "")), FakeResult(), FakeResult(exitCode = if (exists) 0 else 1), FakeResult())
            val h = EngineHarness(fake)
            val manager = WorktreeManager(h.fs, io(h))
            val definition = WorktreeDef("impl", "ai/impl")
            val w = workflow(shell()).copy(worktrees = listOf(definition))
            manager.ensure(w, definition, "preparing")
            assertEquals(!exists, "-b" in fake.requests.last().command)
            assertTrue("/repo.worktrees/impl" in fake.requests.last().command)
            assertTrue(fake.requests.all { it.command.first() == "git" })
        }
    }
    @Test fun existingWorktreeRequiresExactBranchAndForeignPathIsRejected() = runTest {
        for (branch in listOf("ai/impl", "wrong", "absent")) {
            val listing = if (branch == "absent") emptyList() else listOf("worktree /repo.worktrees/impl", "branch refs/heads/$branch", "")
            val fake = FakeProcessExecutor(FakeResult(listing))
            val h = EngineHarness(fake)
            h.fs.createDirectories("/repo.worktrees/impl".toPath())
            val manager = WorktreeManager(h.fs, io(h))
            val definition = WorktreeDef("impl", "ai/impl")
            val w = workflow(shell()).copy(worktrees = listOf(definition))
            if (branch == "ai/impl") manager.ensure(w, definition, "preparing")
            else assertFails { manager.ensure(w, definition, "preparing") }
            assertEquals(1, fake.requests.size)
        }
    }
    @Test fun preparationFailureDoesNotExecuteOrRetryBody() = runTest {
        val fake = FakeProcessExecutor(FakeResult(exitCode = 128))
        val h = EngineHarness(fake)
        val w = workflow(shell().copy(workspace = Workspace.Worktree("impl"))).copy(worktrees = listOf(WorktreeDef("impl", "ai/impl")))
        val state = h.run(w)
        assertEquals(1, fake.requests.size)
        val result = state.visits.single().result!!
        assertFalse(result.bodyStarted)
        assertEquals(FailureKind.PREPARATION, result.failure!!.kind)
        assertEquals(1, state.visits.single().attempts.size)
    }
    @Test fun fileChecksSeparateAbsentMismatchAndReadErrorAndSupportSimpleGlob() = runTest {
        val h = EngineHarness(FakeProcessExecutor())
        h.fs.createDirectories("/repo/out".toPath())
        h.fs.write("/repo/out/report.txt".toPath()) { writeUtf8("CHANGES_REQUESTED\nAPPROVED in body") }
        val checks = CheckRunner(h.fs, io(h))
        assertEquals(CheckResult.Met, checks.completion(Completion.FileExists("out/*.txt"), "/repo", 1, "c/1").result)
        assertEquals(CheckResult.NotMet, checks.completion(Completion.FileExists("absent"), "/repo", 1, "c/2").result)
        assertEquals(CheckResult.NotMet, checks.condition(Condition.FileContains("out/report.txt", "MISSING"), "/repo", 1, "c/3").result)
        assertEquals(CheckResult.Met, checks.condition(Condition.FileContains("out/report.txt", "APPROVED"), "/repo", 1, "c/4").result)
        assertIs<CheckResult.Error>(checks.condition(Condition.FileContains("out", "x"), "/repo", 1, "c/5").result)
        assertIs<CheckResult.Error>(checks.completion(Completion.FileExists("out/**/*.txt"), "/repo", 1, "c/6").result)
    }
    @Test fun missingSessionAfterFailedNewDoesNotInvokeResumeOrNew() = runTest {
        val fake = FakeProcessExecutor(FakeResult(exitCode = 1))
        val state = EngineHarness(fake).run(agents(agent("a", SessionMode.NEW, Target.StepId("b")), agent("b", SessionMode.RESUME)))
        assertEquals(1, fake.requests.size)
        assertEquals(FailureKind.SESSION_MISSING, state.visits.last().result!!.failure!!.kind)
    }
    @Test fun resumeCannotMoveASessionToAnotherWorkspace() = runTest {
        val fake = FakeProcessExecutor(codex())
        val w = agents(agent("a", SessionMode.NEW, Target.StepId("b")).copy(workspace = Workspace.Local), agent("b", SessionMode.RESUME)).copy(
            worktrees = listOf(WorktreeDef("impl", "ai/impl")), sessions = mapOf("s" to SessionDef(Provider.CODEX, Workspace.Worktree("impl"))))
        val state = EngineHarness(fake).run(w)
        assertEquals(1, fake.requests.size)
        assertEquals(FailureKind.CONFIG, state.visits.last().result!!.failure!!.kind)
    }
    @Test fun manualResumeRetryUsesBoundIdAndNewAttemptNumber() = runTest {
        val fake = FakeProcessExecutor(codex(), codex(), codex())
        val h = EngineHarness(fake)
        val job = async { h.run(agents(agent("a", SessionMode.NEW, Target.StepId("b")), agent("b", SessionMode.RESUME, Target.Ask))) }
        runCurrent(); h.engine.decide(UserDecision.Retry); runCurrent()
        assertEquals(3, fake.requests.size)
        assertEquals("thread-1", h.engine.state.value!!.visits.last().boundSessionId)
        assertEquals(2, h.engine.state.value!!.visits.last().manualRetryOf)
        assertTrue("resume" in fake.requests.last().command)
        assertEquals(1, h.engine.state.value!!.visits.last().attempts.single().attemptNo)
        h.engine.abort(); job.await()
    }
    @Test fun missingTransitionsAreAskInEvaluatorAndBlockedAtVersionSave() = runTest {
        val h = EngineHarness(FakeProcessExecutor())
        val step = shell().copy(transitions = emptyList())
        assertFails { h.version(workflow(step)) }
        val time = kotlin.time.Clock.System.now()
        val result = TransitionEvaluator(CheckRunner(h.fs, io(h))).evaluate(step, true, "/repo", StepVisit(1, "one", StepStatus.SUCCEEDED, time), emptyMap(), "checks")
        assertEquals(Decision.Ask("no transition matched"), result.decision)
    }
    @Test fun logWriteFailureCleansUpAndDoesNotStartRetry() = runTest {
        val backing = FakeFileSystem()
        val failing = object : ForwardingFileSystem(backing) {
            override fun appendingSink(file: Path, mustExist: Boolean): Sink {
                if (file.name == "stdout.log") throw IOException("disk full during log append")
                return super.appendingSink(file, mustExist)
            }
        }
        val fake = FakeProcessExecutor(FakeResult(listOf("evidence"), delayMs = 10_000))
        val h = EngineHarness(fake, failing)
        val state = h.run(workflow(shell()))
        assertEquals(RunStatus.FAILED, state.status)
        assertEquals(1, fake.requests.size)
        assertTrue(fake.killed >= 1)
        assertTrue(state.failure!!.contains("disk full"))
    }
    @Test fun terminalStateAndAttemptResultCannotBeOverwritten() = runTest {
        val h = EngineHarness(FakeProcessExecutor(FakeResult()))
        val state = h.run(workflow(shell()))
        assertFails { h.recorder.save(state.copy(status = RunStatus.RUNNING)) }
        assertFails { h.recorder.write(state.runId, "visits/1-one/attempts/1/result.json", "changed", immutable = true) }
        h.engine.abort(); h.engine.pause(); h.engine.resume(); h.engine.decide(UserDecision.Skip)
        assertEquals(state, h.engine.state.value)
    }
    @Test fun storageSymlinksAndReleasedOwnershipAreRejected() = runTest {
        val fs = FakeFileSystem().apply { allowSymlinks = true }
        val h = EngineHarness(FakeProcessExecutor(), fs)
        fs.createDirectories("/elsewhere".toPath())
        fs.createDirectories("/repo/.aiflow".toPath())
        fs.createSymlink("/repo/.aiflow/runs".toPath(), "/elsewhere".toPath())
        assertFails { h.recorder.list() }
        fs.delete("/repo/.aiflow/runs".toPath())
        h.lease.release()
        assertFails { RunRecovery(h.recorder).recover() }
    }
    @Test fun nonCancellableCleanupAndWaitCannotHangTheRun() = runTest {
        val release = CompletableDeferred<Unit>()
        var starts = 0
        val executor = object : ProcessExecutor {
            override fun start(spec: ProcessSpec): RunningProcess {
                starts++
                return object : RunningProcess {
                    override val stdout = emptyFlow<String>()
                    override val stderr = emptyFlow<String>()
                    override suspend fun awaitExit(): Int = withContext(NonCancellable) { release.await(); 1 }
                    override suspend fun killTreeAndWait() = withContext(NonCancellable) { release.await() }
                }
            }
        }
        try {
            val h = EngineHarness(executor)
            val state = h.run(workflow(shell().copy(timeoutSec = 1)))
            assertEquals(RunStatus.FAILED, state.status)
            assertEquals(1, starts)
            assertTrue(currentTime < 5_000)
            assertNull(state.visits.single().attempts.single().result!!.exitCode)
            assertTrue(state.visits.single().attempts.single().result!!.cleanupError!!.contains("timed out"))
        } finally { release.complete(Unit); runCurrent() }
    }

}
