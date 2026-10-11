package sleepworker.ui.settings

import sleepworker.storage.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()
    val candidates by vm.candidates.collectAsState()
    var model by remember { mutableStateOf("") }
    var limit by remember(settings.logBufferLimit) { mutableStateOf(settings.logBufferLimit.toString()) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        listOf("codex", "agy").forEach { binary ->
            val path = if (binary == "codex") settings.codexPath else settings.agyPath
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(path.orEmpty(), { value -> vm.update { if (binary == "codex") it.copy(codexPath = value.ifBlank { null }) else it.copy(agyPath = value.ifBlank { null }) } }, label = { Text("$binary CLI 절대 경로") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedButton({ vm.detect(binary) }, enabled = !busy) { Text("자동 탐지") }
                OutlinedButton({ vm.verify(binary) }, enabled = !busy && !path.isNullOrBlank()) { Text("확인") }
            }
            settings.cliChecks[binary]?.let { check -> Text("마지막 검사 · ${check.path} · ${check.checkedAt}\n${check.diagnostic}${if (check.path != path) "\n현재 입력 경로와 다른 과거 검사입니다" else ""}", style = MaterialTheme.typography.bodySmall) }
            candidates[binary].orEmpty().forEach { check ->
                OutlinedButton({ vm.update { if (binary == "codex") it.copy(codexPath = check.path) else it.copy(agyPath = check.path) } }, enabled = !busy) { Text("선택: ${check.path} · ${check.version ?: "버전 미확인"} · ${check.contractId ?: "계약 없음"}") }
            }
        }
        Text("Codex 모델 후보 — 목록에 있어도 실행 지원이 검증된 것은 아닙니다")
        settings.codexModels.forEachIndexed { i, name -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, Modifier.weight(1f))
            TextButton({ vm.update { it.copy(codexModels = it.codexModels.toMutableList().apply { add(i - 1, removeAt(i)) }) } }, enabled = i > 0) { Text("위로") }
            TextButton({ vm.update { it.copy(codexModels = it.codexModels.toMutableList().apply { add(i + 1, removeAt(i)) }) } }, enabled = i < settings.codexModels.lastIndex) { Text("아래로") }
            TextButton({ vm.update { it.copy(codexModels = it.codexModels - name) } }) { Text("삭제") }
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(model, { model = it }, label = { Text("모델 후보 추가") }, modifier = Modifier.weight(1f), singleLine = true)
            Button({ val name = model.trim(); vm.update { it.copy(codexModels = (it.codexModels + name).distinct()) }; model = "" }, enabled = model.isNotBlank()) { Text("추가") }
        }
        Row { Text("Antigravity 모델 캐시", Modifier.weight(1f)); OutlinedButton({ vm.refreshModels() }, enabled = !busy) { Text("갱신") } }
        settings.agyModelsCache?.let { cache -> Text("과거 조회: ${cache.binaryPath} · ${cache.binaryVersion} · ${cache.queriedAt}\n${cache.models.joinToString("\n")}${if (cache.binaryPath != settings.agyPath) "\n현재 경로와 다른 캐시입니다" else ""}") }
        OutlinedTextField(settings.defaultWorktreeRoot.orEmpty(), { value -> vm.update { it.copy(defaultWorktreeRoot = value.ifBlank { null }) } }, label = { Text("새 워크플로의 기본 워크트리 루트") }, modifier = Modifier.fillMaxWidth())
        Row { Text("macOS 알림", Modifier.weight(1f)); Switch(settings.notificationsEnabled, { enabled -> vm.update { it.copy(notificationsEnabled = enabled) } }) }
        NotificationEvent.entries.forEach { event -> Row { Checkbox(event in settings.notificationEvents, { enabled -> vm.update { it.copy(notificationEvents = if (enabled) it.notificationEvents + event else it.notificationEvents - event) } }); Text(when(event) { NotificationEvent.COMPLETED -> "완료"; NotificationEvent.FAILED -> "실패"; NotificationEvent.AWAITING_USER -> "사용자 확인 필요"; NotificationEvent.PAUSED -> "일시정지됨" }) } }
        OutlinedTextField(limit, { value -> limit = value; value.toIntOrNull()?.takeIf { it in 100..200_000 }?.let { number -> vm.update { it.copy(logBufferLimit = number) } } }, label = { Text("방문별 로그 버퍼 상한 · 100~200,000줄") }, isError = limit.toIntOrNull() !in 100..200_000)
        Text("변경은 즉시 저장됩니다. 로그인은 CLI에서 수행하세요. 알림 클릭으로 앱을 활성화하는 기능은 지원하지 않습니다.")
    }
}
