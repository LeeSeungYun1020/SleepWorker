package aiflow

import aiflow.engine.*
import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.*
import aiflow.storage.*
import aiflow.ui.run.RunViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class RunViewModelIntegrationTest {
    @Test fun savedVersionPreflightExecutionAndHistoryThroughViewModel() = runBlocking {
        val directory = Files.createTempDirectory("aiflow-phase3-").toFile()
        val repo = directory.canonicalPath
        val platform = desktopPlatform().copy(settingsPath = (directory.toPath().toString() + "/settings.json").toPath(), notifier = object : Notifier {
            override suspend fun notify(title: String, body: String) {}
        })
        suspend fun git(vararg args: String) {
            assertEquals(0, captureProcess(platform.processes, ProcessSpec(listOf("git") + args, repo)).exitCode)
        }
        git("init", "-b", "main")
        val workflow = Workflow("Phase 3 local smoke", repo, "main", "sync", steps = listOf(
            Step("sync", "!printf 'sync ready\\n'", workspace = Workspace.Local, transitions = listOf(Transition(Condition.Success, Target.StepId("verify")))),
            Step("verify", "!printf 'local verification\\n'", workspace = Workspace.Local, completion = Completion.Command("test -d .git"), transitions = listOf(Transition(Condition.Success, Target.StepId("log")))),
            Step("log", "!printf 'complete\\n'", workspace = Workspace.Local, transitions = listOf(Transition(Condition.Success, Target.End)))
        ))
        val yaml = directory.resolve("smoke.yaml")
        yaml.writeText(WorkflowCodec().encode(workflow))
        val vm = RunViewModel(platform)
        try {
            vm.openRepository(repo)
            withTimeout(15_000) { vm.repository.first { it != null } }
            vm.importYaml(yaml.absolutePath)
            withTimeout(15_000) { vm.pendingImport.first { it != null } }
            assertEquals(3, vm.importWarnings.value.size)
            vm.confirmImport()
            withTimeout(15_000) { while (vm.selected.value == null) { vm.error.value?.let { fail(it) }; delay(20) } }
            vm.runPreflight()
            val preflight = withTimeout(30_000) { vm.report.first { it != null } }!!
            assertTrue(preflight.passed, preflight.items.toString())
            vm.start()
            val done = withTimeout(30_000) { vm.state.first { it?.status?.terminal == true } }!!
            assertEquals(RunStatus.COMPLETED, done.status, done.failure)
            assertEquals(listOf("sync", "verify", "log"), done.visits.map { it.stepId })
            withTimeout(10_000) { vm.history.first { it.any { entry -> entry.runId == done.runId } } }
            assertTrue(vm.logs.value.any { it.text == "complete" })
            assertTrue(FileSystem.SYSTEM.exists("$repo/.aiflow/runs/${done.runId}/run.json".toPath()))
            vm.showHistory(done)
            withTimeout(10_000) { vm.logs.first { it.any { line -> line.text == "complete" } } }
            println("PHASE3_LOCAL_EVIDENCE=$repo/.aiflow/runs/${done.runId}")
        } finally { vm.close() }
        // Reopening reacquires ownership and discovers history without starting a process.
        val reopened = RunViewModel(platform)
        try {
            reopened.openRepository(repo)
            withTimeout(15_000) { reopened.repository.first { it != null } }
            assertEquals(RunStatus.COMPLETED, reopened.history.value.single().status)
            assertNull(reopened.state.value)
        } finally { reopened.close() }
    }
}
