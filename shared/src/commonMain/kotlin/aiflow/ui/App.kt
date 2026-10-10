package aiflow.ui

import aiflow.ui.editor.EditorScreen
import aiflow.ui.history.HistoryScreen
import aiflow.ui.settings.SettingsScreen
import aiflow.ui.run.RunScreen
import aiflow.ui.run.RunViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import aiflow.ui.components.*
import aiflow.ui.theme.AiflowTheme
import kotlinx.coroutines.launch

private enum class AppDestination(val label: String) { EDITOR("편집"), RUN("실행"), HISTORY("히스토리"), SETTINGS("설정") }

@Composable
fun App(viewModel: RunViewModel) {
    val pendingRepository by viewModel.pendingRepository.collectAsState()
    val busy by viewModel.busy.collectAsState()
    var abortConfirm by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(AppDestination.RUN) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val fatal by viewModel.fatalError.collectAsState()
    LaunchedEffect(viewModel) { viewModel.menuActions.collect { command ->
        if (command == "abort") abortConfirm = true
        else if (command == "undo" || command == "redo") viewModel.undoWorkflow(command == "redo")
        else { focusManager.clearFocus(); tab = AppDestination.EDITOR; viewModel.editorCommand.value = (viewModel.editorCommand.value?.first ?: 0L) + 1 to command }
    } }
    val settings by viewModel.settingsModel.settings.collectAsState()
    val repository by viewModel.repository.collectAsState()
    val run by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var path by remember(repository) { mutableStateOf(repository.orEmpty()) }
    val error by viewModel.error.collectAsState()
    val isEditorVisible = tab == AppDestination.EDITOR
    SideEffect { viewModel.editorVisible.value = isEditorVisible }
    CompositionLocalProvider(LocalTextInputFocus provides viewModel.textInputFocus) {
    AiflowTheme(settings.themeMode) {
        Surface(Modifier.fillMaxSize()) {
            Row {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("aiflow", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                    AppDestination.entries.forEach { item -> NavigationRailItem(selected = tab == item, onClick = { focusManager.clearFocus(); tab = item }, icon = { Icon(when(item) { AppDestination.EDITOR -> Icons.Outlined.Edit; AppDestination.RUN -> Icons.Outlined.PlayArrow; AppDestination.HISTORY -> Icons.Outlined.History; AppDestination.SETTINGS -> Icons.Outlined.Settings }, item.label) }, label = { Text(item.label) }) }
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(path, { path = it }, label = { Text("Git 저장소") }, singleLine = true, modifier = Modifier.trackTextInputFocus().weight(1f), enabled = !busy && run?.status?.terminal != false,
                            trailingIcon = { ToolIcon("저장소 폴더 선택", Icons.Outlined.FolderOpen, !busy && run?.status?.terminal != false) { scope.launch { viewModel.chooseRepository()?.let { path = it; viewModel.openRepository(it) } } } })
                        FilledTonalButton({ focusManager.clearFocus(); viewModel.openRepository(path) }, enabled = path.isNotBlank() && !busy && run?.status?.terminal != false) { Text("열기") }
                    }
                    if (tab != AppDestination.RUN && tab != AppDestination.HISTORY) error?.let { Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider()
                    Box(Modifier.weight(1f)) {
                        when(tab) {
                            AppDestination.RUN -> RunScreen(viewModel)
                            AppDestination.HISTORY -> HistoryScreen(viewModel) { tab = AppDestination.EDITOR }
                            AppDestination.EDITOR -> EditorScreen(viewModel) { tab = AppDestination.RUN }
                            AppDestination.SETTINGS -> SettingsScreen(viewModel.settingsModel)
                        }
                    }
                }
            }
        }

        if (abortConfirm) AlertDialog(onDismissRequest = { abortConfirm = false }, title = { Text("실행 강제 중지") }, text = { Text("본문과 검사 프로세스를 종료하고 중지 상태로 기록합니다.") }, confirmButton = { Button({ viewModel.abort(); abortConfirm = false }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("강제 중지") } }, dismissButton = { TextButton({ abortConfirm = false }) { Text("취소") } })
        if (fatal != null) AlertDialog(onDismissRequest = { viewModel.fatalError.value = null }, title = { Text("앱 오류") }, text = { Text("$fatal\n상세: ~/Library/Logs/aiflow/app.log") }, confirmButton = { TextButton({ viewModel.fatalError.value = null }) { Text("확인") } })
        if (pendingRepository != null) AlertDialog(onDismissRequest = { viewModel.cancelRepositoryChange() }, title = { Text("저장하지 않은 편집 내용") }, text = { Text("초안을 저장하거나 변경을 버린 뒤 저장소를 변경하세요.") },
            confirmButton = { Button({ viewModel.confirmRepositoryChange(true) }, enabled = !busy) { Text("저장 후 이동") } },
            dismissButton = { Row { TextButton({ viewModel.confirmRepositoryChange(false) }, enabled = !busy) { Text("버리기·이동") }; TextButton({ viewModel.cancelRepositoryChange() }) { Text("취소") } } })
    }
    }
}
