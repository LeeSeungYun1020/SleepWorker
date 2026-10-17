package sleepworker.storage

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import sleepworker.model.Provider
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable data class ModelsCache(val binaryPath: String, val binaryVersion: String, val queriedAt: Instant, val models: List<String>)
@Serializable enum class NotificationEvent { COMPLETED, FAILED, AWAITING_USER, PAUSED }
@Serializable data class WindowGeometry(val width: Int = 1280, val height: Int = 850, val x: Int? = null, val y: Int? = null)
@Serializable data class CliCheck(val path: String, val version: String?, val contractId: String?, val checkedAt: Instant, val diagnostic: String)
@Serializable enum class ThemeMode { SYSTEM, LIGHT, DARK }
@Serializable data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val codexPath: String? = null,
    val agyPath: String? = null,
    val claudePath: String? = null,
    val codexModels: List<String> = emptyList(),
    val claudeModels: List<String> = listOf("opus", "sonnet", "haiku"),
    val agyModelsCache: ModelsCache? = null,
    val defaultWorktreeRoot: String? = null,
    val notificationsEnabled: Boolean = true,
    val notificationEvents: Set<NotificationEvent> = NotificationEvent.entries.toSet(),
    val logBufferLimit: Int = 20_000,
    val window: WindowGeometry = WindowGeometry(),
    val cliChecks: Map<String, CliCheck> = emptyMap(),
)
/** CLI binary name; also the key of [AppSettings.cliChecks]. */
val Provider.binary: String get() = when (this) { Provider.CODEX -> "codex"; Provider.ANTIGRAVITY -> "agy"; Provider.CLAUDE -> "claude" }
fun providerForBinary(binary: String): Provider = Provider.entries.first { it.binary == binary }
fun AppSettings.pathFor(provider: Provider): String? = when (provider) { Provider.CODEX -> codexPath; Provider.ANTIGRAVITY -> agyPath; Provider.CLAUDE -> claudePath }
fun AppSettings.withPath(provider: Provider, path: String?): AppSettings = when (provider) {
    Provider.CODEX -> copy(codexPath = path); Provider.ANTIGRAVITY -> copy(agyPath = path); Provider.CLAUDE -> copy(claudePath = path)
}
/** Settings-managed candidates; Antigravity candidates come from its queried model cache. */
fun AppSettings.modelCandidates(provider: Provider): List<String> = when (provider) {
    Provider.CODEX -> codexModels; Provider.ANTIGRAVITY -> agyModelsCache?.models.orEmpty(); Provider.CLAUDE -> claudeModels
}
@OptIn(ExperimentalUuidApi::class)
class SettingsStore(private val fs: FileSystem, private val path: Path) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    suspend fun load(): AppSettings = mutex.withLock {
        if (fs.exists(path)) json.decodeFromString(AppSettings.serializer(), fs.read(path) { readUtf8() }) else AppSettings()
    }
    suspend fun save(settings: AppSettings) = mutex.withLock {
        require(settings.logBufferLimit in 100..200_000) { "로그 상한은 100~200,000줄입니다" }
        fs.createDirectories(path.parent!!)
        val temp = path.parent!! / ".settings-${Uuid.random()}.tmp"
        try {
            fs.write(temp, mustCreate = true) { writeUtf8(json.encodeToString(AppSettings.serializer(), settings)) }
            fs.atomicMove(temp, path)
        } finally { fs.delete(temp, mustExist = false) }
    }
}
