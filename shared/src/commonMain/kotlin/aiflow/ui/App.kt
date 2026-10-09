package aiflow.ui

import aiflow.ui.editor.EditorScreen
import aiflow.ui.run.RunScreen
import aiflow.ui.run.RunViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(viewModel: RunViewModel) {
    val pendingRepository by viewModel.pendingRepository.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var tab by remember { mutableStateOf("실행") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("aiflow", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleLarge)
                    val tabs = listOf("편집", "실행", "히스토리", "설정")
                    TabRow(selectedTabIndex = tabs.indexOf(tab), modifier = Modifier.weight(1f)) {
                        tabs.forEach { name -> Tab(selected = tab == name, onClick = { tab = name }, text = { Text(name) }) }
                    }
                }
                HorizontalDivider()
                if (tab == "실행" || tab == "히스토리") RunScreen(viewModel)
                else if (tab == "편집") EditorScreen(viewModel) { tab = "실행" }
                else Column(Modifier.padding(32.dp)) {
                    Text(tab, style = MaterialTheme.typography.headlineMedium)
                    Text("설정 화면은 Phase 5 예정입니다. CLI 경로와 모델 목록은 ~/Library/Application Support/aiflow/settings.json에서 지정하세요.")
                }
            }
        }
        if (pendingRepository != null) AlertDialog(onDismissRequest = { viewModel.cancelRepositoryChange() }, title = { Text("저장하지 않은 편집 내용") }, text = { Text("초안을 저장하거나 변경을 버린 뒤 저장소를 변경하세요.") },
            confirmButton = { Button({ viewModel.confirmRepositoryChange(true) }, enabled = !busy) { Text("저장 후 이동") } },
            dismissButton = { Row { TextButton({ viewModel.confirmRepositoryChange(false) }, enabled = !busy) { Text("버리기·이동") }; TextButton({ viewModel.cancelRepositoryChange() }) { Text("취소") } } })
    }
}
