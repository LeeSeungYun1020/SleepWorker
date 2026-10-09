package aiflow.model

import okio.FileSystem
import okio.Path.Companion.toPath

enum class Severity { ERROR, WARNING }
data class Issue(val severity: Severity, val message: String, val stepId: String? = null, val transitionIndex: Int? = null)

class WorkflowValidator(private val fs: FileSystem) {
    fun validate(w: Workflow): List<Issue> = buildList {
        fun error(message: String, step: String? = null, edge: Int? = null) { add(Issue(Severity.ERROR, message, step, edge)) }
        fun warning(message: String, step: String? = null) { add(Issue(Severity.WARNING, message, step)) }
        fun name(value: String, label: String, step: String? = null) {
            if (!SAFE_NAME.matches(value)) error("Invalid $label: $value", step)
        }
        fun workspace(value: Workspace?, step: String? = null) {
            if (value is Workspace.Worktree && w.worktrees.none { it.name == value.name }) error("Unknown worktree: ${value.name}", step)
        }
        fun path(value: String, glob: Boolean, step: String, edge: Int? = null) {
            val invalid = value.isBlank() || value.any { it in "?[]" } || "**" in value ||
                (!glob && '*' in value) || '*' in value.substringBeforeLast('/', "") || '*' in value.substringBeforeLast('\\', "")
            if (invalid) error("Invalid check path: $value", step, edge)
        }
        fun command(value: String, step: String, edge: Int? = null) { if (value.isBlank()) error("Empty check command", step, edge) }
        if (w.name.isBlank()) error("Workflow name must not be blank")
        if (w.steps.isEmpty()) error("Workflow needs steps")
        val byId = w.steps.associateBy { it.id }
        if (w.start.isBlank() || w.start !in byId) error("start must reference an explicit step")
        if (w.maxSteps <= 0) error("maxSteps must be a positive Int")
        if (w.repoPath.isBlank() || w.baseBranch.isBlank() || w.worktreeRoot.isBlank()) error("repoPath, baseBranch and worktreeRoot must not be blank")
        if (w.repoPath.isNotBlank() && !fs.exists(w.repoPath.toPath())) warning("Repository does not exist")
        w.steps.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { error("Duplicate step id", it) }
        w.worktrees.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { error("Duplicate worktree: $it") }
        w.worktrees.forEach { name(it.name, "worktree"); if (it.branch.isBlank()) error("Empty worktree branch") }
        w.sessions.forEach { (key, session) -> name(key, "session"); workspace(session.workspace) }
        w.editor?.nodes?.forEach { (id, p) -> if (!p.x.isFinite() || !p.y.isFinite()) error("Coordinates must be finite", id) }
        w.steps.forEach { s ->
            name(s.id, "step id", s.id)
            if (s.id in setOf("start", "end", "ask")) error("Reserved step id", s.id)
            if (s.timeoutSec?.let { it <= 0 } == true || s.checkTimeoutSec <= 0) error("Timeout must be a positive Int", s.id)
            workspace(s.workspace, s.id)
            if (s.effectiveKind == StepKind.AGENT) {
                if (s.session == null || s.session.ref !in w.sessions) error("Agent needs a defined session", s.id)
                if (s.session?.mode == SessionMode.RESUME && s.workspace != null) error("Resume workspace belongs to its session", s.id)
            } else {
                if (s.workspace == null) error("Shell needs workspace", s.id)
                if (listOf("rm -rf", "--force", "push -f", "reset --hard").any { it in s.shellScript }) warning("Potentially destructive shell command", s.id)
            }
            if ((if (s.effectiveKind == StepKind.SHELL) s.shellScript else s.script).isBlank()) error("Empty script", s.id)
            when (val c = s.completion) {
                is Completion.FileExists -> path(c.path, true, s.id)
                is Completion.Command -> command(c.cmd, s.id)
                else -> Unit
            }
            if (s.transitions.isEmpty()) error("Explicit transitions required; connect to end or ask", s.id)
            s.transitions.forEachIndexed { index, t ->
                val target = (t.next as? Target.StepId)?.id?.let(byId::get)
                if (t.next is Target.StepId && target == null) error("Unknown target: ${t.next.id}", s.id, index)
                if (t.maxVisits?.let { it <= 0 } == true) error("maxVisits must be a positive Int", s.id, index)
                if (t.resetSession && target?.effectiveKind != StepKind.AGENT) error("resetSession requires an agent target", s.id, index)
                when (val c = t.`when`) {
                    Condition.Otherwise -> if (index != s.transitions.lastIndex) error("otherwise must be unique and last", s.id, index)
                    is Condition.FileExists -> path(c.path, true, s.id, index)
                    is Condition.FileContains -> { path(c.path, false, s.id, index); if (c.text.isBlank()) error("Empty search text", s.id, index) }
                    is Condition.Command -> command(c.cmd, s.id, index)
                    else -> Unit
                }
            }
            val conditions = s.transitions.map { it.`when` }
            if (Condition.Otherwise !in conditions && !(Condition.Success in conditions && Condition.Failure in conditions)) warning("Unmatched outcomes will wait for user confirmation", s.id)
        }
        val reachable = mutableSetOf<String>()
        val queue = ArrayDeque<String>().apply { if (w.start in byId) add(w.start) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!reachable.add(id)) continue
            byId[id]?.transitions?.mapNotNull { (it.next as? Target.StepId)?.id }?.filter { it in byId }?.forEach(queue::add)
        }
        w.steps.filter { it.id !in reachable }.forEach { warning("Unreachable step", it.id) }
        if (reachable.none { id -> byId[id]!!.transitions.any { it.next == Target.End } }) warning("No reachable end")
        // Independent per-session two-state fixed point: bounded by 2 * steps * sessions, including loops.
        w.sessions.keys.forEach { session ->
            val seen = mutableSetOf<Pair<String, Boolean>>()
            val pending = ArrayDeque<Pair<String, Boolean>>().apply { if (w.start in byId) add(w.start to false) }
            while (pending.isNotEmpty()) {
                val state = pending.removeFirst()
                if (!seen.add(state)) continue
                val s = byId[state.first] ?: continue
                val created = state.second || (s.effectiveKind == StepKind.AGENT && s.session == SessionRef(session, SessionMode.NEW))
                s.transitions.forEach { t ->
                    val next = (t.next as? Target.StepId)?.id?.let(byId::get) ?: return@forEach
                    val reset = t.resetSession && next.effectiveKind == StepKind.AGENT && next.session?.ref == session
                    pending.add(next.id to (created || reset))
                }
            }
            w.steps.filter { it.effectiveKind == StepKind.AGENT && it.session == SessionRef(session, SessionMode.RESUME) && it.id in reachable }.forEach { s ->
                if (s.id to true !in seen) error("Resume has no session creation path", s.id)
                else if (s.id to false in seen) warning("Resume can bypass session creation", s.id)
            }
        }
    }
    companion object { val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}") }
}
