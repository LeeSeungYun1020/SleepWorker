package aiflow.ui

import aiflow.ui.run.RunScreen
import aiflow.ui.run.RunViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(viewModel: RunViewModel) {
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
                else Column(Modifier.padding(32.dp)) {
                    Text(tab, style = MaterialTheme.typography.headlineMedium)
                    Text(if (tab == "편집") "그래프 편집기는 Phase 4 예정입니다. 실행 화면에서 YAML을 저장 버전으로 가져올 수 있습니다."
                        else "설정 화면은 Phase 5 예정입니다. CLI 경로와 모델 목록은 ~/Library/Application Support/aiflow/settings.json에서 지정하세요.")
                }
            }
        }
    }
}
