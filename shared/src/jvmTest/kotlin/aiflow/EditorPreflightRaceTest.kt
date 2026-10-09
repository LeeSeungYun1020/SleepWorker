package aiflow

import aiflow.engine.RunStatus
import aiflow.model.*
import aiflow.platform.*
import aiflow.ui.editor.*
import aiflow.ui.run.RunViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okio.Path.Companion.toPath
import kotlin.test.*

/** The blocked command is a read-only Git probe, never a step body or a model call. */
class EditorPreflightRaceTest {
    private class ProbeBarrier(private val delegate: ProcessExecutor, private val occurrence: Int) : ProcessExecutor {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = CopyOnWriteArrayList<ProcessSpec>()
        private val probes = AtomicInteger()
        override fun start(spec: ProcessSpec): RunningProcess {
            requests += spec
            val process = delegate.start(spec)
            val block = spec.command == listOf("git", "rev-parse", "--is-inside-work-tree") && probes.incrementAndGet() == occurrence
            return if (!block) process else object : RunningProcess by process {
                override suspend fun awaitExit(): Int {
                    reached.complete(Unit)
                    release.await()
                    return process.awaitExit()
                }
            }
        }
        fun bodies() = requests.filter { it.command.any { arg -> "printf" in arg } }
    }
    private suspend fun fixture(occurrence: Int, block: suspend (RunViewModel, EditorViewModel, WorkflowVersion, ProbeBarrier) -> Unit) {
        val directory = Files.createTempDirectory("aiflow-editor-race-").toFile()
        val repo = directory.canonicalPath
        val original = desktopPlatform().copy(settingsPath = directory.resolve("settings.json").absolutePath.toPath(), notifier = object : Notifier { override suspend fun notify(title: String, body: String) {} })
        assertEquals(0, captureProcess(original.processes, ProcessSpec(listOf("git", "init", "-b", "main"), repo)).exitCode)
        val barrier = ProbeBarrier(original.processes, occurrence)
        val vm = RunViewModel(original.copy(processes = barrier))
        try {
            vm.openRepository(repo)
            val editor = withTimeout(10_000) { vm.editor.first { it != null } }!!
            editor.newWorkflow(WorkflowTemplate.LINEAR)
            val version = (editor.save() as SaveOutcome.Saved).version
            block(vm, editor, version, barrier)
        } finally {
            barrier.release.complete(Unit)
            vm.close()
        }
    }
    private suspend fun rejectedBeforeRun(vm: RunViewModel, barrier: ProbeBarrier) {
        withTimeout(15_000) { vm.error.first { it != null } }
        withTimeout(10_000) { vm.busy.first { !it } }
        assertTrue(vm.error.value!!.contains("편집 내용과 저장 버전"), vm.error.value)
        assertNull(vm.report.value)
        assertNull(vm.state.value)
        assertTrue(vm.history.value.isEmpty())
        assertTrue(barrier.bodies().isEmpty())
    }
    @Test fun dirtyInvalidEditDuringPreviewCannotStartSavedVersion() = runBlocking {
        fixture(1) { vm, editor, version, barrier ->
            vm.runEditorVersion(version)
            withTimeout(10_000) { barrier.reached.await() }
            editor.setScript("first", "!")
            assertTrue(editor.dirty.value)
            assertTrue(editor.validateNow().any { it.severity == Severity.ERROR })
            barrier.release.complete(Unit)
            rejectedBeforeRun(vm, barrier)
            // A separate explicit request to run the selected saved version remains supported.
            vm.runPreflight()
            withTimeout(15_000) { vm.report.first { it?.passed == true } }
            vm.start()
            val done = withTimeout(15_000) { vm.state.first { it?.status?.terminal == true } }!!
            assertEquals(RunStatus.COMPLETED, done.status)
            assertEquals(version.workflow, done.workflow)
        }
    }
    @Test fun editAndUndoDuringPreviewStillInvalidatesTheRequestRevision() = runBlocking {
        fixture(1) { vm, editor, version, barrier ->
            vm.runEditorVersion(version)
            withTimeout(10_000) { barrier.reached.await() }
            editor.setScript("first", "!"); editor.undo()
            assertEquals(version.workflow, editor.workflow)
            assertFalse(editor.dirty.value)
            barrier.release.complete(Unit)
            rejectedBeforeRun(vm, barrier)
        }
    }
    @Test fun reopeningSameDraftDuringPreviewInvalidatesTheRequest() = runBlocking {
        fixture(1) { vm, editor, version, barrier ->
            vm.runEditorVersion(version)
            withTimeout(10_000) { barrier.reached.await() }
            editor.open(version.workflowId)
            assertEquals(version.workflow, editor.workflow)
            assertFalse(editor.dirty.value)
            barrier.release.complete(Unit)
            rejectedBeforeRun(vm, barrier)
        }
    }
    @Test fun editorRequestIsBoundBeforeWaitingForTheActionGate() = runBlocking {
        fixture(1) { vm, editor, version, barrier ->
            vm.runPreflight()
            withTimeout(10_000) { barrier.reached.await() }
            vm.runEditorVersion(version)
            editor.setScript("first", "!"); editor.undo()
            assertFalse(editor.dirty.value)
            barrier.release.complete(Unit)
            rejectedBeforeRun(vm, barrier)
        }
    }
    @Test fun changesDuringExecutionTimePreflightFailWithNoBodyOrVisits() = runBlocking {
        fixture(2) { vm, editor, version, barrier ->
            vm.runEditorVersion(version)
            withTimeout(10_000) { barrier.reached.await() }
            assertEquals(RunStatus.PREFLIGHT, vm.state.value?.status)
            editor.setScript("first", "!")
            barrier.release.complete(Unit)
            val failed = withTimeout(15_000) { vm.state.first { it?.status?.terminal == true } }!!
            assertEquals(RunStatus.FAILED, failed.status)
            assertTrue(failed.failure!!.contains("편집 내용과 저장 버전"))
            withTimeout(10_000) { vm.history.first { it.isNotEmpty() } }
            assertNull(vm.report.value)
            assertTrue(failed.visits.isEmpty())
            assertTrue(barrier.bodies().isEmpty())
        }
    }
}
