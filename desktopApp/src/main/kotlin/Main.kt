import aiflow.platform.desktopPlatform
import aiflow.ui.App
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() {
    val platform = desktopPlatform()
    application {
        Window(onCloseRequest = ::exitApplication, title = "aiflow") { App(platform) }
    }
}
