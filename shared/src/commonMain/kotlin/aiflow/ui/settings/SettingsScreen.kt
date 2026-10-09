package aiflow.ui.settings

import aiflow.storage.*
import aiflow.ui.components.*
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState(); val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState(); val candidates by vm.candidates.collectAsState()
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf("") }
    var limit by remember(settings.logBufferLimit) { mutableStateOf(settings.logBufferLimit.toString()) }
    fun addModel() { val name = model.trim(); if (name.isNotEmpty()) { vm.update { it.copy(codexModels = (it.codexModels + name).distinct()) }; model = "" } }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        SectionCard("화면") { SegmentedChoice(ThemeMode.entries, settings.themeMode, { when(it) { ThemeMode.SYSTEM -> "시스템"; ThemeMode.LIGHT -> "라이트"; ThemeMode.DARK -> "다크" } }) { mode -> vm.update { it.copy(themeMode = mode) } } }
        SectionCard("CLI 경로") {
            listOf("codex", "agy").forEach { binary ->
                val path = if (binary == "codex") settings.codexPath else settings.agyPath
                OutlinedTextField(path.orEmpty(), { value -> vm.update { if (binary == "codex") it.copy(codexPath = value.ifBlank { null }) else it.copy(agyPath = value.ifBlank { null }) } }, label = { Text("$binary CLI 절대 경로") }, modifier = Modifier.fillMaxWidth(), singleLine = true, trailingIcon = { ToolIcon("CLI 파일 선택", Icons.Outlined.FolderOpen, !busy) { scope.launch { vm.chooseFile()?.let { value -> vm.update { if (binary == "codex") it.copy(codexPath = value) else it.copy(agyPath = value) } } } } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ vm.detect(binary) }, enabled = !busy) { Text("자동 탐지") }; OutlinedButton({ vm.verify(binary) }, enabled = !busy && !path.isNullOrBlank()) { Text("확인") } }
                settings.cliChecks[binary]?.let { check -> Text("마지막 검사 · ${check.path} · ${check.checkedAt}\n${check.diagnostic}${if (check.path != path) "\n현재 입력 경로와 다른 과거 검사입니다" else ""}", style = MaterialTheme.typography.bodySmall) }
                candidates[binary].orEmpty().forEach { check ->
                    fun select() { vm.update { if (binary == "codex") it.copy(codexPath = check.path) else it.copy(agyPath = check.path) } }
                    ListItem(headlineContent = { Text(check.path) }, supportingContent = { Text("${check.version ?: "버전 미확인"} · ${check.contractId ?: "계약 없음"}") }, leadingContent = { RadioButton(path == check.path, onClick = null, enabled = !busy) }, modifier = Modifier.selectable(path == check.path, enabled = !busy, role = Role.RadioButton, onClick = ::select))
                }
            }
        }
        SectionCard("모델 후보") {
            Text("후보 목록은 실행 지원 검증을 의미하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            settings.codexModels.forEachIndexed { i, name -> ListItem(headlineContent = { Text(name) }, trailingContent = { Row {
                ToolIcon("위로 이동", Icons.Outlined.ArrowUpward, i > 0) { vm.update { it.copy(codexModels = it.codexModels.toMutableList().apply { add(i - 1, removeAt(i)) }) } }
                ToolIcon("아래로 이동", Icons.Outlined.ArrowDownward, i < settings.codexModels.lastIndex) { vm.update { it.copy(codexModels = it.codexModels.toMutableList().apply { add(i + 1, removeAt(i)) }) } }
                ToolIcon("모델 후보 삭제", Icons.Outlined.Delete) { vm.update { it.copy(codexModels = it.codexModels - name) } }
            } }) }
            OutlinedTextField(model, { model = it }, label = { Text("모델 후보 추가") }, modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { addModel(); true } else false }, singleLine = true, trailingIcon = { ToolIcon("모델 추가", Icons.Outlined.Add, model.isNotBlank(), ::addModel) })
            Row { Text("Antigravity 모델 캐시", Modifier.weight(1f)); OutlinedButton({ vm.refreshModels() }, enabled = !busy) { Text("갱신") } }
            ExpandableSection("과거 조회 정보") { settings.agyModelsCache?.let { cache -> Text("${cache.binaryPath} · ${cache.binaryVersion} · ${cache.queriedAt}\n${cache.models.joinToString("\n")}${if (cache.binaryPath != settings.agyPath) "\n현재 경로와 다른 캐시입니다" else ""}") } ?: Text("조회 기록 없음") }
        }
        SectionCard("워크트리") { OutlinedTextField(settings.defaultWorktreeRoot.orEmpty(), { value -> vm.update { it.copy(defaultWorktreeRoot = value.ifBlank { null }) } }, label = { Text("새 워크플로의 기본 루트") }, modifier = Modifier.fillMaxWidth(), trailingIcon = { ToolIcon("워크트리 루트 폴더 선택", Icons.Outlined.FolderOpen) { scope.launch { vm.chooseDirectory()?.let { value -> vm.update { it.copy(defaultWorktreeRoot = value) } } } } }) }
        SectionCard("알림") {
            ListItem(headlineContent = { Text("macOS 알림") }, trailingContent = { Switch(settings.notificationsEnabled, { enabled -> vm.update { it.copy(notificationsEnabled = enabled) } }) })
            NotificationEvent.entries.forEach { event ->
                val selected = event in settings.notificationEvents
                ListItem(headlineContent = { Text(when(event) { NotificationEvent.COMPLETED -> "완료"; NotificationEvent.FAILED -> "실패"; NotificationEvent.AWAITING_USER -> "사용자 확인 필요"; NotificationEvent.PAUSED -> "일시정지됨" }) }, leadingContent = { Checkbox(selected, null) }, modifier = Modifier.toggleable(selected, role = Role.Checkbox) { enabled -> vm.update { it.copy(notificationEvents = if (enabled) it.notificationEvents + event else it.notificationEvents - event) } })
            }
        }
        SectionCard("로그") { OutlinedTextField(limit, { value -> limit = value; value.toIntOrNull()?.takeIf { it in 100..200_000 }?.let { number -> vm.update { it.copy(logBufferLimit = number) } } }, label = { Text("방문별 로그 버퍼 상한") }, supportingText = { Text("100~200,000줄") }, isError = limit.toIntOrNull() !in 100..200_000) }
        Text("변경은 즉시 저장됩니다. 로그인은 CLI에서 수행하세요. 알림 클릭으로 앱을 활성화하는 기능은 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
}
