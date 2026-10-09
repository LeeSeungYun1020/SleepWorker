import aiflow.platform.desktopPlatform
import aiflow.ui.App
import aiflow.ui.run.RunViewModel
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

fun main(args: Array<String>) {
    fun option(name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val platform = desktopPlatform().let { original -> option("--settings")?.let { original.copy(settingsPath = it.toPath()) } ?: original }
    val viewModel = RunViewModel(platform)
    option("--repository")?.let(viewModel::openRepository)
    // Native Quit can bypass Window.onCloseRequest. Stop owned processes before JVM exit.
    val shutdown = Thread({ runBlocking { viewModel.close() } }, "aiflow-shutdown")
    Runtime.getRuntime().addShutdownHook(shutdown)
    application {
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        var confirmClose by remember { mutableStateOf(false) }
        val editor by viewModel.editor.collectAsState()
        val clean = remember { MutableStateFlow(false) }
        val dirty by (editor?.dirty ?: clean).collectAsState()
        fun closeWindow() { if (!closing) { closing = true; scope.launch { try { viewModel.close() } finally { exitApplication() } } } }
        Window(onCloseRequest = {
            if (dirty) confirmClose = true else closeWindow()
        }, title = "aiflow", state = rememberWindowState(width = 1280.dp, height = 850.dp)) {
            App(viewModel)
            if (confirmClose) MaterialTheme { AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("저장하지 않은 편집 내용") }, text = { Text("초안을 저장하거나 변경을 버린 뒤 종료하세요.") },
                confirmButton = { Button({ scope.launch { try { editor?.preserveDraftOnShutdown(); confirmClose = false; closeWindow() } catch (e: Exception) { editor?.message?.value = e.message; confirmClose = false } } }) { Text("저장 후 종료") } },
                dismissButton = { Row { TextButton({ editor?.discard(); confirmClose = false; closeWindow() }) { Text("버리기·종료") }; TextButton({ confirmClose = false }) { Text("취소") } } }) }
        }
    }
}
