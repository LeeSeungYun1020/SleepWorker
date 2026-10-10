package aiflow.ui.editor

import aiflow.model.*
import aiflow.model.Target
import aiflow.ui.run.RunViewModel
import aiflow.ui.components.*
import aiflow.ui.theme.LocalStatusColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

private data class EditorConfirmation(val message: String, val confirmLabel: String, val destructive: Boolean, val action: () -> Unit)
private enum class EditorTab(val label: String) { GRAPH("그래프"), DEFINITIONS("세션·워크트리"), GENERAL("일반") }
private val EditingEnabled = compositionLocalOf { true }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(run: RunViewModel, onRun: () -> Unit) {
    val vm by run.editor.collectAsState()
    val scope = rememberCoroutineScope()
    val pendingIdentifiers = remember { PendingIdentifierEdits() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    if (vm == null) { EmptyState("워크플로 편집기", "상단에서 Git 저장소를 열어 시작하세요."); return }
    val editor = vm!!
    fun beforeSelection(): Boolean {
        if (!pendingIdentifiers.commit()) return false
        focusManager.clearFocus(); return true
    }
    val draft by editor.draft.collectAsState(); val preview by editor.preview.collectAsState()
    val dirty by editor.dirty.collectAsState(); val version by editor.savedVersion.collectAsState()
    val issues by editor.issues.collectAsState(); val message by editor.message.collectAsState()
    val node by editor.selectedNode.collectAsState(); val edge by editor.selectedEdge.collectAsState()
    val canUndo by editor.canUndo.collectAsState(); val canRedo by editor.canRedo.collectAsState()
    val w = preview?.workflow?.let(GraphLayout::fillMissing) ?: draft?.workflow
    var busy by remember(editor) { mutableStateOf(false) }
    var tab by remember { mutableStateOf(EditorTab.GRAPH) }
    val searchFocus = remember { FocusRequester() }
    var showSearch by remember { mutableStateOf(false) }; var search by remember { mutableStateOf("") }; var sortKind by remember { mutableStateOf(false) }
    LaunchedEffect(showSearch, tab) { if (showSearch && tab == EditorTab.GRAPH) { withFrameNanos {}; searchFocus.requestFocus() } }
    var issueFocus by remember { mutableStateOf<Issue?>(null) }
    var showIssues by remember { mutableStateOf(true) }; var focus by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var picker by remember { mutableStateOf<String?>(null) }; var drafts by remember { mutableStateOf(emptyList<WorkflowDraft>()) }; var versions by remember { mutableStateOf(emptyList<WorkflowVersion>()) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }; var confirmation by remember { mutableStateOf<EditorConfirmation?>(null) }
    var warnings by remember { mutableStateOf<List<Issue>?>(null) }; var runAfterSave by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportPath by remember { mutableStateOf("") }; var importPath by remember { mutableStateOf("") }; var copyName by remember { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    fun work(after: () -> Unit = {}, block: suspend () -> Unit) { if (busy) return; busy = true; scope.launch { try { withContext(Dispatchers.Default) { block() }; busy = false; after() } catch (e: Exception) { editor.message.value = e.message ?: e.toString() } finally { busy = false } } }
    fun guard(action: () -> Unit) { if (dirty) pending = action else action() }
    fun saved(outcome: SaveOutcome, execute: Boolean) { when (outcome) {
        is SaveOutcome.Warnings -> { warnings = outcome.issues; runAfterSave = execute }
        is SaveOutcome.Invalid -> { showIssues = true }
        is SaveOutcome.Saved -> if (execute) { run.runEditorVersion(outcome.version); onRun() }
    } }
    val menuCommand by run.editorCommand.collectAsState()
    LaunchedEffect(menuCommand) {
        when (menuCommand?.second) {
            "new" -> guard { picker = "new" }
            "open" -> guard { work { drafts = editor.listDrafts(); picker = "open" } }
            "save" -> if (w != null && preview == null) run { focusManager.clearFocus(); work { saved(editor.save(), false) } }
            "undo" -> editor.undo()
            "redo" -> editor.redo()
            "search" -> { tab = EditorTab.GRAPH; showSearch = true }
            "versions" -> if (draft != null) work { versions = editor.versions(); picker = "versions" }
        }
        run.editorCommand.value = null
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); editor.message.value = null } }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        var overflow by remember { mutableStateOf(false) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(w?.name ?: "워크플로 없음", Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
            StatusBadge(if (w == null) "미선택" else if (preview != null) "버전 열람 · ${preview!!.versionId.take(8)}" else if (dirty) "초안 · 변경됨" else "초안 · 저장됨", if (w == null) Icons.Outlined.FolderOpen else if (preview != null) Icons.Outlined.Visibility else if (dirty) Icons.Outlined.Edit else Icons.Outlined.CheckCircle)
            ToolIcon("실행 취소 · ⌘Z", Icons.Outlined.Undo, canUndo && preview == null && !busy) { editor.undo() }
            ToolIcon("다시 적용 · ⇧⌘Z", Icons.Outlined.Redo, canRedo && preview == null && !busy) { editor.redo() }
            FilledTonalButton({ run { focusManager.clearFocus(); work { saved(editor.save(), false) } } }, enabled = w != null && preview == null && !busy) { Text("저장") }
            Button({ run { focusManager.clearFocus(); work { saved(editor.save(), true) } } }, enabled = w != null && preview == null && !busy) { Text("저장 후 실행") }
            Box {
                ToolIcon("워크플로 작업", Icons.Outlined.MoreVert, !busy) { overflow = true }
                DropdownMenu(overflow, { overflow = false }) {
                    fun close(action: () -> Unit) { overflow = false; action() }
                    DropdownMenuItem(text = { Text("새로 만들기") }, onClick = { close { guard { picker = "new" } } })
                    DropdownMenuItem(text = { Text("초안 열기") }, onClick = { close { guard { work { drafts = editor.listDrafts(); picker = "open" } } } })
                    DropdownMenuItem(text = { Text("버전 기록") }, enabled = draft != null, onClick = { close { work { versions = editor.versions(); picker = "versions" } } })
                    DropdownMenuItem(text = { Text("YAML 가져오기") }, onClick = { close { guard { picker = "import" } } })
                    DropdownMenuItem(text = { Text("YAML 내보내기") }, enabled = w != null, onClick = { close { exportPath = ""; picker = "export" } })
                    DropdownMenuItem(text = { Text("복제") }, enabled = w != null, onClick = { close { guard { copyName = w?.name.orEmpty() + " 복사"; picker = "copy" } } })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("워크플로 삭제", color = MaterialTheme.colorScheme.error) }, enabled = draft != null && preview == null, onClick = { close { confirmation = EditorConfirmation("워크플로를 삭제할까요? 버전 파일이 삭제됩니다. 기존 런 기록은 유지됩니다.", "삭제", true) { work { editor.delete(true); run.refreshVersions() } } } })
                }
            }
            Text("오류 ${issues.count { it.severity == Severity.ERROR }} · 경고 ${issues.count { it.severity == Severity.WARNING }}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelLarge)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (preview != null) Row {
            Text("${preview!!.createdAt} · 복원 출처: ${preview!!.restoredFrom ?: "없음"}", Modifier.weight(1f).padding(8.dp))
            OutlinedButton({ confirmation = EditorConfirmation("현재 초안을 이 버전으로 교체할까요? 보존할 내용은 먼저 YAML로 내보내세요. 저장소 파일·세션·런 기록은 되돌리지 않습니다.", "복원", false) { work { editor.restore(preview!!, true) } } }, enabled = !busy) { Text("이 버전으로 복원") }
            TextButton({ editor.closePreview() }) { Text("초안으로 돌아가기") }
        }
        if (w != null) {
            SecondaryTabRow(selectedTabIndex = tab.ordinal) { EditorTab.entries.forEach { item -> Tab(tab == item, { if (beforeSelection()) tab = item }, text = { Text(item.label) }) } }
            CompositionLocalProvider(EditingEnabled provides (preview == null && !busy), LocalPendingIdentifierEdits provides pendingIdentifiers) {
                when (tab) {
                    EditorTab.GRAPH -> {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.width(if (showSearch) 170.dp else 40.dp)) {
                                ToolIcon(if (showSearch) "목록 접기" else "노드 목록", Icons.Outlined.Menu) { showSearch = !showSearch }
                                if (showSearch) {
                                    OutlinedTextField(search, { search = it }, label = { Text("검색") }, singleLine = true, modifier = Modifier.trackTextInputFocus().fillMaxWidth().focusRequester(searchFocus))
                                    TextButton({ sortKind = !sortKind }) { Text(if (sortKind) "종류 정렬" else "이름 정렬") }
                                    Column(Modifier.verticalScroll(rememberScrollState())) { editor.sortedNodes(search, sortKind).forEach { step -> SelectionItem(step.id, selected = node == step.id) { if (beforeSelection()) { editor.selectNode(step.id); focus = step.id to ((focus?.second ?: 0) + 1) } } } }
                                }
                            }
                            GraphCanvas(editor, w, focus, { source, target -> if (source == "start") editor.setStart(target) else connect = source to target }, Modifier.weight(1f).fillMaxHeight(), editingEnabled = EditingEnabled.current, beforeSelection = ::beforeSelection)
                            Column(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val selected = w.steps.firstOrNull { it.id == node }
                                if (edge != null && selected != null && edge!!.index in selected.transitions.indices) {
                                    Text("화살표 · 우선순위 ${edge!!.index + 1}", style = MaterialTheme.typography.titleMedium)
                                    key(selected.id, edge!!.index) { TransitionForm(w, selected.id, selected.transitions[edge!!.index], { t -> editor.updateTransition(selected.id, edge!!.index, t) }, immediate = true) }
                                    Row {
                                        ToolIcon("우선순위 올리기", Icons.Outlined.ArrowUpward, EditingEnabled.current && edge!!.index > 0 && selected.transitions[edge!!.index].`when` != Condition.Otherwise) { editor.moveTransition(selected.id, edge!!.index, edge!!.index - 1) }
                                        ToolIcon("우선순위 내리기", Icons.Outlined.ArrowDownward, EditingEnabled.current && edge!!.index < selected.transitions.lastIndex && selected.transitions[edge!!.index + 1].`when` != Condition.Otherwise && selected.transitions[edge!!.index].`when` != Condition.Otherwise) { editor.moveTransition(selected.id, edge!!.index, edge!!.index + 1) }
                                        TextButton({ editor.deleteTransition(selected.id, edge!!.index) }, enabled = EditingEnabled.current, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("연결 삭제") }
                                    }
                                } else if (selected != null) {
                                    StepPanel(editor, w, selected, { confirmation = EditorConfirmation("${selected.id} 노드와 다음 연결을 삭제합니다. 자동 우회 연결은 만들지 않습니다.\n${editor.deletionImpact(selected.id).joinToString("\n")}", "삭제", true) { editor.deleteNode(selected.id) } }, { connect = selected.id to "end" }, issueFocus?.takeIf { it.stepId == selected.id })
                                } else if (node == "start") {
                                    Text("시작 연결")
                                    EditorChoice("start", w.start, w.steps.map { it.id }) { editor.setStart(it) }
                                } else { Text(if (node in listOf("end", "ask")) "$node · 출력 연결 없는 특수 노드" else "노드나 화살표를 선택하세요.") }
                                Text("화살표는 배열 우선순위대로 하나씩 평가합니다. 매칭이 없으면 사용자 확인 대기합니다.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    EditorTab.GENERAL -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).widthIn(max = 800.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionCard("기본 정보") {
                        EditorField("이름", w.name, { value -> editor.edit("name") { it.copy(name = value) } })
                        }
                        SectionCard("저장소") {
                        OutlinedTextField(w.repoPath, { value -> editor.edit("repoPath") { it.copy(repoPath = value) } }, label = { Text("저장소 경로") }, enabled = EditingEnabled.current, singleLine = true, modifier = Modifier.trackTextInputFocus().fillMaxWidth(), trailingIcon = { ToolIcon("저장소 폴더 선택", Icons.Outlined.FolderOpen, EditingEnabled.current && editor.dialogs != null) { work { editor.dialogs?.directory()?.let { path -> editor.edit { it.copy(repoPath = path) } } } } })
                        Text("실행은 열린 저장소 ${editor.repository}에서 검증합니다.", style = MaterialTheme.typography.bodySmall)
                        EditorField("기준 브랜치", w.baseBranch, { value -> editor.edit("baseBranch") { it.copy(baseBranch = value) } })
                        EditorField("worktreeRoot (기본: ../저장소명.worktrees)", w.worktreeRoot, { value -> editor.edit("worktreeRoot") { it.copy(worktreeRoot = value) } })
                        }
                        SectionCard("실행 제한") {
                        EditorChoice("시작 단계", w.start, listOf("") + w.steps.map { it.id }) { value -> editor.setStart(value) }
                        PositiveField("최대 단계 방문", w.maxSteps, false) { value -> editor.edit("maxSteps") { it.copy(maxSteps = value ?: 0) } }
                        }
                    }
                    else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefinitionsPanel(editor, w) { title, action -> confirmation = EditorConfirmation(title, "삭제", true, action) }
                    }
                }
            }
            TextButton({ showIssues = !showIssues }) { Text("이슈 ${issues.size} · ${if (showIssues) "접기" else "펼치기"}") }
            if (showIssues) Column(Modifier.fillMaxWidth().heightIn(max = 130.dp).verticalScroll(rememberScrollState())) {
                issues.forEach { issue -> SelectionItem(issue.message, "${if (issue.severity == Severity.ERROR) "오류" else "경고"} · ${issue.stepId.orEmpty()} ${issue.transitionIndex?.let { "#${it + 1}" }.orEmpty()}", icon = if (issue.severity == Severity.ERROR) Icons.Outlined.ErrorOutline else Icons.Outlined.WarningAmber) {
                    if (!beforeSelection()) return@SelectionItem
                    issueFocus = issue; tab = EditorTab.GRAPH; issue.stepId?.let { id -> if (issue.transitionIndex != null) editor.selectEdge(id, issue.transitionIndex) else editor.selectNode(id); focus = id to ((focus?.second ?: 0) + 1) }
                } }
                Text("세션 생성 경로 검증은 정적 검사입니다. new/resetSession 성공은 실행 시 확인합니다.", style = MaterialTheme.typography.bodySmall)
            }
        } else Box(Modifier.weight(1f)) { EmptyState("워크플로를 만드세요", "빈 초안이나 템플릿으로 시작할 수 있습니다.", "새 워크플로") { picker = "new" } }
        SnackbarHost(snackbar)
    }
    if (pending != null) AlertDialog(onDismissRequest = { pending = null }, title = { Text("저장하지 않은 편집 내용") }, text = { Text("초안을 저장하거나 변경을 버린 뒤 이동하세요.") }, confirmButton = { Button({ val action = pending; work(after = { pending = null; action?.invoke() }) { editor.preserveDraftOnShutdown() } }, enabled = !busy) { Text("저장 후 이동") } }, dismissButton = { Row { TextButton({ val action = pending; editor.discard(); pending = null; action?.invoke() }) { Text("버리기") }; TextButton({ pending = null }) { Text("취소") } } })
    confirmation?.let { confirmationState -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("확인") }, text = { Text(confirmationState.message) }, confirmButton = { Button({ confirmation = null; confirmationState.action() }, colors = ButtonDefaults.buttonColors(containerColor = if (confirmationState.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(confirmationState.confirmLabel) } }, dismissButton = { TextButton({ confirmation = null }) { Text("취소") } }) }

    warnings?.let { list -> AlertDialog(onDismissRequest = { warnings = null }, title = { Text("초안 저장됨 · 검증 경고") }, text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { list.forEach { Text("${it.stepId.orEmpty()} · ${it.message}") } } }, confirmButton = { Button({ warnings = null; work { saved(editor.save(true), runAfterSave) } }) { Text("경고 확인·버전 저장") } }, dismissButton = { TextButton({ warnings = null }) { Text("초안만 유지") } }) }
    connect?.let { (source, target) -> AlertDialog(onDismissRequest = { connect = null }, title = { Text("$source → 연결 추가") }, text = { CompositionLocalProvider(EditingEnabled provides true) { Column(Modifier.verticalScroll(rememberScrollState())) { TransitionForm(w!!, source, Transition(Condition.Success, targetOf(target)), { t -> try { editor.addTransition(source, t); connect = null } catch (e: Exception) { editor.message.value = e.message } }, "연결 확정") } } }, confirmButton = {}, dismissButton = { TextButton({ connect = null }) { Text("취소") } }) }
    picker?.let { type -> AlertDialog(modifier = Modifier.width(640.dp), properties = DialogProperties(usePlatformDefaultWidth = false), onDismissRequest = { picker = null }, title = { Text(when (type) { "new" -> "새 워크플로"; "open" -> "초안 열기"; "versions" -> "버전 기록"; "copy" -> "다른 이름으로 복제"; "import" -> "YAML 가져오기"; else -> "단독 YAML 내보내기" }) }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { when (type) {
            "new" -> WorkflowTemplate.entries.forEach { template -> OutlinedCard(onClick = { editor.newWorkflow(template); picker = null }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(when(template) { WorkflowTemplate.EMPTY -> "빈 초안"; WorkflowTemplate.LINEAR -> "선형 3단계 (로컬 셸)"; WorkflowTemplate.EXAMPLE -> "계획서 예시" }); Text(if (template == WorkflowTemplate.EMPTY) "직접 노드와 연결을 구성하세요" else "단계와 연결을 편집해 시작하세요", style = MaterialTheme.typography.bodySmall) } } }
            "open" -> { if (drafts.isEmpty()) Text("저장된 초안 없음"); drafts.forEach { d -> SelectionItem(d.workflow.name, d.workflowId) { work { editor.open(d.workflowId); picker = null } } } }
            "versions" -> { if (versions.isEmpty()) Text("저장 버전 없음"); versions.reversed().forEach { v -> SelectionItem(v.versionId.take(8), "${v.createdAt} · 복원: ${v.restoredFrom ?: "없음"}") { editor.showVersion(v); picker = null } } }
            "copy" -> { OutlinedTextField(copyName, { copyName = it }, modifier = Modifier.trackTextInputFocus(), label = { Text("이름") }); Button({ editor.duplicateWorkflow(copyName); picker = null }) { Text("복제") } }
            "import" -> {
                OutlinedTextField(importPath, { importPath = it }, modifier = Modifier.trackTextInputFocus(), label = { Text("YAML 경로") })
                TextButton({ work { editor.dialogs?.openYaml()?.let { editor.importYaml(it); picker = null } } }, enabled = editor.dialogs != null) { Text("파일 선택") }
                Button({ work { editor.importYaml(importPath); picker = null } }, enabled = importPath.isNotBlank()) { Text("가져오기") }
            }
            else -> {
                OutlinedTextField(exportPath, { exportPath = it }, modifier = Modifier.trackTextInputFocus(), label = { Text("저장 경로") })
                TextButton({ work { editor.dialogs?.saveYaml("workflow.yaml")?.let { editor.exportYaml(it); picker = null } } }, enabled = editor.dialogs != null) { Text("파일 선택·저장") }
                Button({ work { editor.exportYaml(exportPath); picker = null } }, enabled = exportPath.isNotBlank()) { Text("내보내기") }
            }
        } }
    }, confirmButton = {}, dismissButton = { TextButton({ picker = null }) { Text("닫기") } }) }
}

