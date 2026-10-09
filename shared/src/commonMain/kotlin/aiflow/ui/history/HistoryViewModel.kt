package aiflow.ui.history

import aiflow.engine.*
import aiflow.model.*
import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Path.Companion.toPath

data class WorktreeEntry(val path: String, val branch: String?, val locked: Boolean, val main: Boolean)

class HistoryViewModel(private val recorder: RunRecorder, processes: ProcessExecutor) {
    val selected = MutableStateFlow<RunState?>(null)
    val artifacts = MutableStateFlow<List<String>>(emptyList())
    val file = MutableStateFlow<String?>(null)
    val text = MutableStateFlow("")
    val worktrees = MutableStateFlow<List<WorktreeEntry>>(emptyList())
    val ignored = MutableStateFlow<Boolean?>(null)
    private val owner = ManagedProcessRunner(processes)
    private val runner = CommandRunner(owner)
    suspend fun select(run: RunState) {
        val available = recorder.list().first { it.runId == run.runId }
        selected.value = available; file.value = null; text.value = ""
        artifacts.value = recorder.files(run.runId)
    }
    suspend fun read(relative: String) {
        require(relative in artifacts.value)
        text.value = recorder.read(selected.value!!.runId, relative); file.value = relative
    }
    suspend fun delete(confirmed: Boolean) {
        recorder.delete(selected.value!!.runId, confirmed)
        selected.value = null; artifacts.value = emptyList(); file.value = null; text.value = ""
    }
    private suspend fun git(args: List<String>): CommandResult {
        recorder.lease.requireHeld()
        val result = runner.run(ProcessSpec(listOf("git") + args, recorder.lease.repoPath.toString()), 30_000)
        check(result.termination == Termination.NORMAL && result.cleanupError == null) { result.cleanupError ?: result.error ?: "Git 확인 실패" }
        return result
    }
    suspend fun refreshWorktrees() {
        val result = git(listOf("worktree", "list", "--porcelain"))
        check(result.exitCode == 0) { result.stderr.joinToString("\n") }
        worktrees.value = parseWorktrees(result.stdout.joinToString("\n"))
        val ignore = git(listOf("check-ignore", "--no-index", ".aiflow/runs/aiflow-ignore-probe"))
        check(ignore.exitCode in listOf(0, 1)) { ignore.stderr.joinToString("\n") }
        ignored.value = ignore.exitCode == 0
    }
    suspend fun removeWorktree(entry: WorktreeEntry, confirmed: Boolean) {
        require(confirmed && !entry.main && !entry.locked)
        refreshWorktrees()
        require(entry in worktrees.value) { "워크트리 정보가 변경되었습니다 — 다시 확인하세요" }
        aiflow.git.WorktreeManager.remove(recorder.lease.repoPath.toString(), entry.path, runner)
        refreshWorktrees()
    }
    suspend fun close() { owner.cancelAll() }
    companion object {
        fun parseWorktrees(text: String): List<WorktreeEntry> = text.trimEnd().split("\n\n").mapIndexedNotNull { i, block ->
            val lines = block.lines()
            val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapIndexedNotNull null
            WorktreeEntry(path, lines.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch refs/heads/"), lines.any { it == "locked" || it.startsWith("locked ") }, i == 0)
        }
    }
}

/** Reproduce a noninteractive resume command from the recorded request; never implicitly run it. */
fun resumeCommand(run: RunState, visit: StepVisit, attempt: AttemptRecord): String? {
    val metadata = attempt.metadata ?: return null
    val id = attempt.sessionId ?: return null
    val step = run.workflow.steps.firstOrNull { it.id == visit.stepId } ?: return null
    val session = run.workflow.sessions[step.session?.ref] ?: return null
    val binding = run.sessionBindings.values.firstOrNull { it.id == id && it.provider == session.provider }
    val cwd = binding?.workspace ?: when (val workspace = session.workspace) {
        Workspace.Local -> run.workflow.repoPath
        is Workspace.Worktree -> {
            val root = run.workflow.worktreeRoot.toPath()
            val absolute = if (root.isAbsolute) root else run.workflow.repoPath.toPath() / root.toString()
            (absolute / workspace.name).normalized().toString()
        }
    }
    val request = ExecRequest(cwd, "여기에 재개 지시를 입력하세요", metadata.requestedModel, metadata.requestedEffort, id, metadata.binaryPath)
    val adapter = if (session.provider == Provider.CODEX) CodexAdapter() else AntigravityAdapter()
    val spec = try { adapter.buildCommand(request) } catch (_: ProviderConfigException) { return null }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    val command = spec.command.joinToString(" ", transform = ::quote)
    return "cd -- ${quote(cwd)} && " + if (spec.stdin != null) "printf '%s' ${quote(spec.stdin)} | $command" else command
}
