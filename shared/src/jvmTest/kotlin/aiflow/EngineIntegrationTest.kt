package aiflow

import aiflow.engine.*
import aiflow.git.WorktreeManager
import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

/** Local shell/Git only: no model, authentication, network, or publishing. */
class EngineIntegrationTest {
    @Test fun exactReviewFirstLineAndShaDriveTheExplicitBranch() = runBlocking {
        val root = Files.createTempDirectory("aiflow-engine-review-").toRealPath().toString().toPath()
        val fs = FileSystem.SYSTEM
        val lease = JvmRepositoryLock().acquire(root)
        try {
            val store = WorkflowStore(fs, lease)
            val recorder = RunRecorder(fs, lease)
            val executor = JvmProcessExecutor()
            val check = """
                first=${'$'}(head -n 1 review.txt) || exit 2
                sha=${'$'}(cat review.sha) || exit 2
                test "${'$'}first" = APPROVED && test "${'$'}sha" = expected
            """.trimIndent()
            for ((first, sha, expected) in listOf(Triple("CHANGES_REQUESTED", "expected", "fix"), Triple("APPROVED", "wrong", "fix"), Triple("APPROVED", "expected", "publish"))) {
                fs.write(root / "review.txt") { writeUtf8("$first\nAPPROVED appears in the body\n") }
                fs.write(root / "review.sha") { writeUtf8(sha) }
                val review = shell("review").copy(transitions = listOf(Transition(Condition.Command(check), Target.StepId("publish")), Transition(Condition.Otherwise, Target.StepId("fix"))))
                val workflow = workflow(review, shell("publish"), shell("fix")).copy(repoPath = root.toString())
                val draft = store.importYaml(WorkflowCodec().encode(workflow))
                val version = store.saveVersion(draft)
                val state = RunOrchestrator(store, recorder, executor, JvmTempFiles()).start(version)
                assertEquals(RunStatus.COMPLETED, state.status, state.failure)
                assertEquals(listOf("review", expected), state.visits.map { it.stepId })
            }
        } finally { lease.release(); fs.deleteRecursively(root) }
    }
    @Test fun shellPrefixIsRemovedBeforeRealExecutionAndRecording() = runBlocking {
        val root = Files.createTempDirectory("aiflow-shell-prefix-").toRealPath().toString().toPath()
        val fs = FileSystem.SYSTEM
        val lease = JvmRepositoryLock().acquire(root)
        try {
            val store = WorkflowStore(fs, lease)
            val recorder = RunRecorder(fs, lease)
            for (script in listOf("!printf prefix-ok > output.txt", " \n !printf prefix-ok > output.txt\nprintf second-line >> output.txt")) {
                val definition = workflow(shell().copy(script = script)).copy(repoPath = root.toString())
                val version = store.saveVersion(store.importYaml(WorkflowCodec().encode(definition)))
                val state = RunOrchestrator(store, recorder, JvmProcessExecutor(), JvmTempFiles()).start(version)
                assertEquals(RunStatus.COMPLETED, state.status, state.failure)
                assertEquals(if ('\n' in script) "prefix-oksecond-line" else "prefix-ok", fs.read(root / "output.txt") { readUtf8() })
                val path = recorder.attemptPath(state.visits.single(), 1)
                assertEquals(definition.steps.single().shellScript, fs.read(root / ".aiflow" / "runs" / state.runId / path / "script.txt") { readUtf8() })
            }
        } finally { lease.release(); fs.deleteRecursively(root) }
    }
    @Test fun realGitWorktreeCreationReuseDirtyAndRemoval() = runBlocking {
        val root = Files.createTempDirectory("aiflow-engine-git-").toRealPath().toString().toPath()
        val repo = root / "repo"
        val fs = FileSystem.SYSTEM
        fs.createDirectories(repo)
        val executor = JvmProcessExecutor()
        suspend fun git(vararg args: String) {
            val result = captureProcess(executor, ProcessSpec(listOf("git") + args, repo.toString()))
            assertEquals(0, result.exitCode, result.stderr.joinToString("\n"))
        }
        git("init", "-b", "main")
        git("-c", "user.name=Engine Test", "-c", "user.email=engine@example.invalid", "commit", "--allow-empty", "-m", "fixture")
        val lease = JvmRepositoryLock().acquire(repo)
        try {
            val recorder = RunRecorder(fs, lease)
            val io = ExecutionIO(CommandRunner(ManagedProcessRunner(executor)), recorder, "20261005-130000-abcdef1234567890", MutableSharedFlow(extraBufferCapacity = 100))
            val manager = WorktreeManager(fs, io)
            val definition = WorktreeDef("impl", "ai/impl")
            val workflow = workflow(shell()).copy(repoPath = repo.toString(), worktreeRoot = "../worktrees", worktrees = listOf(definition))
            manager.ensure(workflow, definition, "preparing")
            manager.ensure(workflow, definition, "preparing")
            val target = manager.resolvePath(workflow, Workspace.Worktree("impl"))
            assertTrue(fs.exists(target.toPath() / ".git"))
            assertFalse(manager.isDirty(target))
            fs.write(target.toPath() / "dirty.txt") { writeUtf8("local change") }
            assertTrue(manager.isDirty(target))
            fs.delete(target.toPath() / "dirty.txt")
            manager.remove(repo.toString(), target)
            assertFalse(fs.exists(target.toPath()))
        } finally { lease.release(); fs.deleteRecursively(root) }
    }
}
