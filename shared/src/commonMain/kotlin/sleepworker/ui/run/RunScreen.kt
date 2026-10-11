package sleepworker.ui.run

import sleepworker.engine.*
import sleepworker.model.*
import sleepworker.provider.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RunScreen(vm: RunViewModel) {
    val versions by vm.versions.collectAsState()
    val selected by vm.selected.collectAsState()
    val report by vm.report.collectAsState()
    val state by vm.state.collectAsState()
    val logs by vm.logs.collectAsState()
    val truncated by vm.truncated.collectAsState()
    val history by vm.history.collectAsState()
    val pendingImport by vm.pendingImport.collectAsState()
    val importWarnings by vm.importWarnings.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val repository by vm.repository.collectAsState()
    var repo by remember { mutableStateOf("") }
    LaunchedEffect(repository) { if (repository != null) repo = repository!! }
    var showTimeline by remember { mutableStateOf(true) }
    var yaml by remember { mutableStateOf("") }
    var versionMenu by remember { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf(false) }
    var abortConfirm by remember { mutableStateOf(false) }
    var selectedVisit by remember(state?.runId) { mutableStateOf<Int?>(null) }
    var selectedAttempt by remember(selectedVisit, state?.runId) { mutableStateOf<Int?>(null) }
    var showPreflight by remember(state?.runId) { mutableStateOf(false) }
    var phase by remember(selectedVisit) { mutableStateOf("전체") }
    var stderr by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    val active = state?.status?.let { !it.terminal } == true
    LaunchedEffect(state?.runId, active) {
        if (active) {
            now = Clock.System.now()
            while (true) { delay(1000); now = Clock.System.now() }
        }
    }
    val visit = state?.visits?.firstOrNull { it.visitNo == selectedVisit } ?: state?.visits?.lastOrNull()
    val step = state?.workflow?.steps?.firstOrNull { it.id == visit?.stepId }
    val attempt = visit?.attempts?.firstOrNull { it.attemptNo == selectedAttempt } ?: visit?.attempts?.lastOrNull()
    val clipboard = LocalClipboardManager.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val compact = maxWidth < 840.dp
    Column(Modifier.fillMaxSize().padding(if (compact) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(repo, { repo = it }, label = { Text("저장소 절대 경로") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedButton({ vm.openRepository(repo) }, enabled = !active && !busy && repo.isNotBlank()) { Text("열기") }
            OutlinedTextField(yaml, { yaml = it }, label = { Text("가져올 YAML 경로") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedButton({ vm.importYaml(yaml) }, enabled = repository != null && !active && !busy && yaml.isNotBlank()) { Text("버전 가져오기") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                OutlinedButton({ versionMenu = true }, enabled = !active && !busy) { Text(selected?.workflow?.name ?: "저장 버전 선택") }
                DropdownMenu(versionMenu, { versionMenu = false }) { versions.forEach { v ->
                    DropdownMenuItem(text = { Text("${v.workflow.name} · ${v.versionId.take(8)} · ${v.createdAt}") }, onClick = { vm.select(v); versionMenu = false })
                } }
            }
            FilledTonalButton({ vm.runPreflight() }, enabled = selected != null && !active && !busy) { Text("프리플라이트") }
            Button({ vm.start() }, enabled = report?.passed == true && report?.version == selected && !active && !busy) { Text("새 실행") }
            OutlinedButton({ vm.pause() }, enabled = state?.status == RunStatus.RUNNING) { Text("일시정지") }
            OutlinedButton({ vm.resume() }, enabled = state?.status == RunStatus.PAUSED) { Text("재개") }
            OutlinedButton({ abortConfirm = true }, enabled = active) { Text("강제 중지") }
            Box {
                TextButton({ historyMenu = true }, enabled = !active && !busy) { Text("기록") }
                DropdownMenu(historyMenu, { historyMenu = false }) { history.forEach { run ->
                    DropdownMenuItem(text = { Text("${run.runId} · ${run.status}") }, onClick = { vm.showHistory(run); historyMenu = false })
                } }
            }
        }
        if (versions.isEmpty()) Text("실행 가능한 저장 버전이 없습니다. YAML을 가져오거나 편집기에서 유효한 버전을 저장하세요.")
        selected?.let { Text("workflowId: ${it.workflowId}   versionId: ${it.versionId}", style = MaterialTheme.typography.labelSmall) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        report?.let { r ->
            val collapse = compact && state != null && r.passed
            if (collapse) TextButton({ showPreflight = !showPreflight }) {
                Text(if (showPreflight) "프리플라이트 상세 접기" else "프리플라이트 통과 · 상세 보기")
            }
            if (!collapse || showPreflight) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) { items(r.items) { item ->
                Text("${item.status} · ${item.name}: ${item.detail}", color = if (item.status == PreflightStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            } }
        }
        state?.let { run ->
            val elapsed = runElapsedTime(run.status, run.visits.firstOrNull()?.startedAt, run.lastUpdatedAt, now)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RunStatusBadge(run.status)
                Text("${elapsed.displayText()} · ${run.runId}", style = MaterialTheme.typography.labelLarge)
            }
            when (run.status) {
                RunStatus.PAUSE_REQUESTED -> Text("현재 단계 완료 후 정지합니다")
                RunStatus.PAUSED -> Text("일시정지됨 — 다음: ${(run.visits.lastOrNull()?.decision as? Decision.NextStep)?.id}")
                RunStatus.INTERRUPTED -> Text("중단된 기록입니다. 재개·다시 시도할 수 없습니다. 버전 선택 후 프리플라이트를 거쳐 새 실행하세요.")
                else -> Unit
            }
            run.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        if (compact) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(showTimeline, { showTimeline = true }, label = { Text("방문 목록") })
            FilterChip(!showTimeline, { showTimeline = false }, label = { Text("선택 방문 로그") })
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val timelineState = rememberLazyListState()
            LaunchedEffect(state?.visits?.size, selectedVisit) {
                if (selectedVisit == null && !state?.visits.isNullOrEmpty()) timelineState.scrollToItem(state!!.visits.lastIndex)
            }
            if (!compact || showTimeline) LazyColumn((if (compact) Modifier.weight(1f) else Modifier.width(300.dp)).fillMaxHeight(), state = timelineState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state?.visits.orEmpty(), key = { it.visitNo }) { v ->
                    val definition = state!!.workflow.steps.first { it.id == v.stepId }
                    Card(onClick = { selectedVisit = v.visitNo; selectedAttempt = null; if (compact) showTimeline = false }, colors = CardDefaults.cardColors(containerColor = if (v.visitNo == visit?.visitNo) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("#${v.visitNo} ${if (definition.effectiveKind == StepKind.SHELL) "!" else "Agent"} ${v.stepId} · ${definition.title.orEmpty()}")
                            Text("${v.status} · ${visitElapsedTime(state!!.status, v.startedAt, v.endedAt, now).displayText()} · ${visitAttemptLabel(v)}", color = if (v.status == StepStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            v.manualRetryOf?.let { Text("방문 #$it 수동 재시도") }
                            v.attempts.lastOrNull()?.sessionId?.let { id -> TextButton({ clipboard.setText(AnnotatedString(id)) }) { Text(id) } }
                            v.transitionTaken?.let { Text("${it.index + 1}: ${conditionLabel(it.condition)} → ${targetLabel(it.target)}") }
                        }
                    }
                }
            }
            if (!compact) VerticalDivider()
            if (!compact || !showTimeline) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!stderr, { stderr = false }, label = { Text("stdout") })
                    FilterChip(stderr, { stderr = true }, label = { Text("stderr") })
                    if (step?.effectiveKind == StepKind.AGENT) FilterChip(summary, { summary = !summary }, label = { Text(if (summary) "이벤트 요약" else "원문") })
                    FilterChip(follow, { follow = !follow }, label = { Text("하단 고정") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visit?.attempts?.forEach { a -> FilterChip(a.attemptNo == attempt?.attemptNo, { selectedAttempt = a.attemptNo }, label = { Text("시도 ${a.attemptNo}") }) }
                    listOf("전체", "본문", "완료 확인", "전이 조건").forEach { p -> FilterChip(phase == p, { phase = p }, label = { Text(p) }) }
                }
                attempt?.let { a ->
                    Text("Provider: ${a.result?.providerReport?.outcome ?: if (step?.effectiveKind == StepKind.SHELL) "해당 없음" else if (active) "수신 중" else "미확인"} · 종료코드: ${a.result?.exitCode ?: if (active) "대기" else "미확인"} · ${a.result?.termination ?: if (active) "진행 중" else "기록 없음"}", style = MaterialTheme.typography.bodySmall)
                    a.result?.failure?.let { Text("${it.kind}: ${it.detail}", color = MaterialTheme.colorScheme.error) }
                    a.retrySkippedReason?.let { Text("자동 재시도 불가: $it") }
                }
                if (visit?.visitNo in truncated) Text("앞부분 로그 생략 — 파일에서 전체 보기: $repository/.sleepworker/runs/${state?.runId}/logs.jsonl")
                val visible by produceState<List<LogLine>>(emptyList(), logs, visit?.visitNo, attempt?.attemptNo, phase, stderr) {
                    value = withContext(Dispatchers.Default) {
                        logs.filter { line -> line.visitNo == visit?.visitNo && (line.attemptNo == null || line.attemptNo == attempt?.attemptNo) && line.stream == (if (stderr) Stream.STDERR else Stream.STDOUT) && when (phase) {
                    "완료 확인" -> "completion" in line.phase
                    "전이 조건" -> "transitions" in line.phase
                    "본문" -> "completion" !in line.phase && "transitions" !in line.phase
                    else -> true
                } }
                    }
                }
                val events = visible.filter { it.event != null }.map { it.text }
                val rendered = if (summary && step?.effectiveKind == StepKind.AGENT) events else visible.filter { it.event == null }.map { "[${it.attemptNo ?: "-"} · ${it.phase.substringAfterLast('/')}] ${it.text}" }
                val listState = rememberLazyListState()
                LaunchedEffect(rendered.size, follow, selectedVisit, selectedAttempt) { if (follow && rendered.isNotEmpty()) listState.scrollToItem(rendered.lastIndex) }
                SelectionContainerCompat {
                    LazyColumn(Modifier.fillMaxSize(), state = listState) { items(rendered.size) { index -> Text(rendered[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
    }
    if (pendingImport != null) AlertDialog(onDismissRequest = { vm.cancelImport() }, title = { Text("가져오기 검증 경고") },
        text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) { importWarnings.forEach { Text(it.message + " · " + it.stepId.orEmpty()) } } },
        confirmButton = { Button({ vm.confirmImport() }, enabled = !busy) { Text("경고 확인 · 버전 저장") } },
        dismissButton = { TextButton({ vm.cancelImport() }) { Text("초안만 유지") } })
    if (abortConfirm) AlertDialog(onDismissRequest = { abortConfirm = false }, title = { Text("실행을 강제 중지할까요?") }, text = { Text("본문과 완료·전이 검사 프로세스를 종료합니다. 실행 기록은 보존됩니다.") }, confirmButton = { Button({ vm.abort(); abortConfirm = false }) { Text("강제 중지") } }, dismissButton = { TextButton({ abortConfirm = false }) { Text("취소") } })
    if (state?.status == RunStatus.AWAITING_USER && !abortConfirm) {
        val current = state!!.visits.last()
        AlertDialog(onDismissRequest = {}, title = { Text("사용자 확인 필요") }, text = {
            Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                Text((current.decision as? Decision.Ask)?.reason.orEmpty())
                current.result?.failure?.let { Text("${it.kind}: ${it.detail}") }
                current.attempts.lastOrNull()?.retrySkippedReason?.let { Text("자동 재시도 불가: $it") }
                Text("다시 시도는 원래 스크립트 전체를 새 방문으로 실행합니다. 건너뛰기는 실패 기록을 보존하고 성공으로 간주해 분기합니다. 이미 검사한 외부 조건은 재실행하지 않으며 ask·오류·상한이 남으면 계속 대기합니다.")
                logs.filter { it.visitNo == current.visitNo }.takeLast(8).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { Button({ vm.answer(UserDecision.Retry) }, enabled = state!!.visits.size < state!!.workflow.maxSteps) { Text("다시 시도") } }, dismissButton = {
            Row { TextButton({ vm.answer(UserDecision.Skip) }) { Text("건너뛰기") }; TextButton({ abortConfirm = true }) { Text("중지") } }
        })
    }
}

@Composable
private fun SelectionContainerCompat(content: @Composable () -> Unit) {
    androidx.compose.foundation.text.selection.SelectionContainer(content = content)
}

private fun targetLabel(target: sleepworker.model.Target): String = when (target) {
    is sleepworker.model.Target.StepId -> target.id
    sleepworker.model.Target.End -> "end"
    sleepworker.model.Target.Ask -> "ask"
}
private fun conditionLabel(condition: Condition): String = when (condition) {
    Condition.Success -> "success"
    Condition.Failure -> "failure"
    Condition.Otherwise -> "else"
    is Condition.Command -> "command: ${condition.cmd}"
    is Condition.FileExists -> "file exists: ${condition.path}"
    is Condition.FileContains -> "file contains: ${condition.path} / ${condition.text}"
}

@Composable
fun RunStatusBadge(status: RunStatus) {
    val label = when (status) {
        RunStatus.IDLE -> "대기"
        RunStatus.PREFLIGHT -> "사전 검사 중"
        RunStatus.RUNNING -> "실행 중"
        RunStatus.PAUSE_REQUESTED -> "일시정지 예약"
        RunStatus.PAUSED -> "일시정지"
        RunStatus.AWAITING_USER -> "확인 필요"
        RunStatus.COMPLETED -> "✓ 완료"
        RunStatus.FAILED -> "! 실패"
        RunStatus.ABORTED -> "중지됨"
        RunStatus.INTERRUPTED -> "중단된 기록"
    }
    val colors = MaterialTheme.colorScheme
    val background = when (status) {
        RunStatus.FAILED -> colors.errorContainer
        RunStatus.AWAITING_USER, RunStatus.INTERRUPTED -> colors.tertiaryContainer
        RunStatus.COMPLETED -> colors.secondaryContainer
        else -> colors.primaryContainer
    }
    val foreground = when (status) {
        RunStatus.FAILED -> colors.onErrorContainer
        RunStatus.AWAITING_USER, RunStatus.INTERRUPTED -> colors.onTertiaryContainer
        RunStatus.COMPLETED -> colors.onSecondaryContainer
        else -> colors.onPrimaryContainer
    }
    Surface(color = background, contentColor = foreground, shape = MaterialTheme.shapes.small) {
        Text(label, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

internal const val DEFAULT_MAX_ATTEMPTS = 2

internal fun visitAttemptLabel(visit: StepVisit, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): String =
    visitAttemptLabel(visit.status, visit.attempts, maxAttempts)

internal fun visitAttemptLabel(status: StepStatus, attempts: List<AttemptRecord>, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): String {
    val current = when {
        status == StepStatus.RETRYING -> (attempts.size + 1).coerceAtMost(maxAttempts).coerceAtLeast(1)
        attempts.isNotEmpty() -> attempts.last().attemptNo.coerceAtLeast(attempts.size)
        else -> 0
    }
    return "시도 $current/$maxAttempts"
}
