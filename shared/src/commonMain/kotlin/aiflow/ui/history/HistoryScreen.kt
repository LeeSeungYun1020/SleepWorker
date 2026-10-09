package aiflow.ui.history

import aiflow.engine.*
import aiflow.storage.WorkflowCodec
import aiflow.ui.run.RunViewModel
import aiflow.ui.run.RunStatusBadge
import aiflow.ui.run.visitAttemptLabel
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(vm: RunViewModel, onEditor: () -> Unit) {
    val controller by vm.historyController.collectAsState()
    val runs by vm.history.collectAsState()
    val busy by vm.busy.collectAsState()
    val live by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val repository by vm.repository.collectAsState()
    val history = controller
    if (history == null) { Text("실행 또는 편집 화면에서 저장소를 먼저 여세요.", Modifier.padding(24.dp)); return }
    val run by history.selected.collectAsState()
    val files by history.artifacts.collectAsState()
    val file by history.file.collectAsState()
    val text by history.text.collectAsState()
    val worktrees by history.worktrees.collectAsState()
    val ignored by history.ignored.collectAsState()
    val versions by vm.versions.collectAsState()
    val active = live?.status?.let { !it.terminal } == true && vm.active
    val clipboard = LocalClipboardManager.current
    var confirmation by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<WorktreeEntry?>(null) }
    var visitNo by remember(run?.runId) { mutableStateOf<Int?>(null) }
    var fileFilter by remember(run?.runId) { mutableStateOf("") }
    var snapshot by remember(run?.runId) { mutableStateOf(false) }
    LaunchedEffect(controller, active) { if (!active) vm.refreshHistory() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { Text("실행 히스토리", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium); OutlinedButton({ vm.refreshHistory() }, enabled = !busy && !active) { Text("새로고침") } }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (ignored == false) Text(".aiflow/runs/가 Git ignore 규칙에 없습니다. 필요하면 직접 .gitignore에 추가하세요.")
        if (active) Text("실행 종료 후 기록과 워크트리를 관리할 수 있습니다.")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(runs, key = { it.runId }) { item -> Card(onClick = { vm.selectHistory(item) }, enabled = !busy && !active) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(item.workflow.name); Text(item.runId, style = MaterialTheme.typography.labelSmall); RunStatusBadge(item.status)
                    Text("버전 ${item.versionId.take(8)} · 방문 ${item.visits.size}")
                    Text("시작: ${item.startedAt ?: "런 시작 미관측 · 첫 방문 ${item.visits.firstOrNull()?.startedAt ?: "없음"}"}\n종료: ${if (item.status == RunStatus.INTERRUPTED) "실제 시각 알 수 없음" else if (item.status.terminal) item.lastUpdatedAt else "미완료"}", style = MaterialTheme.typography.bodySmall)
                } } }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                run?.let { selected ->
                    Text("${selected.workflow.name} · ${selected.versionId}")
                    if (selected.status == RunStatus.INTERRUPTED) Text("중단된 실행 · 직전 ${selected.previousStatus}\n마지막 기록 ${selected.previousUpdatedAt}\n사유 ${selected.interruptionReason} · 발견 ${selected.interruptedAt}\n실제 종료 시각과 외부 프로세스 상태는 알 수 없습니다. 로그가 일부만 남았을 수 있습니다. 버전을 선택해 새로 실행하세요.")
                    selected.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val source = versions.firstOrNull { it.workflowId == selected.workflowId && it.versionId == selected.versionId }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ snapshot = !snapshot }) { Text("실행 당시 스냅샷") }
                        TextButton({ vm.openHistoryVersion(false); onEditor() }, enabled = source != null && !busy && !active) { Text("사용한 버전 보기") }
                        TextButton({ confirmation = if (source != null) "restore" else "import" }, enabled = !busy && !active) { Text(if (source != null) "이 버전으로 복원" else "스냅샷을 새 워크플로로 가져오기") }
                        TextButton({ confirmation = "delete" }, enabled = selected.status.terminal && !busy && !active) { Text("런 기록 삭제") }
                    }
                    if (source == null) Text("원본 버전 파일 없음 — 런 내부 스냅샷을 표시합니다.")
                    if (snapshot) SelectionContainer { Text(WorkflowCodec().encode(selected.workflow), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                    selected.visits.forEach { visit ->
                        OutlinedCard(onClick = { visitNo = if (visitNo == visit.visitNo) null else visit.visitNo }) { Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("#${visit.visitNo} ${visit.stepId} · ${visit.status} · ${visitAttemptLabel(visit)}")
                            Text("${visit.startedAt} → ${visit.endedAt ?: "종료 미관측"}", style = MaterialTheme.typography.bodySmall)
                            visit.transitionTaken?.let { Text("우선순위 ${it.index + 1} · ${it.condition} → ${it.target}") }
                            visit.manualRetryOf?.let { retry -> TextButton({ visitNo = retry }) { Text("수동 재방문 · 원래 방문 #$retry") } }
                            if (visitNo == visit.visitNo) {
                                Text("원래 결과: ${visit.result?.let { if (it.success) "성공" else "실패" } ?: "미확정"} · 종료코드 ${visit.result?.exitCode ?: "미관측"} · ${visit.result?.termination ?: "미관측"}\n사용자 판정: ${visit.controls.joinToString { "${it.action} · ${it.timestamp}" }.ifBlank { "없음" }}", style = MaterialTheme.typography.bodySmall)
                                visit.conditionEvaluations.forEach { (index, check) -> Text("조건 ${index + 1}: ${historyCheckLabel(check)}", style = MaterialTheme.typography.bodySmall) }
                                visit.attempts.forEach { attempt ->
                                    Text("시도 ${attempt.attemptNo} · ${attempt.status}\n${attempt.metadata?.let { "실행 파일: ${it.binaryPath}\n버전: ${it.binaryVersion ?: "알 수 없음"} · 계약: ${it.contractId ?: "알 수 없음"}\n요청 모델: ${it.requestedModel ?: "없음"} · effort: ${it.requestedEffort ?: "없음"}" } ?: "실행 메타데이터 미관측"}\n종료코드 ${attempt.result?.exitCode ?: "알 수 없음"} · 종료 ${attempt.result?.termination ?: "미관측"}\n${attempt.result?.failure ?: attempt.failureDetail.orEmpty()}\n정리 오류: ${attempt.result?.cleanupError ?: "없음"}\n완료 확인: ${historyCheckLabel(attempt.result?.completionResult)}\nusage: ${attempt.result?.providerReport?.usage ?: "알 수 없음"}\n실제 모델 / 비용: 알 수 없음", style = MaterialTheme.typography.bodySmall)
                                    attempt.retrySkippedReason?.let { Text("자동 재시도 불가: $it") }
                                    attempt.sessionId?.let { id -> TextButton({ clipboard.setText(AnnotatedString(id)) }) { Text("세션 ID 복사 · $id") } }
                                    resumeCommand(selected, visit, attempt)?.let { command -> TextButton({ clipboard.setText(AnnotatedString(command)) }) { Text("터미널 resume 명령 복사 · 지시 문구를 수정 후 실행") } }
                                }
                            }
                        } }
                    }
                    Text("기록 파일 — 선택 시 지연 로드", style = MaterialTheme.typography.titleMedium)
                    val prefix = visitNo?.let { number -> selected.visits.first { it.visitNo == number }.let { "visits/$number-${it.stepId}/" } }
                    OutlinedTextField(fileFilter, { fileFilter = it }, label = { Text("파일명 검색 · stdout / script / result / checks") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    LazyColumn(Modifier.fillMaxWidth().height(180.dp)) {
                        items(files.filter { (prefix == null || it.startsWith(prefix)) && it.contains(fileFilter, true) }, key = { it }) { relative ->
                            TextButton({ vm.readHistoryFile(relative) }, enabled = !busy && !active) { Text(relative) }
                        }
                    }
                    file?.let { Text("$repository/.aiflow/runs/${selected.runId}/$it"); SelectionContainer { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                }
                Text("워크트리", style = MaterialTheme.typography.titleMedium)
                worktrees.forEach { entry -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${entry.path}\n${entry.branch ?: "detached / bare"}${if (entry.locked) " · 잠금" else ""}", Modifier.weight(1f))
                    OutlinedButton({ deletion = entry }, enabled = !entry.main && !entry.locked && !busy && !active) { Text("삭제") }
                } }
            }
        }
    }
    confirmation?.let { operation -> AlertDialog(onDismissRequest = { confirmation = null }, title = { Text(when(operation) { "delete" -> "런 기록 삭제"; "restore" -> "버전을 초안으로 복원"; else -> "새 워크플로로 가져오기" }) }, text = { Text(if (operation == "delete") "선택한 런 디렉터리를 삭제합니다. 그래프 버전과 작업 파일은 유지됩니다." else "현재 편집 내용은 초안으로 저장합니다. 그래프를 초안에 적용하며 런 재개나 작업 파일 되돌리기는 수행하지 않습니다.") }, confirmButton = { Button({ if (operation == "delete") vm.deleteHistory() else { vm.openHistoryVersion(operation == "restore", operation == "import"); onEditor() }; confirmation = null }, enabled = !busy && !active) { Text("확인") } }, dismissButton = { TextButton({ confirmation = null }) { Text("취소") } }) }
    deletion?.let { entry -> AlertDialog(onDismissRequest = { deletion = null }, title = { Text("워크트리 삭제") }, text = { Text("${entry.path}\n이 경로의 세션 resume이 불가능해집니다. 브랜치는 삭제하지 않습니다. 변경 사항이 있는 워크트리는 Git이 삭제를 거부합니다.") }, confirmButton = { Button({ vm.removeHistoryWorktree(entry); deletion = null }, enabled = !busy && !active) { Text("삭제") } }, dismissButton = { TextButton({ deletion = null }) { Text("취소") } }) }
}

private fun historyCheckLabel(check: CheckRecord?): String {
    if (check == null) return "기록 없음"
    val result = when (val value = check.result) { CheckResult.Met -> "Met"; CheckResult.NotMet -> "NotMet"; is CheckResult.Error -> "Error · ${value.reason}" }
    return result + check.command?.let { " · 종료코드 ${it.exitCode ?: "미관측"} · ${it.elapsedMs}ms · ${it.termination}" }.orEmpty()
}
