package sleepworker.storage

import sleepworker.engine.*
import sleepworker.platform.RepositoryLease
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RecordingException(cause: Throwable) : Exception("Run recording failed: ${cause.message}", cause)

/** All readers and writers require the same live repository ownership lease. */
@OptIn(ExperimentalUuidApi::class)
class RunRecorder(val fs: FileSystem, val lease: RepositoryLease) {
    val json = Json { prettyPrint = true; encodeDefaults = true }
    private val root = lease.repoPath / ".sleepworker" / "runs"
    private fun path(runId: String, relative: String): Path {
        lease.requireHeld()
        require(Regex("[0-9]{8}-[0-9]{6}-[0-9a-f]{8,32}").matches(runId)) { "Invalid run ID" }
        val parts = listOf(".sleepworker", "runs", runId) + relative.split('/').filter { it.isNotEmpty() }
        var result = lease.repoPath
        parts.forEach {
            require(it != "." && it != ".." && '\\' !in it)
            result /= it
            require(fs.metadataOrNull(result)?.symlinkTarget == null) { "Symlink run storage: $result" }
        }
        return result
    }
    fun visitPath(visit: StepVisit): String {
        require(visit.visitNo > 0 && sleepworker.model.WorkflowValidator.SAFE_NAME.matches(visit.stepId))
        return "visits/${visit.visitNo}-${visit.stepId}"
    }
    fun attemptPath(visit: StepVisit, attemptNo: Int): String {
        require(attemptNo in 1..2)
        return "${visitPath(visit)}/attempts/$attemptNo"
    }
    private fun atomic(path: Path, text: String) {
        fs.createDirectories(path.parent!!)
        val temporary = path.parent!! / ".${Uuid.random()}.tmp"
        try {
            fs.write(temporary, mustCreate = true) { writeUtf8(text) }
            fs.atomicMove(temporary, path)
        } finally { fs.delete(temporary, mustExist = false) }
    }
    private suspend fun <T> guarded(block: () -> T): T = lease.writeMutex.withLock {
        try { lease.requireHeld(); block() } catch (e: Exception) { throw RecordingException(e) }
    }
    suspend fun save(state: RunState) = guarded {
        require(state.status != RunStatus.IDLE)
        val file = path(state.runId, "run.json")
        if (fs.exists(file)) {
            val prior = json.decodeFromString<RunState>(fs.read(file) { readUtf8() })
            check(!prior.status.terminal || state == prior) { "Terminal run is immutable" }
        }
        // run.json is authoritative if the app dies between these projections.
        atomic(file, json.encodeToString(state))
        state.visits.forEach { atomic(path(state.runId, "${visitPath(it)}/visit.json"), json.encodeToString(it)) }
    }
    suspend fun write(runId: String, relative: String, text: String, immutable: Boolean = false) = guarded {
        val file = path(runId, relative)
        if (immutable && fs.exists(file)) {
            check(fs.read(file) { readUtf8() } == text) { "Final result is immutable" }
        } else atomic(file, text)
    }
    suspend fun append(runId: String, relative: String, text: String) = guarded {
        val file = path(runId, relative)
        fs.createDirectories(file.parent!!)
        fs.appendingSink(file).buffer().use { it.writeUtf8(text) }
    }
    suspend fun readLogs(runId: String, onLine: (LogLine) -> Unit) = guarded {
        val file = path(runId, "logs.jsonl")
        if (fs.exists(file)) fs.read(file) {
            while (true) {
                val line = readUtf8Line() ?: break
                // A crash may leave an incomplete final JSON line. Other records remain readable.
                val parsed = try { json.decodeFromString<LogLine>(line) } catch (_: Exception) { null }
                if (parsed != null) onLine(parsed)
            }
        }
    }
    suspend fun files(runId: String): List<String> = guarded {
        val directory = path(runId, "")
        if (!fs.exists(directory)) emptyList() else fs.listRecursively(directory, followSymlinks = false).map { file ->
            val relative = file.relativeTo(directory).toString()
            path(runId, relative)
            relative to fs.metadata(file).isRegularFile
        }.filter { it.second }.map { it.first }.toList().sorted()
    }
    /** Read only the selected artifact, with a bounded preview for large logs. */
    suspend fun read(runId: String, relative: String, limit: Long = 512_000): String = guarded {
        require(limit in 1..2_000_000)
        fs.read(path(runId, relative)) {
            val bytes = readByteArray(limit.coerceAtMost(fs.metadata(path(runId, relative)).size ?: 0))
            bytes.decodeToString() + if (!exhausted()) "\n… 미리보기 상한 도달 — 원본 파일에서 전체 열람" else ""
        }
    }
    suspend fun delete(runId: String, confirmed: Boolean) = guarded {
        require(confirmed)
        val directory = path(runId, "")
        val run = json.decodeFromString<RunState>(fs.read(path(runId, "run.json")) { readUtf8() })
        require(run.status.terminal) { "미완료 실행 기록은 삭제할 수 없습니다" }
        fs.listRecursively(directory, followSymlinks = false).forEach { path(runId, it.relativeTo(directory).toString()) }
        fs.deleteRecursively(directory)
    }
    suspend fun list(): List<RunState> = guarded {
        // Also checks every storage ancestor even for an empty repository.
        path("20000101-000000-00000000", "")
        if (!fs.exists(root)) emptyList() else fs.list(root).filter { !it.name.startsWith('.') }.map { dir ->
            val file = path(dir.name, "run.json")
            if (!fs.exists(file)) null else json.decodeFromString<RunState>(fs.read(file) { readUtf8() }).also { require(it.runId == dir.name) }
        }.filterNotNull().sortedByDescending { it.runId }
    }
}
