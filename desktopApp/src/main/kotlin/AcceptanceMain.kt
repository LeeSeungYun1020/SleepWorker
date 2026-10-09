import aiflow.engine.*
import aiflow.model.WorkflowVersion
import aiflow.platform.desktopPlatform
import aiflow.storage.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import kotlin.time.Clock

/** Explicit opt-in diagnostic entry point. Preflight never invokes an agent model. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3 && args[0] == "preflight") { "Usage: preflight WORKFLOW.yaml SETTINGS.json" }
    val platform = desktopPlatform()
    val workflow = WorkflowCodec().decode(platform.files.read(args[1].toPath()) { readUtf8() })
    val settings = SettingsStore(platform.files, args[2].toPath()).load()
    val version = WorkflowVersion("00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002", Clock.System.now(), workflow = workflow)
    val report = CliPreflight(platform).inspect(version, settings)
    report.items.forEach { println("${it.status}: ${it.name}: ${it.detail}") }
    println("PREFLIGHT_PASSED=${report.passed}")
}
