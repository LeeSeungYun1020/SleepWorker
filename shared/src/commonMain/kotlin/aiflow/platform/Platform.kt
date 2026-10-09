package aiflow.platform

import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path

interface ProcessExecutor { fun start(spec: ProcessSpec): RunningProcess }
data class ProcessSpec(val command: List<String>, val cwd: String, val stdin: String? = null, val env: Map<String, String> = emptyMap())
interface RunningProcess {
    /** Single-consumer streams retain output from start and report I/O failure separately from EOF. */
    val stdout: Flow<String>
    val stderr: Flow<String>
    suspend fun awaitExit(): Int
    suspend fun killTreeAndWait()
}
class ProcessCleanupException(message: String, cause: Throwable? = null) : Exception(message, cause)
interface TempFiles { fun createScript(content: String): Path; fun delete(path: Path) }
interface Notifier {
    suspend fun notify(title: String, body: String)
    suspend fun notify(title: String, body: String, subtitle: String) = notify(title, "$subtitle · $body")
}
interface PathDetector {
    suspend fun candidates(binary: String): List<String> = listOfNotNull(detect(binary))
    suspend fun candidates(binary: String, probe: ProcessProbe): List<String> = candidates(binary)
    suspend fun detect(binary: String): String?
    /** Process-based detectors must execute through the supplied probe for ownership and recording. */
    suspend fun detect(binary: String, probe: ProcessProbe): String? = detect(binary)
}
interface RepositoryLease { val writeMutex: kotlinx.coroutines.sync.Mutex; val repoPath: Path; fun requireHeld(); fun release() }
interface RepositoryLock { fun acquire(repoPath: Path): RepositoryLease }
data class Platform(val processes: ProcessExecutor, val files: FileSystem, val tempFiles: TempFiles, val notifier: Notifier, val pathDetector: PathDetector, val repositoryLock: RepositoryLock, val settingsPath: Path, val fileDialogs: FileDialogs? = null, val diagnostics: (Throwable) -> Unit = {})

/** Native pickers return null on cancellation. Implementations marshal to their UI thread. */
interface FileDialogs {
    suspend fun directory(): String?
    suspend fun openYaml(): String?
    suspend fun saveYaml(suggestedName: String): String?
}
