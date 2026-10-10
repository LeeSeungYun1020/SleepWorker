import aiflow.platform.*
import aiflow.storage.*
import aiflow.engine.RunStatus
import aiflow.ui.App
import aiflow.ui.theme.AiflowTheme
import aiflow.ui.run.RunViewModel
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.Path.Companion.toPath
import java.awt.Desktop

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val platform = desktopPlatform().let { original -> option("--settings")?.let { original.copy(settingsPath = it.toPath()) } ?: original }
    val initial = runBlocking { try { SettingsStore(platform.files, platform.settingsPath).load() } catch (e: Exception) { JvmAppLog.write(e); AppSettings() } }
    val viewModel = RunViewModel(platform)
    Thread.setDefaultUncaughtExceptionHandler { _, error -> JvmAppLog.write(error); viewModel.fatalError.value = error.message ?: error.toString() }
    option("--repository")?.let(viewModel::openRepository)
    val shutdown = Thread({ runBlocking { try { viewModel.close() } catch (e: Exception) { JvmAppLog.write(e) } } }, "aiflow-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    application {
        val settings by viewModel.settingsModel.settings.collectAsState()
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        var confirmClose by remember { mutableStateOf(false) }
        val editor by viewModel.editor.collectAsState()
        val run by viewModel.state.collectAsState()
        val clean = remember { MutableStateFlow(false) }
        val dirty by (editor?.dirty ?: clean).collectAsState()
        val busy by viewModel.busy.collectAsState()
        val canUndo by (editor?.canUndo ?: clean).collectAsState()
        val canRedo by (editor?.canRedo ?: clean).collectAsState()
        val emptyPreview = remember { MutableStateFlow<aiflow.model.WorkflowVersion?>(null) }
        val emptyDraft = remember { MutableStateFlow<aiflow.model.WorkflowDraft?>(null) }
        val preview by (editor?.preview ?: emptyPreview).collectAsState()
        val draft by (editor?.draft ?: emptyDraft).collectAsState()
        val editorVisible by viewModel.editorVisible.collectAsState()
        val inputFocused by viewModel.textInputFocus.active.collectAsState()
        val workflowUndoEnabled = editorVisible && !inputFocused && preview == null && !busy
        val geometry = initial.window
        val visiblePosition = geometry.x != null && geometry.y != null && java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device -> device.defaultConfiguration.bounds.contains(geometry.x!! + 80, geometry.y!! + 40) }
        val state = rememberWindowState(width = geometry.width.coerceIn(800, 4000).dp, height = geometry.height.coerceIn(600, 3000).dp,
            position = if (visiblePosition) WindowPosition(geometry.x!!.dp, geometry.y!!.dp) else WindowPosition.PlatformDefault)
        fun closeWindow(save: Boolean = true) {
            if (closing) return
            closing = true
            scope.launch {
                try {
                    if (save) editor?.preserveDraftOnShutdown() else editor?.discard()
                    viewModel.close(); exitApplication()
                } catch (e: Exception) { JvmAppLog.write(e); viewModel.fatalError.value = "종료 실패: ${e.message}"; closing = false }
            }
        }
        fun requestClose() {
            if (busy || closing) return
            if (dirty || run?.status?.let { !it.terminal && it != RunStatus.IDLE } == true) confirmClose = true else closeWindow()
        }
        val latestClose by rememberUpdatedState { requestClose() }
        DisposableEffect(Unit) {
            val desktop = Desktop.getDesktop()
            if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler { _, response -> response.cancelQuit(); javax.swing.SwingUtilities.invokeLater { latestClose() } }
            onDispose { if (desktop.isSupported(Desktop.Action.APP_QUIT_HANDLER)) desktop.setQuitHandler(null) }
        }
        LaunchedEffect(state) {
            snapshotFlow { Triple(state.size, state.position, state.placement) }.debounce(500).collect { (size, position, placement) ->
                if (placement == WindowPlacement.Floating && position is WindowPosition.Absolute) {
                    try { viewModel.settingsModel.save { it.copy(window = WindowGeometry(size.width.value.toInt(), size.height.value.toInt(), position.x.value.toInt(), position.y.value.toInt())) } }
                    catch (e: Exception) { JvmAppLog.write(e) }
                }
            }
        }
        Window(onCloseRequest = { requestClose() }, title = "aiflow", state = state, onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Z && event.isMetaPressed && !event.isAltPressed && !event.isCtrlPressed && viewModel.editorVisible.value && !viewModel.textInputFocus.active.value && !viewModel.busy.value && editor?.readOnly == false) {
                viewModel.undoWorkflow(event.isShiftPressed); true
            } else false
        }) {
            MenuBar {
                Menu("File") {
                    Item("새 워크플로", onClick = { viewModel.menu("new") }, enabled = editor != null && !busy, shortcut = KeyShortcut(Key.N, meta = true))
                    Item("열기", onClick = { viewModel.menu("open") }, enabled = editor != null && !busy, shortcut = KeyShortcut(Key.O, meta = true))
                    Item("저장", onClick = { viewModel.menu("save") }, enabled = (draft != null || preview != null) && !busy && preview == null, shortcut = KeyShortcut(Key.S, meta = true))
                    Item("버전 기록", onClick = { viewModel.menu("versions") }, enabled = draft != null && !busy)
                    Separator()
                    Item("종료", onClick = { requestClose() }, enabled = !busy && !closing, shortcut = KeyShortcut(Key.Q, meta = true))
                }
                Menu("Edit") {
                    Item("실행 취소 (⌘Z)", onClick = { viewModel.undoWorkflow() }, enabled = canUndo && workflowUndoEnabled)
                    Item("다시 적용 (⇧⌘Z)", onClick = { viewModel.undoWorkflow(true) }, enabled = canRedo && workflowUndoEnabled)
                    Item("노드 찾기", onClick = { viewModel.menu("search") }, enabled = editor != null, shortcut = KeyShortcut(Key.F, meta = true))
                }
                Menu("Run") {
                    Item("프리플라이트", onClick = viewModel::runPreflight, enabled = !viewModel.active && !busy && viewModel.selected.value != null, shortcut = KeyShortcut(Key.P, meta = true, shift = true))
                    Item("실행", onClick = viewModel::start, enabled = !viewModel.active && !busy && viewModel.report.value?.passed == true, shortcut = KeyShortcut(Key.R, meta = true))
                    Item("일시정지", onClick = viewModel::pause, enabled = run?.status == RunStatus.RUNNING)
                    Item("재개", onClick = viewModel::resume, enabled = run?.status == RunStatus.PAUSED)
                    Item("중지", onClick = { viewModel.menu("abort") }, enabled = viewModel.active)
                }
                Menu("Help") {
                    Item("계획서 열기", onClick = {
                        try {
                            val text = object {}.javaClass.getResourceAsStream("/plan.md")?.bufferedReader()?.use { it.readText() } ?: error("계획서 리소스 없음")
                            val file = java.nio.file.Files.createTempFile("aiflow-plan-", ".md").toFile().apply { deleteOnExit(); writeText(text) }
                            Desktop.getDesktop().open(file)
                        } catch (e: Exception) { JvmAppLog.write(e); viewModel.fatalError.value = e.message }
                    })
                }
            }
            App(viewModel)
            if (confirmClose) AiflowTheme(settings.themeMode) { AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text(if (dirty) "저장하지 않은 편집 내용" else "실행 중인 앱 종료") }, text = { Text((if (dirty) "초안을 저장하거나 변경을 버린 뒤 종료하세요.\n" else "") + (if (viewModel.active) "실행 중인 프로세스를 종료하고 로그를 저장한 뒤 중단된 실행으로 기록합니다." else "")) },
                confirmButton = { Button({ confirmClose = false; closeWindow() }) { Text(if (dirty) "저장 후 종료" else "실행 중단 후 종료") } },
                dismissButton = { Row { if (dirty) TextButton({ confirmClose = false; closeWindow(false) }) { Text("버리기·종료") }; TextButton({ confirmClose = false }) { Text("취소") } } }) }
        }
    }
}
