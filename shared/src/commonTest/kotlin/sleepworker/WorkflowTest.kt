package sleepworker

import sleepworker.model.*
import sleepworker.model.Target
import sleepworker.storage.*
import sleepworker.platform.*
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

internal fun shell(id: String = "one", next: Target = Target.End) = Step(id, "!echo hi", workspace = Workspace.Local, transitions = listOf(Transition(Condition.Otherwise, next)))
internal fun workflow(vararg steps: Step) = Workflow("demo", "/repo", "main", steps.firstOrNull()?.id ?: "", steps = steps.toList())
internal fun agent(id: String, mode: SessionMode, next: Target = Target.End) = Step(id, "  prompt\n", session = SessionRef("s", mode), transitions = listOf(Transition(Condition.Otherwise, next)))
class WorkflowTest {
    private val fs = FakeFileSystem().apply { createDirectories("/repo".toPath()) }
    private val validator = WorkflowValidator(fs)
    private val codec = WorkflowCodec()
    private fun errors(w: Workflow) = validator.validate(w).filter { it.severity == Severity.ERROR }
    @Test fun roundtripAndBangPreserveWhitespace() {
        val step = shell().copy(script = " \n !echo hi\n  ", completion = Completion.FileExists("out/*.txt"), transitions = listOf(
            Transition(Condition.FileContains("a.txt", "OK"), Target.Ask), Transition(Condition.Command("test -f a"), Target.StepId("one"), 3), Transition(Condition.Otherwise, Target.End)))
        val w = workflow(step).copy(editor = EditorLayout(mapOf("one" to NodePosition(2f, 3f))))
        assertEquals(w, codec.decode(codec.encode(w)))
        assertEquals(" \n echo hi\n  ", step.shellScript)
        assertEquals(StepKind.SHELL, step.effectiveKind)
        assertEquals("echo hi", shell().copy(script = "echo hi").shellScript)
        assertEquals(workflow(shell()), codec.decode(codec.encode(workflow(shell()))))
        assertEquals(Completion.ExitCode, codec.decode(codec.encode(workflow(shell().copy(completion = Completion.ExitCode)))).steps[0].completion)
    }
    @Test fun schemaRejectsUnknownMissingAndOverflow() {
        val text = codec.encode(workflow(shell()))
        assertFails { codec.decode(text + "\nunknown: true") }
        assertFails { codec.decode(text.lineSequence().filterNot { it.startsWith("start:") }.joinToString("\n")) }
        assertFails { codec.decode(text + "\nmaxSteps: 2147483648") }
        assertFails { codec.decode(text.replace("local", "elsewhere")) }
    }
    @Test fun everyStructuralErrorIsRejected() {
        val valid = workflow(shell())
        val invalid = listOf(
            workflow(), valid.copy(start = ""), valid.copy(start = "missing"), valid.copy(steps = listOf(shell(), shell())),
            workflow(shell("end")), workflow(shell("start")), workflow(shell("ask")), workflow(shell("../x")), workflow(shell("a/b")), workflow(shell("a".repeat(65))),
            valid.copy(maxSteps = 0), valid.copy(maxSteps = -1), valid.copy(repoPath = ""), valid.copy(baseBranch = " "), valid.copy(worktreeRoot = ""),
            workflow(shell().copy(timeoutSec = 0)), workflow(shell().copy(checkTimeoutSec = -1)),
            workflow(shell().copy(script = "  ! \n")), workflow(shell().copy(script = " \n", kind = StepKind.SHELL)),
            workflow(shell().copy(workspace = null)), workflow(shell().copy(workspace = Workspace.Worktree("absent"))),
            workflow(shell().copy(transitions = emptyList())), workflow(shell(next = Target.StepId("missing"))),
            workflow(shell().copy(transitions = listOf(Transition(Condition.Otherwise, Target.End, 0)))),
            workflow(shell().copy(transitions = listOf(Transition(Condition.Otherwise, Target.End), Transition(Condition.Success, Target.End)))),
            workflow(shell().copy(transitions = listOf(Transition(Condition.Otherwise, Target.End), Transition(Condition.Otherwise, Target.End)))),
            workflow(shell().copy(transitions = listOf(Transition(Condition.Success, Target.End, resetSession = true)))),
            workflow(shell().copy(transitions = listOf(Transition(Condition.Success, Target.StepId("one"), resetSession = true)))),
            workflow(Step("one", "prompt")), workflow(agent("one", SessionMode.NEW)),
            valid.copy(worktrees = listOf(WorktreeDef("bad/name", "b"))), valid.copy(worktrees = listOf(WorktreeDef("a", "b"), WorktreeDef("a", "c"))),
            valid.copy(sessions = mapOf("../bad" to SessionDef(Provider.CODEX, Workspace.Local))),
            valid.copy(sessions = mapOf("s" to SessionDef(Provider.CODEX, Workspace.Worktree("none")))),
            valid.copy(editor = EditorLayout(mapOf("one" to NodePosition(Float.NaN, 0f)))),
            valid.copy(editor = EditorLayout(mapOf("one" to NodePosition(0f, Float.POSITIVE_INFINITY)))),
        )
        invalid.forEachIndexed { index, w -> assertTrue(errors(w).isNotEmpty(), "case $index") }
        assertTrue(errors(valid.copy(maxSteps = Int.MAX_VALUE, steps = listOf(shell().copy(timeoutSec = Int.MAX_VALUE)))).isEmpty())
    }
    @Test fun checksValidatePathsCommandsAndText() {
        listOf("", "**/a", "a?", "[ab]", "*/a", "a/**").forEach { path ->
            assertTrue(errors(workflow(shell().copy(completion = Completion.FileExists(path)))).isNotEmpty(), path)
            assertTrue(errors(workflow(shell().copy(transitions = listOf(Transition(Condition.FileExists(path), Target.End))))).isNotEmpty(), path)
        }
        listOf(Condition.FileContains("*.txt", "ok"), Condition.FileContains("a", ""), Condition.Command(" ")).forEach { condition ->
            assertTrue(errors(workflow(shell().copy(transitions = listOf(Transition(condition, Target.End))))).isNotEmpty())
        }
        assertTrue(errors(workflow(shell().copy(completion = Completion.Command("")))).isNotEmpty())
        assertTrue(errors(workflow(shell().copy(completion = Completion.FileExists("out/*.txt")))).isEmpty())
    }
    @Test fun sessionFixedPointHandlesBypassResetAndLoops() {
        val sessions = mapOf("s" to SessionDef(Provider.CODEX, Workspace.Local))
        val resume = agent("resume", SessionMode.RESUME)
        val new = agent("new", SessionMode.NEW, Target.StepId("resume"))
        assertTrue(errors(workflow(resume).copy(sessions = sessions)).any { "creation" in it.message })
        assertTrue(errors(workflow(new, resume).copy(sessions = sessions)).isEmpty())
        assertTrue(errors(workflow(new, resume.copy(workspace = Workspace.Local)).copy(sessions = sessions)).isNotEmpty())
        val bypass = shell("entry").copy(transitions = listOf(Transition(Condition.Success, Target.StepId("new")), Transition(Condition.Otherwise, Target.StepId("resume"))))
        val w = workflow(bypass, new, resume).copy(sessions = sessions)
        assertTrue(validator.validate(w).any { it.severity == Severity.WARNING && "bypass" in it.message })
        assertTrue(errors(w).isEmpty())
        val reset = shell("entry").copy(transitions = listOf(Transition(Condition.Otherwise, Target.StepId("resume"), resetSession = true)))
        assertTrue(errors(workflow(reset, resume).copy(sessions = sessions)).isEmpty())
        val loop = resume.copy(transitions = listOf(Transition(Condition.Success, Target.StepId("entry")), Transition(Condition.Otherwise, Target.End)))
        val original = w.copy(steps = listOf(bypass, new, loop))
        val reordered = original.copy(steps = original.steps.reversed(), editor = EditorLayout(mapOf("entry" to NodePosition(10f, 99f))))
        assertEquals(validator.validate(original).toSet(), validator.validate(reordered).toSet())
    }
    @Test fun draftsVersionsRestoreAndRename() = runTest {
        val lease = object : RepositoryLease { override val writeMutex = kotlinx.coroutines.sync.Mutex(); override val repoPath = "/repo".toPath(); override fun requireHeld() {}; override fun release() {} }
        val store = WorkflowStore(fs, lease)
        val draft = store.importYaml(codec.encode(workflow()))
        assertEquals(draft, store.loadDraft(draft.workflowId))
        assertFailsWith<IllegalArgumentException> { store.saveVersion(draft) }
        val valid = draft.copy(workflow = workflow(shell()))
        val one = store.saveVersion(valid)
        assertEquals(one, store.saveVersion(valid))
        val renamed = valid.copy(workflow = valid.workflow.copy(name = "renamed", editor = EditorLayout(mapOf("one" to NodePosition(5f, 6f)))))
        val two = store.saveVersion(renamed)
        assertNotEquals(one.versionId, two.versionId)
        val restored = store.restoreToDraft(draft.workflowId, one.versionId)
        assertEquals(restored, store.loadDraft(draft.workflowId))
        val three = store.saveVersion(restored)
        assertEquals(one.versionId, three.restoredFrom)
        assertEquals(one.workflow, three.workflow)
        assertNull(store.loadDraft(draft.workflowId).restoredFrom)
        assertEquals(setOf(one, two, three), store.listVersions(draft.workflowId).toSet())
        assertFails { store.loadDraft("../bad") }
        assertFails { store.deleteWorkflow(draft.workflowId, false) { false } }
        assertFails { store.deleteWorkflow(draft.workflowId, true) { true } }
    }
    @Test fun settingsRoundtrip() = runTest {
        val store = SettingsStore(fs, "/settings/settings.json".toPath())
        assertEquals(AppSettings(), store.load())
        val settings = AppSettings(codexPath = "/bin/custom", codexModels = listOf("candidate"), notificationsEnabled = false)
        store.save(settings)
        assertEquals(settings, store.load())
    }
}
