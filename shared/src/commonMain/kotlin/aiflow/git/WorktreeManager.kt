package aiflow.git

import aiflow.engine.*
import aiflow.model.*
import aiflow.platform.ProcessSpec
import aiflow.provider.Termination
import aiflow.storage.WorkspacePaths
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.Path.Companion.toPath

class WorktreeManager(private val fs: FileSystem, private val io: ExecutionIO, private val controlTimeoutMs: Long = 30_000) {
    companion object {
        /** History-only maintenance does not invent a workflow run to record a Git operation. */
        suspend fun remove(repoPath: String, path: String, runner: CommandRunner) {
            val result = runner.run(ProcessSpec(listOf("git", "worktree", "remove", "--", path), repoPath), 30_000)
            currentCoroutineContext().ensureActive()
            check(result.cleanupError == null && result.termination == Termination.NORMAL && result.exitCode == 0) {
                result.cleanupError ?: result.error ?: "Worktree remove failed: ${result.stderr.joinToString("\n")}"
            }
        }
    }
    private var sequence = 0
    fun resolvePath(workflow: Workflow, workspace: Workspace): String = WorkspacePaths(fs).resolve(workflow, workspace).toString()
    private suspend fun git(repo: String, args: List<String>, path: String): CommandResult {
        val result = io.command("$path/${++sequence}", ProcessSpec(listOf("git") + args, repo), controlTimeoutMs)
        currentCoroutineContext().ensureActive()
        if (result.cleanupError != null) throw UnsafeCleanup(result.cleanupError)
        check(result.termination == Termination.NORMAL) { result.error ?: "Git command did not finish" }
        return result
    }
    suspend fun ensure(workflow: Workflow, definition: WorktreeDef, path: String) {
        val target = resolvePath(workflow, Workspace.Worktree(definition.name))
        val listed = git(workflow.repoPath, listOf("worktree", "list", "--porcelain"), path)
        check(listed.exitCode == 0) { listed.stderr.joinToString("\n") }
        val entries = listed.stdout.joinToString("\n").split("\n\n").map { block -> block.lines().mapNotNull {
            val split = it.indexOf(' '); if (split < 0) null else it.substring(0, split) to it.substring(split + 1)
        }.toMap() }
        val entry = entries.firstOrNull { it["worktree"]?.toPath(normalize = true)?.toString() == target }
        if (entry != null) {
            check(entry["branch"] == "refs/heads/${definition.branch}") { "Existing worktree branch mismatch: $target" }
            check(fs.metadataOrNull(target.toPath())?.isDirectory == true) { "Registered worktree is missing: $target" }
            return
        }
        check(!fs.exists(target.toPath())) { "Existing path is not this repository's worktree: $target" }
        val valid = git(workflow.repoPath, listOf("check-ref-format", "--branch", definition.branch), path)
        check(valid.exitCode == 0) { "Invalid branch ${definition.branch}" }
        val branch = git(workflow.repoPath, listOf("show-ref", "--verify", "--quiet", "refs/heads/${definition.branch}"), path)
        check(branch.exitCode in listOf(0, 1)) { "Cannot inspect branch" }
        val args = if (branch.exitCode == 0) listOf("worktree", "add", "--", target, definition.branch)
            else listOf("worktree", "add", "-b", definition.branch, "--", target, workflow.baseBranch)
        val added = git(workflow.repoPath, args, path)
        check(added.exitCode == 0) { "Worktree add failed: ${added.stderr.joinToString("\n")}" }
    }
    suspend fun remove(repoPath: String, path: String) {
        val result = git(repoPath, listOf("worktree", "remove", "--", path), "maintenance")
        check(result.exitCode == 0) { "Worktree remove failed" }
    }
    suspend fun isDirty(repoPath: String): Boolean {
        val result = git(repoPath, listOf("status", "--porcelain"), "preflight")
        check(result.exitCode == 0) { "Git status failed" }
        return result.stdout.any { it.isNotBlank() }
    }
}
