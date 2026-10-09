package sleepworker.ui

import sleepworker.platform.APP_NAME
import sleepworker.ui.editor.EditorScreen
import sleepworker.ui.history.HistoryScreen
import sleepworker.ui.settings.SettingsScreen
import sleepworker.ui.run.RunScreen
import sleepworker.ui.run.RunViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(viewModel: RunViewModel) {
    val pendingRepository by viewModel.pendingRepository.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var abortConfirm by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("실행") }
    val fatal by viewModel.fatalError.collectAsState()
    LaunchedEffect(viewModel) { viewModel.menuActions.collect { command ->
        if (command == "abort") abortConfirm = true
        else { tab = "편집"; viewModel.editorCommand.value = (viewModel.editorCommand.value?.first ?: 0L) + 1 to command }
    } }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(APP_NAME, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
                    val tabs = listOf("편집", "실행", "히스토리", "설정")
                    TabRow(selectedTabIndex = tabs.indexOf(tab), modifier = Modifier.weight(1f)) {
                        tabs.forEach { name -> Tab(selected = tab == name, onClick = { tab = name }, text = { Text(name) }) }
                    }
                }
                HorizontalDivider()
                if (tab == "실행") RunScreen(viewModel)
                else if (tab == "히스토리") HistoryScreen(viewModel) { tab = "편집" }
                else if (tab == "편집") EditorScreen(viewModel) { tab = "실행" }
                else SettingsScreen(viewModel.settingsModel)
            }
        }
        if (abortConfirm) AlertDialog(onDismissRequest = { abortConfirm = false }, title = { Text("실행 강제 중지") }, text = { Text("본문과 검사 프로세스를 종료하고 ABORTED로 기록합니다.") }, confirmButton = { Button({ viewModel.abort(); abortConfirm = false }) { Text("강제 중지") } }, dismissButton = { TextButton({ abortConfirm = false }) { Text("취소") } })
        if (fatal != null) AlertDialog(onDismissRequest = { viewModel.fatalError.value = null }, title = { Text("앱 오류") }, text = { Text("$fatal\n상세: ~/Library/Logs/aiflow/app.log") }, confirmButton = { TextButton({ viewModel.fatalError.value = null }) { Text("확인") } })
        if (pendingRepository != null) AlertDialog(onDismissRequest = { viewModel.cancelRepositoryChange() }, title = { Text("저장하지 않은 편집 내용") }, text = { Text("초안을 저장하거나 변경을 버린 뒤 저장소를 변경하세요.") },
            confirmButton = { Button({ viewModel.confirmRepositoryChange(true) }, enabled = !busy) { Text("저장 후 이동") } },
            dismissButton = { Row { TextButton({ viewModel.confirmRepositoryChange(false) }, enabled = !busy) { Text("버리기·이동") }; TextButton({ viewModel.cancelRepositoryChange() }) { Text("취소") } } })
    }
}
