package aiflow.ui

import aiflow.platform.Platform
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun App(platform: Platform) {
    var selected by remember { mutableStateOf("편집") }
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row {
                Column(Modifier.width(180.dp).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("aiflow", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    listOf("편집", "실행", "히스토리", "설정").forEach { item ->
                        if (selected == item) FilledTonalButton(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) { Text(item) }
                        else TextButton(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) { Text(item) }
                    }
                }
                VerticalDivider()
                Column(Modifier.padding(32.dp)) {
                    Text(selected, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(16.dp))
                    Text("워크플로를 위한 공간", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
