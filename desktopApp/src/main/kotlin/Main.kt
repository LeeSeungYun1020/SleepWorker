import aiflow.platform.desktopPlatform
import aiflow.ui.App
import aiflow.ui.run.RunViewModel
import androidx.compose.runtime.*
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
        Window(onCloseRequest = {
            if (!closing) { closing = true; scope.launch { try { viewModel.close() } finally { exitApplication() } } }
        }, title = "aiflow", state = rememberWindowState(width = 1280.dp, height = 850.dp)) { App(viewModel) }
    }
}