@Composable
internal fun EditorField(label: String, value: String, change: (String) -> Unit, lines: Int = 1, alwaysEnabled: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = alwaysEnabled || EditingEnabled.current, singleLine = lines == 1, minLines = lines, modifier = Modifier.trackTextInputFocus().fillMaxWidth())
}
@Composable
internal fun EditorChoice(label: String, value: String, choices: List<String>, change: (String) -> Unit) {
    SelectField(label, value, choices, EditingEnabled.current, change)
}
@Composable
private fun PositiveField(label: String, value: Int?, optional: Boolean, change: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val valid = (optional && text.isBlank()) || text.toIntOrNull()?.let { it > 0 } == true
    OutlinedTextField(text, { text = it; change(if (optional && it.isBlank()) null else it.toIntOrNull() ?: 0) }, label = { Text(label) }, isError = !valid, supportingText = { if (!valid) Text("양의 Int를 입력하세요") }, enabled = EditingEnabled.current, singleLine = true, modifier = Modifier.trackTextInputFocus().fillMaxWidth())
}

@Composable
private fun StepPanel(vm: EditorViewModel, w: Workflow, s: Step, onDelete: () -> Unit, onAddEdge: () -> Unit, issue: Issue?) {
    val enabled = EditingEnabled.current
    val pendingIdentifiers = LocalPendingIdentifierEdits.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val scriptRequest = remember { BringIntoViewRequester() }; val edgesRequest = remember { BringIntoViewRequester() }
    val timeoutRequest = remember { BringIntoViewRequester() }; val idRequest = remember { BringIntoViewRequester() }
    val sessionRequest = remember { BringIntoViewRequester() }; val completionRequest = remember { BringIntoViewRequester() }
    LaunchedEffect(issue, s.id) {
        if (issue != null) {
            androidx.compose.runtime.withFrameNanos { }
            val text = issue.message.lowercase()
            when {
                "script" in text -> scriptRequest
                "transition" in text || "unmatched" in text -> edgesRequest
                "timeout" in text -> timeoutRequest
                "session" in text || "resume" in text -> sessionRequest
                "check" in text -> completionRequest
                else -> idRequest
            }.bringIntoView()
        }
    }
    Text("단계 속성", style = MaterialTheme.typography.titleMedium)
    SectionCard("기본 정보") {
    Box(Modifier.bringIntoViewRequester(idRequest)) { CommitTextField("단계 ID", s.id, enabled, validate = { name -> if (!WorkflowValidator.SAFE_NAME.matches(name) || name in GraphLayout.special || w.steps.any { it.id == name && it.id != s.id }) "중복·예약어 또는 잘못된 ID" else null }) { vm.renameNode(s.id, it) } }
    EditorField("제목", s.title.orEmpty(), { value -> vm.updateStep(s.id, "title") { it.copy(title = value) } })
    Row { TextButton({ vm.setStart(s.id) }, enabled = enabled) { Text("시작 단계로 지정") }; TextButton({ vm.duplicateNode(s.id) }, enabled = enabled) { Text("복제") }; TextButton(onDelete, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") } }
    SegmentedChoice(StepKind.entries, s.effectiveKind, { if (it == StepKind.AGENT) "에이전트" else "셸" }, enabled) { vm.setKind(s.id, it) }
    }
    SectionCard("스크립트") {
    Box(Modifier.bringIntoViewRequester(scriptRequest)) { ScriptEditor(s.script, { vm.setScript(s.id, it) }, s.effectiveKind == StepKind.SHELL, enabled) }
    }
    SectionCard("실행 환경") {
    if (s.effectiveKind == StepKind.SHELL) {
        WorkspaceChoice(w, s.workspace ?: Workspace.Local) { value -> vm.updateStep(s.id) { it.copy(workspace = value) } }
        Text("같은 명령 1회 재실행", style = MaterialTheme.typography.bodySmall)
        if (listOf("rm -rf", "--force", "push -f", "reset --hard").any { it in s.shellScript }) Text("위험 패턴 포함 (차단하지 않음)", color = MaterialTheme.colorScheme.error)
    } else {
        Box(Modifier.bringIntoViewRequester(sessionRequest)) { EditorChoice("세션", s.session?.ref.orEmpty(), w.sessions.keys.toList()) { ref -> vm.updateStep(s.id) { it.copy(session = SessionRef(ref, it.session?.mode ?: SessionMode.NEW), workspace = null) } } }
        SegmentedChoice(SessionMode.entries, s.session?.mode ?: SessionMode.NEW, { if (it == SessionMode.NEW) "새 세션" else "이어가기" }, enabled) { mode -> vm.updateStep(s.id) { it.copy(session = SessionRef(it.session?.ref.orEmpty(), mode), workspace = null) } }
        val session = w.sessions[s.session?.ref]
        Text("세션 ${s.session?.ref ?: "?"} → ${session?.workspace ?: "workspace 없음"} (읽기 전용)", style = MaterialTheme.typography.bodySmall)
        ComboField("모델 · 직접 입력 또는 후보 선택", s.model.orEmpty(), session?.let { vm.modelCandidates(it.provider) }.orEmpty(), enabled) { value -> vm.updateStep(s.id, "model") { it.copy(model = value.ifBlank { null }) } }
        session?.let { definition ->

            val cliVersions by vm.cliVersions.collectAsState()
            val cache = if (definition.provider == Provider.ANTIGRAVITY) vm.settings.agyModelsCache else null
            cache?.let { Text("${it.binaryPath} · ${it.binaryVersion}\n${it.queriedAt}", style = MaterialTheme.typography.labelSmall) }
            EditorChoice("effort", s.effort?.name.orEmpty(), listOf("") + Effort.entries.map { "${it.name} · ${vm.effortStatus(definition.provider, s.model, it, cliVersions[definition.provider] ?: cache?.binaryVersion)}" }) { value -> vm.updateStep(s.id) { it.copy(effort = value.substringBefore(' ').takeIf { it.isNotBlank() }?.let(Effort::valueOf)) } }
            Text("Codex CLI 버전은 프리플라이트에서 확인합니다. 실측 기록이 없는 버전·모델·effort도 실행할 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        }
        ExpandableSection("재시도 스크립트") { OutlinedTextField(s.retryScript.orEmpty(), { value -> vm.updateStep(s.id, "retryScript") { it.copy(retryScript = value.ifBlank { null }) } }, label = { Text("retryScript") }, placeholder = { Text("Retry the current task. Previous attempt failed: …; exit code: …; completion: … (기본 문구)") }, minLines = 3, enabled = enabled, modifier = Modifier.trackTextInputFocus()) }
    }
    }
    SectionCard("완료 조건과 제한") {
    val completion = when(s.completion) { is Completion.FileExists -> "fileExists"; is Completion.Command -> "command"; else -> "없음" }
    Box(Modifier.bringIntoViewRequester(completionRequest)) { EditorChoice("완료 확인", completion, listOf("없음", "fileExists", "command")) { type -> vm.updateStep(s.id) { it.copy(completion = when(type) { "fileExists" -> Completion.FileExists(""); "command" -> Completion.Command(""); else -> null }) } } }
    when (val c = s.completion) { is Completion.FileExists -> EditorField("완료 파일 경로", c.path, { value -> vm.updateStep(s.id) { it.copy(completion = Completion.FileExists(value)) } }); is Completion.Command -> EditorField("완료 명령", c.cmd, { value -> vm.updateStep(s.id) { it.copy(completion = Completion.Command(value)) } }, 3); else -> Unit }
    Box(Modifier.bringIntoViewRequester(timeoutRequest)) { PositiveField("본문·출력 수신 timeoutSec (선택)", s.timeoutSec, true) { value -> vm.updateStep(s.id, "timeoutSec") { it.copy(timeoutSec = value) } } }
    PositiveField("각 검사 checkTimeoutSec", s.checkTimeoutSec, false) { value -> vm.updateStep(s.id, "checkTimeoutSec") { it.copy(checkTimeoutSec = value ?: 0) } }
    ExpandableSection("검사 조건 도움말") { Text("전이 command: 0=매칭, 1=불일치, 2 이상=검사 오류·사용자 대기. completion command: 정상 non-zero=미충족. fileContains는 전체 문자열 포함 검사입니다.", style = MaterialTheme.typography.bodySmall) }
    }
    SectionCard("나가는 전이") { Column(Modifier.bringIntoViewRequester(edgesRequest), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("나가는 전이 · 평가 우선순위")
    if (s.transitions.isEmpty()) Text("연결을 추가하세요. 종료하려면 end에 연결하세요.", color = MaterialTheme.colorScheme.error)
    s.transitions.forEachIndexed { i, t -> SelectionItem("${i + 1} · ${conditionName(t.`when`)} → ${targetId(t.next)}") { if (pendingIdentifiers?.commit() != false) { focusManager.clearFocus(); vm.selectEdge(vm.selectedNode.value ?: s.id, i) } } }
    FilledTonalButton(onAddEdge, enabled = enabled) { Text("연결 추가") }
    } }
}

@Composable
private fun TransitionForm(w: Workflow, source: String, initial: Transition, apply: (Transition) -> Unit, label: String = "연결 확정", immediate: Boolean = false) {
    var transition by remember(source, initial) { mutableStateOf(initial) }
    var limit by remember(source) { mutableStateOf(initial.maxVisits?.toString().orEmpty()) }
    var error by remember(source, initial) { mutableStateOf<String?>(null) }
    LaunchedEffect(initial.maxVisits) {
        val pendingLimit = if (limit.isBlank()) null else limit.toIntOrNull() ?: 0
        if (initial.maxVisits != pendingLimit) limit = initial.maxVisits?.toString().orEmpty()
    }
    fun change(next: Transition) { try { if (immediate) apply(next); transition = next; error = null } catch (e: Exception) { error = e.message ?: "연결을 확인하세요" } }
    val condition = conditionName(transition.`when`)
    EditorChoice("조건", condition, listOf("success", "failure", "otherwise", "fileExists", "fileContains", "command")) { type -> change(transition.copy(`when` = when(type) { "success" -> Condition.Success; "failure" -> Condition.Failure; "otherwise" -> Condition.Otherwise; "fileExists" -> Condition.FileExists(""); "fileContains" -> Condition.FileContains("", ""); else -> Condition.Command("") })) }
    when (val c = transition.`when`) {
        is Condition.Command -> EditorField("검사 명령", c.cmd, { change(transition.copy(`when` = c.copy(cmd = it))) }, 3)
        is Condition.FileExists -> EditorField("파일 경로", c.path, { change(transition.copy(`when` = c.copy(path = it))) })
        is Condition.FileContains -> { EditorField("파일 경로", c.path, { change(transition.copy(`when` = c.copy(path = it))) }); EditorField("포함 문자열", c.text, { change(transition.copy(`when` = c.copy(text = it))) }, 2) }
        else -> Unit
    }
    val target = targetId(transition.next)
    EditorChoice("대상", target, w.steps.map { it.id } + listOf("end", "ask")) { id -> change(transition.copy(next = targetOf(id), resetSession = transition.resetSession && w.steps.firstOrNull { it.id == id }?.effectiveKind == StepKind.AGENT)) }
    val validLimit = limit.isBlank() || limit.toIntOrNull()?.let { it > 0 } == true
    OutlinedTextField(limit, { limit = it; change(transition.copy(maxVisits = if (it.isBlank()) null else it.toIntOrNull() ?: 0)) }, label = { Text("최대 방문 횟수 (선택)") }, isError = !validLimit, supportingText = { if (!validLimit) Text("양의 Int를 입력하세요") }, enabled = EditingEnabled.current, singleLine = true, modifier = Modifier.trackTextInputFocus().fillMaxWidth())
    Row { Checkbox(transition.resetSession, { change(transition.copy(resetSession = it)) }, enabled = EditingEnabled.current && w.steps.firstOrNull { it.id == target }?.effectiveKind == StepKind.AGENT); Text("대상 세션 초기화", Modifier.padding(top = 12.dp)) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!immediate) Button({ apply(transition) }, enabled = EditingEnabled.current && validLimit) { Text(label) }
}

@Composable
private fun WorkspaceChoice(w: Workflow, value: Workspace, change: (Workspace) -> Unit) = EditorChoice("workspace", if (value is Workspace.Worktree) "worktree:${value.name}" else "local", listOf("local") + w.worktrees.map { "worktree:${it.name}" }) { change(if (it == "local") Workspace.Local else Workspace.Worktree(it.removePrefix("worktree:"))) }

@Composable
private fun DefinitionsPanel(vm: EditorViewModel, w: Workflow, confirm: (String, () -> Unit) -> Unit) {
    val enabled = EditingEnabled.current
    Text("워크트리", style = MaterialTheme.typography.titleMedium)
    w.worktrees.forEach { wt ->
        SectionCard("워크트리 ${wt.name}") {

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CommitTextField("워크트리 이름", wt.name, enabled, validate = { name -> if (!WorkflowValidator.SAFE_NAME.matches(name) || w.worktrees.any { it.name == name && it.name != wt.name }) "중복 또는 잘못된 이름" else null }) { vm.renameWorktree(wt.name, it) } }
            Box(Modifier.weight(1f)) { EditorField("branch", wt.branch, { value -> vm.edit("worktree:${wt.name}:branch") { workflow -> workflow.copy(worktrees = workflow.worktrees.map { if (it.name == wt.name) it.copy(branch = value) else it }) } }) }
            TextButton({ confirm("워크트리 ${wt.name} 삭제 · 참조 단계: ${vm.worktreeReferences(wt.name).joinToString()}. 참조가 남으면 실행할 수 없습니다.") { vm.deleteWorktree(wt.name) } }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
        }
        }
    }
    TextButton({ var name = "worktree"; var i = 2; while (w.worktrees.any { it.name == name }) name = "worktree-${i++}"; vm.edit { it.copy(worktrees = it.worktrees + WorktreeDef(name, "ai/$name")) } }, enabled = enabled) { Text("+ 워크트리") }
    HorizontalDivider(); Text("세션", style = MaterialTheme.typography.titleMedium)
    w.sessions.forEach { (key, session) ->
        SectionCard("세션 $key") {

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { CommitTextField("세션 이름", key, enabled, validate = { name -> if (!WorkflowValidator.SAFE_NAME.matches(name) || (name != key && name in w.sessions)) "중복 또는 잘못된 이름" else null }) { vm.renameSession(key, it) } }
            Box(Modifier.weight(1f)) { EditorChoice("provider", session.provider.name, Provider.entries.map { it.name }) { value -> vm.edit { it.copy(sessions = it.sessions + (key to session.copy(provider = Provider.valueOf(value)))) } } }
            Box(Modifier.weight(1f)) { WorkspaceChoice(w, session.workspace) { value -> vm.edit { it.copy(sessions = it.sessions + (key to session.copy(workspace = value))) } } }
            TextButton({ confirm("세션 $key 삭제 · 참조 단계: ${vm.sessionReferences(key).joinToString()}. 참조가 남으면 실행할 수 없습니다.") { vm.deleteSession(key) } }, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("삭제") }
        }
        }
    }
    TextButton({ var name = "session"; var i = 2; while (name in w.sessions) name = "session-${i++}"; vm.edit { it.copy(sessions = it.sessions + (name to SessionDef(Provider.CODEX, Workspace.Local))) } }, enabled = enabled) { Text("+ 세션") }
}

private class ShellHighlight(val comment: Color, val string: Color, val keyword: Color) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val highlighted = buildAnnotatedString { append(source); Regex("#[^\\n]*|'[^']*'|\"[^\"]*\"|\\b(if|then|else|fi|for|do|done|case|esac|set|exit|export)\\b").findAll(source).forEach { match -> addStyle(SpanStyle(color = when { match.value.startsWith('#') -> comment; match.value.startsWith('\'') || match.value.startsWith('"') -> string; else -> keyword }), match.range.first, match.range.last + 1) } }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }
}
@Composable
private fun ScriptEditor(script: String, change: (String) -> Unit, shell: Boolean, enabled: Boolean) {
    var value by remember { mutableStateOf(TextFieldValue(script)) }
    LaunchedEffect(script) { if (script != value.text) value = value.copy(text = script, selection = TextRange(value.selection.start.coerceAtMost(script.length), value.selection.end.coerceAtMost(script.length))) }
    Row(Modifier.fillMaxWidth().heightIn(min = 230.dp).border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small).padding(8.dp)) {
        Text((1..maxOf(12, script.count { it == '\n' } + 1)).joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
        BasicTextField(value, { value = it; change(it.text) }, enabled = enabled, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface), visualTransformation = if (shell) ShellHighlight(LocalStatusColors.current.comment, LocalStatusColors.current.string, MaterialTheme.colorScheme.primary) else VisualTransformation.None,
            modifier = Modifier.trackTextInputFocus().weight(1f).onPreviewKeyEvent { event ->
                if (enabled && event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                    val range = value.selection; val next = value.text.replaceRange(range.min, range.max, "\t"); value = TextFieldValue(next, TextRange(range.min + 1)); change(next); true
                } else false
            })
    }
}
