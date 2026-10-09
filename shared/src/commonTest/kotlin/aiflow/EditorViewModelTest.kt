package aiflow

import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.RepositoryLease
import aiflow.provider.Verification
import aiflow.storage.*
import aiflow.ui.editor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.*
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    private val fs = FakeFileSystem().apply { createDirectories("/repo".toPath()) }
    private val lease = object : RepositoryLease {
        override val writeMutex = Mutex(); override val repoPath = "/repo".toPath()
        override fun requireHeld() {}; override fun release() {}
    }
    private val store = WorkflowStore(fs, lease)
    private fun TestScope.editor(files: FileSystem = fs, storage: WorkflowStore = store, onVersion: suspend (WorkflowVersion) -> Unit = {}) = EditorViewModel(storage, files, "/repo", onVersion = onVersion, scope = backgroundScope)
    private fun EditorViewModel.step(id: String) = workflow!!.steps.first { it.id == id }
    private fun flow(w: Workflow) = w.start to w.steps.associate { it.id to it.transitions }

    @Test fun creationDuplicationAndDeletionNeverInferConnections() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        val old = vm.workflow!!
        val added = vm.addNode(StepKind.SHELL); val copy = vm.duplicateNode("second")
        assertTrue(vm.step(added).transitions.isEmpty()); assertTrue(vm.step(copy).transitions.isEmpty())
        assertEquals(old.start, vm.workflow!!.start)
        assertTrue(vm.workflow!!.steps.flatMap { it.transitions }.none { it.next == Target.StepId(copy) })
        vm.deleteNode("second")
        assertTrue(vm.step("first").transitions.none { it.next is Target.StepId })
        assertTrue(vm.step("third").transitions.any { it.next == Target.End })
        vm.deleteNode("first")
        assertEquals("", vm.workflow!!.start)
        assertIs<SaveOutcome.Invalid>(vm.save())
        assertEquals(vm.draft.value, store.loadDraft(vm.draft.value!!.workflowId))
        assertTrue(vm.versions().isEmpty())
    }
    @Test fun renameUpdatesAllReferencesCoordinatesAndSelectionAtomically() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR); val before = vm.draft.value
        vm.renameNode("first", "entry")
        assertEquals("entry", vm.workflow!!.start); assertEquals("entry", vm.selectedNode.value)
        assertNotNull(vm.workflow!!.editor!!.nodes["entry"]); assertFalse("first" in vm.workflow!!.editor!!.nodes)
        vm.renameNode("second", "middle")
        assertEquals(Target.StepId("middle"), vm.step("entry").transitions.first().next)
        assertFails { vm.renameNode("entry", "middle") }; assertFails { vm.renameNode("entry", "end") }; assertFails { vm.renameNode("entry", "../bad") }
        vm.undo(); vm.undo(); assertEquals(before, vm.draft.value)
        vm.redo(); vm.redo(); assertEquals("entry", vm.workflow!!.start)
    }
    @Test fun otherwiseRemainsUniqueLastAndPrioritiesRoundtripWithUndo() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        vm.addTransition("first", Transition(Condition.Otherwise, Target.Ask))
        vm.addTransition("first", Transition(Condition.FileContains("result", "APPROVED"), Target.End, 3))
        assertEquals(listOf(Condition.Success, Condition.Failure, Condition.FileContains("result", "APPROVED"), Condition.Otherwise), vm.step("first").transitions.map { it.`when` })
        assertFails { vm.addTransition("first", Transition(Condition.Otherwise, Target.End)) }
        val before = vm.draft.value
        vm.moveTransition("first", 2, 0)
        assertEquals(Condition.FileContains("result", "APPROVED"), vm.step("first").transitions.first().`when`)
        assertFails { vm.moveTransition("first", 3, 0) }
        vm.updateTransition("first", 0, Transition(Condition.Command("test -f x"), Target.StepId("first"), 2))
        vm.deleteTransition("first", 1)
        assertEquals(vm.workflow, WorkflowCodec().decode(WorkflowCodec().encode(vm.workflow!!)))
        vm.undo(); vm.undo(); vm.undo(); assertEquals(before, vm.draft.value)
        vm.redo(); vm.redo(); vm.redo(); assertEquals(Target.StepId("first"), vm.step("first").transitions.first().next)
    }
    @Test fun renameSessionsAndWorktreesUpdatesAgentAndShellReferences() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.EXAMPLE)
        vm.renameSession("impl", "implementation"); vm.renameWorktree("impl-wt", "code-wt")
        assertEquals("implementation", vm.step("implement").session!!.ref)
        assertEquals("implementation", vm.step("fix").session!!.ref)
        assertEquals(Workspace.Worktree("code-wt"), vm.workflow!!.sessions.getValue("implementation").workspace)
        assertEquals(Workspace.Worktree("code-wt"), vm.step("test").workspace)
        assertTrue(vm.worktreeReferences("code-wt").containsAll(listOf("implement", "fix", "test", "publish", "log")))
        vm.deleteSession("implementation"); assertTrue(vm.validateNow().any { it.severity == Severity.ERROR && it.stepId == "fix" })
        vm.undo(); vm.deleteWorktree("code-wt"); assertTrue(vm.validateNow().any { "Unknown worktree" in it.message })
    }
    @Test fun bangTogglePreservesTextAndAgentConfigurationAndCommonLimits() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.EXAMPLE)
        val original = vm.step("verify")
        val script = " \n !printf 'ok'\n  "
        vm.setScript("verify", script)
        assertEquals(script, vm.step("verify").script); assertEquals(StepKind.SHELL, vm.step("verify").effectiveKind)
        assertEquals(Workspace.Worktree("impl-wt"), vm.step("verify").workspace)
        assertEquals(original.checkTimeoutSec, vm.step("verify").checkTimeoutSec)
        val agentScript = " \n printf 'ok'\n  "
        vm.setScript("verify", agentScript)
        assertEquals(StepKind.AGENT, vm.step("verify").effectiveKind); assertNull(vm.step("verify").workspace)
        assertEquals(original.session, vm.step("verify").session); assertEquals(original.model, vm.step("verify").model)
        assertEquals(agentScript, vm.step("verify").script)
        vm.setKind("verify", StepKind.SHELL); assertEquals(agentScript, vm.step("verify").script)
        vm.setKind("verify", StepKind.AGENT); assertEquals(agentScript, vm.step("verify").script)
    }
    @Test fun validationDebouncesAndMapsNodeAndEdgeIssues() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        vm.updateStep("first") { it.copy(transitions = listOf(Transition(Condition.Command(""), Target.End))) }
        runCurrent(); advanceTimeBy(299); runCurrent()
        assertFalse(vm.issues.value.any { it.transitionIndex == 0 })
        advanceTimeBy(1); runCurrent()
        assertTrue(vm.issueMap["first"]!!.any { it.transitionIndex == 0 && it.severity == Severity.ERROR })
    }
    @Test fun layoutHandlesCyclesSelfLoopsDuplicatesAndOrphansWithoutChangingFlow() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        vm.addTransition("second", Transition(Condition.FileExists("flag"), Target.StepId("first"), 2))
        vm.addTransition("second", Transition(Condition.Command("test -f loop"), Target.StepId("second"), 3))
        vm.addTransition("first", Transition(Condition.FileExists("other"), Target.StepId("second")))
        vm.addNode(StepKind.SHELL)
        val before = flow(vm.workflow!!)
        vm.moveNode("first", NodePosition(-120f, 999f)); vm.autoLayout(); vm.sortedNodes("", true)
        assertEquals(before, flow(vm.workflow!!))
        assertEquals(GraphLayout.positions(vm.workflow!!), GraphLayout.positions(vm.workflow!!.copy(steps = vm.workflow!!.steps.reversed())))
        assertEquals(setOf("first", "second", "third"), GraphLayout.reachable(vm.workflow!!))
        assertEquals((vm.workflow!!.steps.map { it.id } + GraphLayout.special).toSet(), vm.workflow!!.editor!!.nodes.keys)
    }
    @Test fun missingCoordinatesKeepExistingPositionsAndRoundtripDraft() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        vm.moveNode("first", NodePosition(-8f, 111f)); vm.saveDraft()
        val id = vm.draft.value!!.workflowId
        val reopened = editor(); reopened.open(id)
        assertFalse(reopened.dirty.value); assertEquals(vm.workflow, reopened.workflow)
        val partial = vm.workflow!!.copy(editor = EditorLayout(mapOf("first" to NodePosition(9f, 8f))))
        fs.write("/partial.yaml".toPath()) { writeUtf8(WorkflowCodec().encode(partial)) }
        reopened.importYaml("/partial.yaml")
        assertEquals(NodePosition(9f, 8f), reopened.workflow!!.editor!!.nodes["first"])
        assertEquals(flow(partial), flow(reopened.workflow!!))
        fs.write("/none.yaml".toPath()) { writeUtf8(WorkflowCodec().encode(partial.copy(editor = null, steps = partial.steps.reversed()))) }
        reopened.importYaml("/none.yaml"); assertEquals(flow(partial), flow(reopened.workflow!!)); assertEquals(6, reopened.workflow!!.editor!!.nodes.size)
    }
    @Test fun invalidDraftAndWarningsNeverPublishWithoutAcknowledgement() = runTest {
        var callbacks = 0
        val vm = editor(onVersion = { callbacks++ }); vm.newWorkflow(WorkflowTemplate.EMPTY)
        assertIs<SaveOutcome.Invalid>(vm.save()); assertEquals(0, callbacks)
        val id = vm.draft.value!!.workflowId; val reopened = editor(); reopened.open(id); assertEquals(vm.workflow, reopened.workflow)
        vm.newWorkflow(WorkflowTemplate.LINEAR)
        vm.updateStep("first") { it.copy(transitions = it.transitions.take(1)) }
        assertIs<SaveOutcome.Warnings>(vm.save()); assertTrue(vm.versions().isEmpty()); assertFalse(vm.dirty.value)
        assertIs<SaveOutcome.Saved>(vm.save(true)); assertEquals(1, callbacks)
        vm.updateStep("first") { it.copy(script = "!") }; assertIs<SaveOutcome.Invalid>(vm.save()); assertEquals(1, callbacks)
        assertEquals(1, vm.versions().size)
    }
    @Test fun restoringVersionCreatesNewHistoryAndPreservesLaterVersions() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        val first = (vm.save() as SaveOutcome.Saved).version
        vm.edit { it.copy(name = "v2") }; val second = (vm.save() as SaveOutcome.Saved).version
        vm.showVersion(first); assertTrue(vm.readOnly); assertFails { vm.edit { it.copy(name = "bad") } }
        vm.exportYaml("/preview.yaml"); assertEquals(first.workflow, WorkflowCodec().decode(fs.read("/preview.yaml".toPath()) { readUtf8() }))
        assertFails { vm.restore(first, false) }; vm.restore(first, true)
        assertEquals(first.versionId, vm.draft.value!!.restoredFrom)
        val restored = (vm.save() as SaveOutcome.Saved).version
        assertEquals(first.versionId, restored.restoredFrom); assertNotEquals(first.versionId, restored.versionId)
        assertEquals(listOf(first, second, restored), vm.versions())
        assertEquals(second, store.loadVersion(second.workflowId, second.versionId))
        assertNull(vm.draft.value!!.restoredFrom)
    }
    @Test fun saveFailurePreservesDraftAndDoesNotNotifyExecution() = runTest {
        var callbacks = 0
        val failing = object : ForwardingFileSystem(fs) { override fun atomicMove(source: Path, target: Path) { if ("versions" in target.segments) throw IOException("version disk failure"); super.atomicMove(source, target) } }
        val storage = WorkflowStore(failing, lease); val vm = editor(failing, storage) { callbacks++ }
        vm.newWorkflow(WorkflowTemplate.LINEAR)
        assertFailsWith<IOException> { vm.save() }
        assertEquals(vm.draft.value, store.loadDraft(vm.draft.value!!.workflowId)); assertTrue(vm.versions().isEmpty()); assertEquals(0, callbacks)
        assertTrue(vm.message.value!!.startsWith("초안만 저장됨"))
    }
    @Test fun undoIsLimitedToFiftyAndDragIsOneEdit() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR)
        val position = vm.workflow!!.editor!!.nodes.getValue("first")
        vm.moveNode("first", NodePosition(123f, 456f)); vm.undo(); assertEquals(position, vm.workflow!!.editor!!.nodes["first"])
        repeat(55) { i -> vm.edit { it.copy(name = "name-$i") } }
        repeat(50) { vm.undo() }; assertFalse(vm.canUndo.value); assertEquals("name-4", vm.workflow!!.name)
        vm.redo(); assertEquals("name-5", vm.workflow!!.name)
        vm.edit { it.copy(name = "replacement") }; assertFalse(vm.canRedo.value)
    }
    @Test fun deletingUnsavedWorkflowAndCancellingRestoreAreSafe() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.EMPTY)
        assertFails { vm.delete(false) }; assertNotNull(vm.draft.value)
        vm.delete(true); assertNull(vm.draft.value)
        vm.newWorkflow(WorkflowTemplate.LINEAR); vm.save(); val previous = vm.draft.value
        vm.duplicateWorkflow("copy"); assertNotEquals(previous!!.workflowId, vm.draft.value!!.workflowId)
        assertEquals(flow(previous.workflow), flow(vm.workflow!!)); vm.discard(); assertNull(vm.draft.value)
        assertEquals(1, store.listWorkflows().size)
    }
    @Test fun candidateListingIsSeparateFromVersionSpecificExecutionEvidence() = runTest {
        val settings = AppSettings(codexModels = listOf("candidate"), agyModelsCache = ModelsCache("/agy", "1.3.1", kotlin.time.Instant.parse("2026-10-09T00:00:00Z"), listOf("gemini-3.8-flash-high")))
        val vm = EditorViewModel(store, fs, "/repo", settings, scope = backgroundScope)
        assertEquals(listOf("candidate"), vm.modelCandidates(Provider.CODEX))
        assertEquals(Verification.NOT_VERIFIED, vm.effortStatus(Provider.CODEX, "candidate", Effort.LOW, "0.160.1"))
        assertEquals(Verification.VERIFIED, vm.effortStatus(Provider.ANTIGRAVITY, "gemini-3.8-flash-high", Effort.HIGH, "1.3.1"))
        assertEquals(Verification.NOT_VERIFIED, vm.effortStatus(Provider.ANTIGRAVITY, "gemini-3.8-flash-high", Effort.MAX, "1.3.1"))
    }
    @Test fun publishingCallbackRunsOutsideStorageGateAndKeepsLaterEditsSeparate() = runTest {
        lateinit var vm: EditorViewModel
        vm = editor(onVersion = { vm.edit { it.copy(name = "later edit") }; vm.saveDraft() })
        vm.newWorkflow(WorkflowTemplate.LINEAR)
        val saved = (withTimeout(1000) { vm.save() } as SaveOutcome.Saved).version
        assertEquals("선형 3단계", saved.workflow.name)
        assertEquals("later edit", vm.workflow!!.name)
        assertEquals("later edit", store.loadDraft(saved.workflowId).workflow.name)
        assertEquals(saved, store.loadVersion(saved.workflowId, saved.versionId))
    }
    @Test fun canonicalExampleCanBeBuiltNodeByNodeWithoutHiddenEdges() = runTest {
        val expected = WorkflowTemplates.create(WorkflowTemplate.EXAMPLE, "/repo")
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.EMPTY)
        vm.edit { expected.copy(start = "", steps = emptyList(), editor = it.editor) }
        expected.steps.forEach { step ->
            val id = vm.addNode(step.kind); vm.renameNode(id, step.id)
            vm.updateStep(step.id) { step.copy(transitions = emptyList()) }
            assertTrue(vm.step(step.id).transitions.isEmpty())
        }
        expected.steps.forEach { step -> step.transitions.forEach { vm.addTransition(step.id, it) } }
        vm.setStart(expected.start)
        assertEquals(expected.copy(editor = null), vm.workflow!!.copy(editor = null))
        val saved = (vm.save() as SaveOutcome.Saved).version
        vm.autoLayout(); assertEquals(flow(saved.workflow), flow(vm.workflow!!))
    }
    @Test fun shutdownPersistsDirtyDraftEvenDuringVersionPreview() = runTest {
        val vm = editor(); vm.newWorkflow(WorkflowTemplate.LINEAR); val v = (vm.save() as SaveOutcome.Saved).version
        vm.edit { it.copy(name = "unsaved") }; vm.showVersion(v); vm.preserveDraftOnShutdown()
        assertEquals("unsaved", store.loadDraft(v.workflowId).workflow.name)
        assertEquals(v, store.loadVersion(v.workflowId, v.versionId))
    }
}
