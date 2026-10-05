package aiflow.storage

import aiflow.model.*
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** worktreeRoot may intentionally be outside repoPath; a worktree may not escape that root. */
class WorkspacePaths(private val fs: FileSystem) {
    fun resolve(workflow: Workflow, workspace: Workspace): Path {
        val repo = workflow.repoPath.toPath(normalize = true)
        require(repo.isAbsolute) { "Resolve repository paths before preparing a run" }
        if (workspace == Workspace.Local) return repo
        val name = (workspace as Workspace.Worktree).name
        require(WorkflowValidator.SAFE_NAME.matches(name) && workflow.worktrees.any { it.name == name }) { "Invalid worktree name" }
        require(workflow.worktreeRoot.isNotBlank())
        val root = repo.resolve(workflow.worktreeRoot, normalize = true)
        val target = root.resolve(name, normalize = true)
        require(target.parent == root) { "Worktree escapes configured root" }
        require(fs.metadataOrNull(target)?.symlinkTarget == null) { "Worktree cannot redirect through a symlink" }
        return target
    }
}
