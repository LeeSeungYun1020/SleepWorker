package aiflow.ui.settings

import aiflow.engine.CommandRunner
import aiflow.model.Provider
import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/** Serializes edits with probes so a late model refresh cannot overwrite a newer setting. */
class SettingsViewModel(private val platform: Platform, private val scope: CoroutineScope) {
    private val store = SettingsStore(platform.files, platform.settingsPath)
    private val gate = Mutex()
    private val owner = ManagedProcessRunner(platform.processes)
    private val runner = CommandRunner(owner)
    val settings = MutableStateFlow(AppSettings())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val candidates = MutableStateFlow<Map<String, List<CliCheck>>>(emptyMap())
    private val loaded = scope.async {
        try { settings.value = store.load() } catch (e: Exception) { error.value = "설정 로드 실패: ${e.message}" }
    }
    suspend fun current(): AppSettings { loaded.await(); return settings.value }
    suspend fun save(transform: (AppSettings) -> AppSettings) {
        loaded.await()
        gate.withLock {
            val next = transform(settings.value)
            store.save(next); settings.value = next
        }
    }
    suspend fun chooseFile(): String? = platform.fileDialogs?.file()
    suspend fun chooseDirectory(): String? = platform.fileDialogs?.directory()
    fun update(transform: (AppSettings) -> AppSettings) = scope.launch {
        try { save(transform); error.value = null } catch (e: Exception) { platform.diagnostics(e); error.value = e.message }
    }
    private fun action(block: suspend () -> Unit) = scope.launch {
        loaded.await(); gate.withLock {
            busy.value = true; error.value = null
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { platform.diagnostics(e); error.value = e.message }
            finally { busy.value = false }
        }
    }
    private suspend fun capture(command: List<String>, cwd: String = "/"): CapturedProcess {
        val result = runner.run(ProcessSpec(command, cwd), 30_000)
        result.cleanupError?.let { throw aiflow.engine.UnsafeCleanup(it) }
        check(result.termination == Termination.NORMAL) { result.error ?: "CLI 확인 실패" }
        return CapturedProcess(result.exitCode ?: error("종료코드 미관측"), result.stdout, result.stderr)
    }
    private suspend fun checkPath(binary: String, path: String): CliCheck {
        val output = try { capture(listOf(path, "--version")) }
        catch (e: CancellationException) { throw e }
        catch (e: aiflow.engine.UnsafeCleanup) { throw e }
        catch (e: Exception) { return CliCheck(path, null, null, Clock.System.now(), e.message ?: e.toString()) }
        val version = if (output.exitCode == 0) Regex("\\b\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?\\b").find(output.stdout.joinToString("\n"))?.value else null
        val provider = if (binary == "codex") Provider.CODEX else Provider.ANTIGRAVITY
        val contract = version?.let { VerifiedCliContract.forVersion(provider, it) }
        return CliCheck(path, version, contract?.id, Clock.System.now(),
            "exit=${output.exitCode}\n${(output.stdout + output.stderr).joinToString("\n")}\n${contract?.id ?: "실측 기록 없음 — 버전·모델 실측 여부는 실행을 제한하지 않습니다"}")
    }
    fun detect(binary: String) = action {
        candidates.value = candidates.value + (binary to platform.pathDetector.candidates(binary, ProcessProbe { capture(it.command, it.cwd) }).distinct().map { checkPath(binary, it) })
        check(candidates.value[binary].orEmpty().isNotEmpty()) { "$binary 후보를 찾지 못했습니다. 절대 경로를 입력하세요." }
    }
    fun verify(binary: String) = action {
        val path = (if (binary == "codex") settings.value.codexPath else settings.value.agyPath)?.takeIf { it.startsWith('/') } ?: error("CLI 절대 경로가 필요합니다")
        val check = checkPath(binary, path)
        val next = settings.value.copy(cliChecks = settings.value.cliChecks + (binary to check))
        store.save(next); settings.value = next
    }
    fun refreshModels() = action {
        val path = settings.value.agyPath?.takeIf { it.startsWith('/') } ?: error("agy 절대 경로가 필요합니다")
        val checked = checkPath("agy", path)
        require(checked.version != null) { checked.diagnostic }
        val adapter = AntigravityAdapter()
        val models = adapter.listModels(ProcessProbe { capture(it.command) }, ProviderConfig(path, "/", checked.version)) ?: error("agy models 갱신 실패 — 기존 캐시는 과거 결과입니다")
        val next = settings.value.copy(agyModelsCache = ModelsCache(path, checked.version, Clock.System.now(), models), cliChecks = settings.value.cliChecks + ("agy" to checked))
        store.save(next); settings.value = next
    }
    suspend fun close() { owner.cancelAll() }
}
