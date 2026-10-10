package aiflow.ui.editor

import aiflow.model.*
import aiflow.model.Target
import aiflow.platform.FileDialogs
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

data class EdgeSelection(val source: String, val index: Int)
sealed interface SaveOutcome {
    data class Invalid(val issues: List<Issue>) : SaveOutcome
    data class Warnings(val issues: List<Issue>) : SaveOutcome
    data class Saved(val version: WorkflowVersion) : SaveOutcome
}

@OptIn(ExperimentalUuidApi::class)
class EditorViewModel(
    private val store: WorkflowStore,
    private val fs: FileSystem,
    val repository: String,
    var settings: AppSettings = AppSettings(),
    val dialogs: FileDialogs? = null,
    private val isRunning: (String) -> Boolean = { false },
    private val onVersion: suspend (WorkflowVersion) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val draft = MutableStateFlow<WorkflowDraft?>(null)
    val dirty = MutableStateFlow(false)
    private val editRevision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = editRevision.asStateFlow()
    val selectedNode = MutableStateFlow<String?>(null)
    val selectedEdge = MutableStateFlow<EdgeSelection?>(null)
    val issues = MutableStateFlow<List<Issue>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val cliVersions = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val savedVersion = MutableStateFlow<WorkflowVersion?>(null)
    val preview = MutableStateFlow<WorkflowVersion?>(null)
    val canUndo = MutableStateFlow(false); val canRedo = MutableStateFlow(false)
    private val undo = ArrayDeque<WorkflowDraft>(); private val redo = ArrayDeque<WorkflowDraft>()
    private var baseline: WorkflowDraft? = null
    private var validation: Job? = null
    private val gate = Mutex()
    val workflow get() = preview.value?.workflow ?: draft.value?.workflow
    val readOnly get() = preview.value != null
    val issueMap get() = issues.value.groupBy { it.stepId }
    fun validateNow(): List<Issue> = workflow?.let { WorkflowValidator(fs).validate(it) }.orEmpty().also { issues.value = it }
    private fun updated() {
        editRevision.update { it + 1 }
        dirty.value = draft.value != baseline
        canUndo.value = undo.isNotEmpty(); canRedo.value = redo.isNotEmpty()
        validation?.cancel(); validation = scope.launch { delay(300); validateNow() }
    }
    private var mergeKey: String? = null
    private var mergeAt = kotlin.time.TimeSource.Monotonic.markNow()
    fun edit(merge: String? = null, transform: (Workflow) -> Workflow) {
        check(!readOnly) { "버전 열람은 읽기 전용입니다" }
        val current = draft.value ?: return
        val changed = transform(current.workflow)
        if (changed == current.workflow) return
        if (merge == null || merge != mergeKey || mergeAt.elapsedNow().inWholeMilliseconds > 300 || undo.isEmpty()) undo.addLast(current)
        mergeKey = merge; mergeAt = kotlin.time.TimeSource.Monotonic.markNow()
        if (undo.size > 50) undo.removeFirst(); redo.clear()
        draft.value = current.copy(workflow = changed); updated()
    }
    private fun load(value: WorkflowDraft, persisted: Boolean) {
        mergeKey = null; validation?.cancel(); preview.value = null; draft.value = value.copy(workflow = GraphLayout.fillMissing(value.workflow))
        baseline = if (persisted) value else null
        undo.clear(); redo.clear(); selectedNode.value = null; selectedEdge.value = null; savedVersion.value = null
        updated(); validateNow()
    }
    fun importSnapshot(workflow: Workflow) = load(WorkflowDraft(Uuid.random().toString(), workflow = workflow.copy(repoPath = repository)), false)
    fun newWorkflow(template: WorkflowTemplate) = load(WorkflowDraft(Uuid.random().toString(), workflow = WorkflowTemplates.create(template, repository, settings.defaultWorktreeRoot)), false)
    suspend fun open(workflowId: String) = gate.withLock { load(store.loadDraft(workflowId), true) }
    suspend fun listDrafts(): List<WorkflowDraft> = store.listWorkflows().map { store.loadDraft(it) }
    suspend fun importYaml(path: String) = gate.withLock {
        val w = WorkflowCodec().decode(fs.read(path.toPath()) { readUtf8() })
        require(w.repoPath.toPath(normalize = true) == repository.toPath()) { "YAML repoPath가 열린 저장소와 다릅니다" }
        load(WorkflowDraft(Uuid.random().toString(), workflow = w), false)
    }
    suspend fun exportYaml(path: String) {
        val w = workflow ?: error("워크플로 없음")
        fs.write(path.toPath()) { writeUtf8(WorkflowCodec().encode(w)) }
    }
    fun duplicateWorkflow(name: String) {
        val w = workflow ?: return
        load(WorkflowDraft(Uuid.random().toString(), workflow = w.copy(name = name)), false)
    }
    suspend fun saveDraft() = gate.withLock {
        check(!readOnly)
        mergeKey = null
        val snapshot = draft.value ?: error("워크플로 없음")
        store.saveDraft(snapshot); baseline = snapshot; updated(); message.value = "초안 저장됨"
    }
    suspend fun save(warningsAcknowledged: Boolean = false): SaveOutcome {
        val outcome = gate.withLock {
        check(!readOnly)
        mergeKey = null
        val snapshot = draft.value ?: error("워크플로 없음")
        store.saveDraft(snapshot); baseline = snapshot; updated()
        val checked = WorkflowValidator(fs).validate(snapshot.workflow)
        issues.value = checked
        if (checked.any { it.severity == Severity.ERROR }) { message.value = "초안 저장됨 — 실행 불가"; return@withLock SaveOutcome.Invalid(checked) }
        val warnings = checked.filter { it.severity == Severity.WARNING }
        if (!warningsAcknowledged && warnings.isNotEmpty()) { message.value = "초안 저장됨 — 경고 확인 필요"; return@withLock SaveOutcome.Warnings(warnings) }
        try {
            val saved = store.saveVersion(snapshot, warningsAcknowledged)
            // Changes made while storage was pending remain dirty and cannot run as this snapshot.
            baseline = snapshot.copy(restoredFrom = null)
            if (draft.value == snapshot) draft.value = baseline
            savedVersion.value = saved; updated()
            message.value = "버전 저장됨 · ${saved.versionId.take(8)}"; SaveOutcome.Saved(saved)
        } catch (e: Exception) { message.value = "초안만 저장됨 — 버전 저장 실패: ${e.message}"; throw e }
    }
        if (outcome is SaveOutcome.Saved) onVersion(outcome.version)
        return outcome
    }
    suspend fun versions(): List<WorkflowVersion> = draft.value?.let { store.listVersions(it.workflowId) }.orEmpty()
    fun showVersion(version: WorkflowVersion) { mergeKey = null; editRevision.update { it + 1 }; preview.value = version; selectNode(null); validateNow() }
    fun closePreview() { mergeKey = null; editRevision.update { it + 1 }; preview.value = null; validateNow() }
    suspend fun restore(version: WorkflowVersion, confirmed: Boolean) = gate.withLock {
        require(confirmed); require(version.workflowId == draft.value?.workflowId)
        load(store.restoreToDraft(version.workflowId, version.versionId), true)
        message.value = "버전을 초안으로 복원함 — 저장하면 새 버전이 생성됩니다"
    }
    suspend fun delete(confirmed: Boolean) = gate.withLock {
        val current = draft.value ?: return@withLock
        require(confirmed && !isRunning(current.workflowId))
        if (current.workflowId in store.listWorkflows()) store.deleteWorkflow(current.workflowId, true, isRunning)
        draft.value = null; baseline = null; undo.clear(); redo.clear(); preview.value = null; updated(); issues.value = emptyList()
    }
    fun discard() { baseline?.let { load(it, true) } ?: run { draft.value = null; undo.clear(); redo.clear(); updated() } }
    fun undo() { mergeKey = null; if (!readOnly && undo.isNotEmpty()) { draft.value?.let(redo::addLast); draft.value = undo.removeLast(); selectNode(null); updated() } }
    fun redo() { mergeKey = null; if (!readOnly && redo.isNotEmpty()) { draft.value?.let(undo::addLast); draft.value = redo.removeLast(); selectNode(null); updated() } }
    fun selectNode(id: String?) { selectedNode.value = id; selectedEdge.value = null }
    fun selectEdge(source: String, index: Int) { selectedNode.value = source; selectedEdge.value = EdgeSelection(source, index) }
    fun updateStep(id: String, merge: String? = null, transform: (Step) -> Step) = edit(merge?.let { "$id:$it" }) { w -> w.copy(steps = w.steps.map { if (it.id == id) transform(it) else it }) }
    private fun unique(base: String, ids: Collection<String>): String { var candidate = base; var n = 2; while (candidate in ids || candidate in GraphLayout.special) candidate = "$base-${n++}"; return candidate }
    fun addNode(kind: StepKind = StepKind.AGENT): String {
        val id = unique("step", workflow?.steps.orEmpty().map { it.id })
        edit { w -> GraphLayout.fillMissing(w.copy(steps = w.steps + Step(id, "", kind = kind, session = w.sessions.keys.firstOrNull()?.let { SessionRef(it, SessionMode.NEW) }, workspace = if (kind == StepKind.SHELL) Workspace.Local else null))) }
        selectNode(id); return id
    }
    fun duplicateNode(id: String): String {
        val w = workflow ?: error("워크플로 없음"); val source = w.steps.first { it.id == id }; val copy = unique(id, w.steps.map { it.id })
        edit { current -> val position = current.editor?.nodes?.get(id) ?: NodePosition(300f, 50f)
            current.copy(steps = current.steps + source.copy(id = copy, transitions = emptyList()), editor = EditorLayout(current.editor?.nodes.orEmpty() + (copy to NodePosition(position.x + 50, position.y + 170)))) }
        selectNode(copy); return copy
    }
    fun deletionImpact(id: String): List<String> = buildList {
        val w = workflow ?: return@buildList
        if (w.start == id) add("start → $id")
        w.steps.forEach { s -> s.transitions.forEachIndexed { i, t -> if (s.id == id || t.next == Target.StepId(id)) add("${s.id} #${i + 1} → ${targetId(t.next)}") } }
    }
    fun deleteNode(id: String) {
        edit { w -> w.copy(start = if (w.start == id) "" else w.start, steps = w.steps.filterNot { it.id == id }.map { it.copy(transitions = it.transitions.filterNot { t -> t.next == Target.StepId(id) }) }, editor = w.editor?.let { EditorLayout(it.nodes - id) }) }
        selectNode(null)
    }
    fun renameNode(old: String, name: String) {
        require(WorkflowValidator.SAFE_NAME.matches(name) && name !in GraphLayout.special && workflow!!.steps.none { it.id == name && it.id != old }) { "중복·예약어 또는 잘못된 id" }
        edit { w -> w.copy(start = if (w.start == old) name else w.start, steps = w.steps.map { s -> s.copy(id = if (s.id == old) name else s.id, transitions = s.transitions.map { t -> if (t.next == Target.StepId(old)) t.copy(next = Target.StepId(name)) else t }) }, editor = w.editor?.let { EditorLayout(it.nodes.mapKeys { (key, _) -> if (key == old) name else key }) }) }
        selectedNode.value = name; selectedEdge.value = selectedEdge.value?.let { if (it.source == old) it.copy(source = name) else it }
    }
    fun setStart(id: String) = edit { it.copy(start = id) }
    fun moveNode(id: String, position: NodePosition) { require(position.x.isFinite() && position.y.isFinite()); edit { it.copy(editor = EditorLayout(it.editor?.nodes.orEmpty() + (id to position))) } }
    fun autoLayout() = edit { it.copy(editor = EditorLayout(GraphLayout.positions(it))) }
    fun sortedNodes(search: String, byKind: Boolean): List<Step> = workflow?.steps.orEmpty().filter { search.isBlank() || it.id.contains(search, true) || it.title.orEmpty().contains(search, true) }.sortedWith(if (byKind) compareBy({ it.effectiveKind.name }, { it.id }) else compareBy { it.id })
    fun addTransition(source: String, transition: Transition) {
        updateStep(source) { s -> require(transition.`when` != Condition.Otherwise || s.transitions.none { it.`when` == Condition.Otherwise })
            val index = if (transition.`when` == Condition.Otherwise) s.transitions.size else s.transitions.indexOfFirst { it.`when` == Condition.Otherwise }.let { if (it < 0) s.transitions.size else it }
            s.copy(transitions = s.transitions.toMutableList().apply { add(index, transition) }) }
        selectedEdge.value = null
    }
    fun updateTransition(source: String, index: Int, transition: Transition, merge: String? = null) {
        val current = workflow?.steps?.firstOrNull { it.id == source }?.transitions?.getOrNull(index) ?: return
        if (current == transition) return
        var selected = index
        val argumentKey = if (current.copy(maxVisits = transition.maxVisits) == transition) "maxVisits" else if (conditionName(current.`when`) == conditionName(transition.`when`) && current.copy(`when` = transition.`when`) == transition) when (val old = current.`when`) {
            is Condition.Command -> "command"
            is Condition.FileExists -> "path"
            is Condition.FileContains -> if ((transition.`when` as Condition.FileContains).path != old.path) "path" else "text"
            else -> null
        } else null
        updateStep(source, (merge ?: argumentKey)?.let { "transition:$index:$it" }) { step ->
            require(transition.`when` != Condition.Otherwise || step.transitions.withIndex().none { it.index != index && it.value.`when` == Condition.Otherwise }) { "otherwise 조건은 하나만 사용할 수 있습니다" }
            val indexed = step.transitions.mapIndexed { i, t -> i to if (i == index) transition else t }.sortedBy { it.second.`when` == Condition.Otherwise }
            selected = indexed.indexOfFirst { it.first == index }
            step.copy(transitions = indexed.map { it.second })
        }
        selectedEdge.value = EdgeSelection(source, selected)
    }
    fun deleteTransition(source: String, index: Int) { updateStep(source) { it.copy(transitions = it.transitions.filterIndexed { i, _ -> i != index }) }; selectedEdge.value = null }
    fun moveTransition(source: String, from: Int, to: Int) {
        updateStep(source) { s -> val list = s.transitions.toMutableList(); require(to in list.indices && list[from].`when` != Condition.Otherwise && list[to].`when` != Condition.Otherwise) { "otherwise는 항상 마지막입니다" }; list.add(to, list.removeAt(from)); s.copy(transitions = list) }; selectedEdge.value = EdgeSelection(source, to)
    }
    fun setScript(id: String, script: String) {
        var switched = false
        updateStep(id, "script") { s -> val marker = script.trimStart().startsWith('!'); val removed = s.script.trimStart().startsWith('!') && !marker
            val kind = if (marker) StepKind.SHELL else if (removed) StepKind.AGENT else s.kind
            switched = kind != s.effectiveKind
            s.copy(script = script, kind = kind, workspace = if (kind == StepKind.SHELL) s.workspace ?: workflow?.sessions?.get(s.session?.ref)?.workspace ?: Workspace.Local else null) }
        if (switched) message.value = if (script.trimStart().startsWith('!')) "셸 단계로 전환됨" else "Agent 단계로 전환됨"
    }
    fun setKind(id: String, kind: StepKind) = updateStep(id) { s ->
        val script = if (kind == StepKind.AGENT && s.script.trimStart().startsWith('!')) s.shellScript else s.script
        s.copy(kind = kind, script = script, workspace = if (kind == StepKind.SHELL) s.workspace ?: Workspace.Local else null)
    }
    fun renameSession(old: String, name: String) {
        require(WorkflowValidator.SAFE_NAME.matches(name) && (old == name || name !in workflow!!.sessions))
        edit { w -> w.copy(sessions = w.sessions.entries.associate { (key, value) -> (if (key == old) name else key) to value }, steps = w.steps.map { if (it.session?.ref == old) it.copy(session = it.session.copy(ref = name)) else it }) }
    }
    fun renameWorktree(old: String, name: String) {
        require(WorkflowValidator.SAFE_NAME.matches(name) && workflow!!.worktrees.none { it.name == name && it.name != old })
        fun workspace(value: Workspace?) = if (value == Workspace.Worktree(old)) Workspace.Worktree(name) else value
        edit { w -> w.copy(worktrees = w.worktrees.map { if (it.name == old) it.copy(name = name) else it }, sessions = w.sessions.mapValues { it.value.copy(workspace = workspace(it.value.workspace)!!) }, steps = w.steps.map { it.copy(workspace = workspace(it.workspace)) }) }
    }
    fun sessionReferences(name: String) = workflow?.steps.orEmpty().filter { it.session?.ref == name }.map { it.id }
    fun worktreeReferences(name: String): List<String> = workflow?.let { w -> w.steps.filter { it.workspace == Workspace.Worktree(name) || w.sessions[it.session?.ref]?.workspace == Workspace.Worktree(name) }.map { it.id } }.orEmpty()
    fun deleteSession(name: String) = edit { it.copy(sessions = it.sessions - name) }
    fun deleteWorktree(name: String) = edit { it.copy(worktrees = it.worktrees.filterNot { wt -> wt.name == name }) }
    fun modelCandidates(provider: Provider): List<String> = if (provider == Provider.CODEX) settings.codexModels else settings.agyModelsCache?.models.orEmpty()
    fun effortStatus(provider: Provider, model: String?, effort: Effort, version: String?): Verification = version?.let { VerifiedCliContract.forVersion(provider, it)?.modelEfforts?.get(ModelEffort(model.orEmpty(), effort))?.status } ?: Verification.NOT_VERIFIED
    suspend fun preserveDraftOnShutdown() = gate.withLock {
        if (dirty.value) draft.value?.let { store.saveDraft(it); baseline = it; updated() }
    }
    fun close() { scope.cancel() }
}
fun targetId(target: Target): String = when (target) { is Target.StepId -> target.id; Target.End -> "end"; Target.Ask -> "ask" }
fun targetOf(id: String): Target = when (id) { "end" -> Target.End; "ask" -> Target.Ask; else -> Target.StepId(id) }
fun conditionName(value: Condition): String = when (value) { Condition.Success -> "success"; Condition.Failure -> "failure"; Condition.Otherwise -> "otherwise"; is Condition.Command -> "command"; is Condition.FileExists -> "fileExists"; is Condition.FileContains -> "fileContains" }
