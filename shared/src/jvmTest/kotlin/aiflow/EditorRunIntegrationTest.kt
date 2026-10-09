package aiflow

import aiflow.engine.RunStatus
import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.*
import aiflow.ui.editor.*
import aiflow.ui.run.RunViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class EditorRunIntegrationTest {
    @Test fun exampleTemplatePreservesCanonicalPlanYaml() {
        val canonical = aiflow.storage.WorkflowCodec().decode(javaClass.getResourceAsStream("/feature-dev.yaml")!!.bufferedReader().use { it.readText() })
        assertEquals(canonical, WorkflowTemplates.create(WorkflowTemplate.EXAMPLE, canonical.repoPath).copy(editor = null))
    }
    @Test fun repositorySwitchRequiresExplicitSaveOrDiscardAndCancelKeepsDraft() = runBlocking {
        val directory = Files.createTempDirectory("aiflow-editor-switch-").toFile()
        val first = directory.resolve("first").apply { mkdir() }.canonicalPath
        val second = directory.resolve("second").apply { mkdir() }.canonicalPath
        val vm = RunViewModel(desktopPlatform().copy(settingsPath = directory.resolve("settings.json").absolutePath.toPath()))
        try {
            vm.openRepository(first); val editor = withTimeout(15_000) { vm.editor.first { it != null } }!!
            editor.newWorkflow(WorkflowTemplate.EMPTY)
            vm.openRepository(second)
            withTimeout(15_000) { vm.pendingRepository.first { it != null } }
            assertEquals(first, vm.repository.value); assertTrue(editor.dirty.value)
            vm.cancelRepositoryChange(); assertNull(vm.pendingRepository.value); assertTrue(editor.dirty.value)
            vm.openRepository(second); withTimeout(15_000) { vm.pendingRepository.first { it != null } }
            vm.confirmRepositoryChange(true)
            withTimeout(15_000) { vm.repository.first { it == second } }
            assertNotSame(editor, vm.editor.value)
            vm.openRepository(first); withTimeout(15_000) { vm.repository.first { it == first } }
            val reopened = vm.editor.value!!; assertEquals(1, reopened.listDrafts().size)
            reopened.open(reopened.listDrafts().single().workflowId); assertFalse(reopened.dirty.value)
        } finally { vm.close() }
    }
    @Test fun buildGraphSaveRunRearrangeRestoreAndReopen() = runBlocking {
        val directory = Files.createTempDirectory("aiflow-phase4-").toFile()
        val repo = directory.canonicalPath
        val platform = desktopPlatform().copy(settingsPath = directory.resolve("settings.json").absolutePath.toPath(), notifier = object : Notifier { override suspend fun notify(title: String, body: String) {} })
        assertEquals(0, captureProcess(platform.processes, ProcessSpec(listOf("git", "init", "-b", "main"), repo)).exitCode)
        val vm = RunViewModel(platform)
        lateinit var first: WorkflowVersion; lateinit var last: WorkflowVersion
        try {
            vm.openRepository(repo); val editor = withTimeout(15_000) { vm.editor.first { it != null } }!!
            editor.newWorkflow(WorkflowTemplate.EMPTY)
            val ids = (1..3).map { i -> editor.addNode(StepKind.SHELL).also { editor.setScript(it, "!printf 'visit-$i\\n'") } }
            editor.setStart(ids.first())
            ids.forEachIndexed { i, id -> editor.addTransition(id, Transition(Condition.Success, if (i == 2) Target.End else Target.StepId(ids[i + 1]))); editor.addTransition(id, Transition(Condition.Failure, Target.Ask)) }
            first = (editor.save() as SaveOutcome.Saved).version
            assertEquals(first, vm.selected.value)
            editor.moveNode(ids[0], NodePosition(900f, 500f)); editor.autoLayout(); editor.edit { it.copy(steps = it.steps.reversed()) }
            last = (editor.save() as SaveOutcome.Saved).version
            vm.runEditorVersion(last)
            val completed = withTimeout(30_000) { vm.state.first { it?.status?.terminal == true } }!!
            assertEquals(RunStatus.COMPLETED, completed.status, vm.error.value ?: completed.failure)
            assertEquals(ids, completed.visits.map { it.stepId }); assertEquals(last.workflow, completed.workflow)
            withTimeout(10_000) { vm.history.first { it.any { run -> run.runId == completed.runId } } }
            editor.restore(first, true); val restored = (editor.save() as SaveOutcome.Saved).version
            assertEquals(first.versionId, restored.restoredFrom); assertEquals(3, editor.versions().size)
            editor.setScript(ids[0], "!"); assertIs<SaveOutcome.Invalid>(editor.save()); assertEquals(restored, vm.selected.value)
            vm.runEditorVersion(restored)
            withTimeout(10_000) { vm.error.first { it != null } }
            assertNull(vm.state.value); assertEquals(1, vm.history.value.size)
            assertTrue(vm.error.value!!.contains("편집 내용과 저장 버전"))
            println("PHASE4_LOCAL_EVIDENCE=$repo/.aiflow")
        } finally { vm.close() }
        val reopened = RunViewModel(platform)
        try {
            reopened.openRepository(repo); val editor = withTimeout(15_000) { reopened.editor.first { it != null } }!!
            editor.open(first.workflowId)
            assertEquals("!", editor.workflow!!.steps.first { it.id == first.workflow.start }.script)
            assertEquals(3, editor.versions().size); assertEquals(first, editor.versions().first()); assertTrue(last in editor.versions())
            assertEquals(RunStatus.COMPLETED, reopened.history.value.single().status); assertNull(reopened.state.value)
        } finally { reopened.close() }
    }
}
