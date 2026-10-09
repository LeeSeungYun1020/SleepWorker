package sleepworker

import sleepworker.model.*
import sleepworker.platform.*
import sleepworker.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import okio.*
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.*

class StorageBoundaryTest {
    private val fs = FakeFileSystem().apply { allowSymlinks = true; createDirectories("/repo".toPath()) }
    private val lease = object : RepositoryLease {
        override val writeMutex = Mutex()
        override val repoPath = "/repo".toPath()
        var held = true
        override fun requireHeld() { check(held) }
        override fun release() { held = false }
    }
    @Test fun failedCommitPreservesDraftVersionsAndHidesTemporaryFiles() = runTest {
        var fail = false
        val failing = object : ForwardingFileSystem(fs) {
            override fun atomicMove(source: Path, target: Path) {
                if (fail) throw IOException("injected atomic move failure")
                super.atomicMove(source, target)
            }
        }
        val store = WorkflowStore(failing, lease)
        val draft = store.importYaml(WorkflowCodec().encode(workflow(shell())))
        val first = store.saveVersion(draft)
        val changed = draft.copy(workflow = draft.workflow.copy(name = "changed"))
        fail = true
        assertFailsWith<IOException> { store.saveDraft(changed) }
        assertEquals(draft, store.loadDraft(draft.workflowId))
        assertFailsWith<IOException> { store.saveVersion(changed) }
        assertEquals(listOf(first), store.listVersions(draft.workflowId))
        assertTrue(fs.listRecursively("/repo".toPath()).none { it.name.endsWith(".tmp") })
    }
    @Test fun collisionNeverOverwritesImmutableVersion() = runTest {
        val fixed = "00000000-0000-4000-8000-000000000001"
        val store = WorkflowStore(fs, lease, newId = { fixed })
        val draft = store.importYaml(WorkflowCodec().encode(workflow(shell())))
        val first = store.saveVersion(draft)
        assertFails { store.saveVersion(draft.copy(workflow = draft.workflow.copy(name = "changed"))) }
        assertEquals(listOf(first), store.listVersions(draft.workflowId))
    }
    @Test fun warningsRequireAcknowledgementAndLockIsMandatory() = runTest {
        val store = WorkflowStore(fs, lease)
        val draft = store.importYaml(WorkflowCodec().encode(workflow(shell().copy(transitions = listOf(Transition(Condition.Success, sleepworker.model.Target.End))))))
        assertFails { store.saveVersion(draft) }
        store.saveVersion(draft, warningsAcknowledged = true)
        lease.release()
        assertFails { store.saveDraft(draft) }
        assertFails { store.listVersions(draft.workflowId) }
    }
    @Test fun symlinkStorageEscapeRejected() = runTest {
        val store = WorkflowStore(fs, lease)
        fs.createDirectories("/elsewhere".toPath())
        fs.createSymlink("/repo/.sleepworker".toPath(), "/elsewhere".toPath())
        assertFails { store.importYaml(WorkflowCodec().encode(workflow(shell()))) }
        assertTrue(fs.list("/elsewhere".toPath()).isEmpty())
    }
    @Test fun twoStoresShareRepositoryWriteSerialization() = runTest {
        val first = WorkflowStore(fs, lease)
        val second = WorkflowStore(fs, lease)
        val draft = first.importYaml(WorkflowCodec().encode(workflow(shell())))
        val versions = awaitAll(async { first.saveVersion(draft) }, async { second.saveVersion(draft) })
        assertEquals(versions[0], versions[1])
        assertEquals(1, first.listVersions(draft.workflowId).size)
    }
    @Test fun worktreeRootOutsideRepoButNamesCannotEscape() {
        val w = workflow(shell()).copy(worktreeRoot = "../repo.worktrees", worktrees = listOf(WorktreeDef("impl", "ai/impl")))
        val paths = WorkspacePaths(fs)
        assertEquals("/repo.worktrees/impl".toPath(), paths.resolve(w, Workspace.Worktree("impl")))
        assertFails { paths.resolve(w, Workspace.Worktree("../escape")) }
        assertFails { paths.resolve(w, Workspace.Worktree("missing")) }
        fs.createDirectories("/repo.worktrees".toPath())
        fs.createSymlink("/repo.worktrees/impl".toPath(), "/repo".toPath())
        assertFails { paths.resolve(w, Workspace.Worktree("impl")) }
    }
}
