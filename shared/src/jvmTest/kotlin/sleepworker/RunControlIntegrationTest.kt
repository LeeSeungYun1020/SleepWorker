package sleepworker

import sleepworker.engine.*
import sleepworker.model.*
import sleepworker.model.Target
import sleepworker.platform.*
import sleepworker.storage.*
import sleepworker.ui.run.RunViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import okio.Path.Companion.toPath
import kotlin.test.*

class RunControlIntegrationTest {
    private suspend fun fixture(script: String, completion: Completion? = null): Pair<RunViewModel, String> {
        val repo = Files.createTempDirectory("sleepworker-controls-").toFile().canonicalPath
        val platform = desktopPlatform().copy(settingsPath = "$repo/settings.json".toPath(), notifier = object : Notifier { override suspend fun notify(title: String, body: String) {} })
        assertEquals(0, captureProcess(platform.processes, ProcessSpec(listOf("git", "init", "-q"), repo)).exitCode)
        val w = Workflow("controls", repo, "main", "body", steps = listOf(Step("body", script, kind = StepKind.SHELL, workspace = Workspace.Local, completion = completion,
            transitions = listOf(Transition(Condition.Success, Target.End), Transition(Condition.Otherwise, Target.Ask)))))
        java.io.File(repo, "workflow.yaml").writeText(WorkflowCodec().encode(w))
        val vm = RunViewModel(platform)
        vm.openRepository(repo); withTimeout(10_000) { vm.repository.first { it != null } }
        vm.importYaml("$repo/workflow.yaml"); withTimeout(10_000) { vm.selected.first { it != null } }
        vm.runPreflight(); assertTrue(withTimeout(30_000) { vm.report.first { it != null } }!!.passed)
        return vm to repo
    }
    @Test fun manualRetryAndSkipPreserveAllAttempts() = runBlocking {
        val (vm, _) = fixture("echo FAILED; exit 7")
        try {
            vm.start(); withTimeout(10_000) { vm.state.first { it?.status == RunStatus.AWAITING_USER } }
            vm.answer(UserDecision.Retry)
            val twice = withTimeout(10_000) { vm.state.first { it?.status == RunStatus.AWAITING_USER && it.visits.size == 2 } }!!
            assertEquals(1, twice.visits.last().manualRetryOf)
            assertTrue(twice.visits.all { it.attempts.size == 2 && it.attempts.all { a -> a.result?.exitCode == 7 } })
            vm.answer(UserDecision.Skip)
            val end = withTimeout(10_000) { vm.state.first { it?.status == RunStatus.COMPLETED } }!!
            assertTrue(end.visits.all { it.result?.success == false })
        } finally { vm.close() }
    }
    @Test fun abortCompletionAndShutdownLeaveNoOwnedProcessAndRecover() = runBlocking {
        for (shutdown in listOf(false, true)) {
            val (vm, repo) = fixture("echo BODY_DONE", Completion.Command("echo \$\$ > check.pid; sleep 120"))
            vm.start()
            val pidFile = java.io.File(repo, "check.pid")
            withTimeout(10_000) { while (!pidFile.exists()) delay(20) }
            val pid = pidFile.readText().trim().toLong()
            if (shutdown) vm.close() else {
                vm.abort(); withTimeout(10_000) { vm.state.first { it?.status == RunStatus.ABORTED } }; vm.close()
            }
            assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
            val reopened = RunViewModel(desktopPlatform())
            try {
                reopened.openRepository(repo); withTimeout(10_000) { reopened.repository.first { it != null } }
                assertEquals(if (shutdown) RunStatus.INTERRUPTED else RunStatus.ABORTED, reopened.history.value.single().status)
                assertNull(reopened.state.value)
            } finally { reopened.close() }
        }
    }
}
