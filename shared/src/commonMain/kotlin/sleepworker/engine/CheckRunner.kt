package sleepworker.engine

import sleepworker.model.*
import sleepworker.platform.ProcessSpec
import sleepworker.provider.Termination
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import okio.FileSystem
import okio.Path.Companion.toPath

class CheckRunner(private val fs: FileSystem, private val io: ExecutionIO) {
    suspend fun command(cmd: String, workspace: String, timeoutSec: Int, path: String, transition: Boolean): CheckRecord {
        val result = io.command(path, ProcessSpec(listOf("/bin/zsh", "-lc", cmd), workspace), timeoutSec * 1000L)
        val check = when {
            result.cleanupError != null -> CheckResult.Error(result.cleanupError)
            result.termination != Termination.NORMAL -> CheckResult.Error(result.error ?: result.termination.name)
            result.exitCode == 0 -> CheckResult.Met
            !transition || result.exitCode == 1 -> CheckResult.NotMet
            else -> CheckResult.Error("Condition command exited ${result.exitCode}")
        }
        return CheckRecord(check, result)
    }
    private fun exists(workspace: String, value: String): CheckResult {
        val path = workspace.toPath().resolve(value, normalize = true)
        require('?' !in value && '[' !in value && ']' !in value && "**" !in value && '*' !in path.parent.toString()) { "Unsupported glob" }
        val present = if ('*' !in path.name) fs.metadataOrNull(path) != null else {
            val parent = path.parent!!
            if (fs.metadataOrNull(parent) == null) false else {
                val pattern = Regex(path.name.split('*').joinToString(".*") { Regex.escape(it) })
                fs.list(parent).any { pattern.matches(it.name) }
            }
        }
        return if (present) CheckResult.Met else CheckResult.NotMet
    }
    private fun contains(workspace: String, value: String, text: String): CheckResult {
        val path = workspace.toPath().resolve(value, normalize = true)
        return if (fs.metadataOrNull(path) == null) CheckResult.NotMet
        else if (fs.read(path) { readUtf8() }.contains(text)) CheckResult.Met else CheckResult.NotMet
    }
    private fun file(block: () -> CheckResult): CheckRecord = try { CheckRecord(block()) } catch (e: Exception) { CheckRecord(CheckResult.Error(e.message ?: e.toString())) }
    suspend fun completion(completion: Completion?, workspace: String, timeoutSec: Int, path: String): CheckRecord {
        val record = when (completion) {
            null, Completion.ExitCode -> CheckRecord(CheckResult.Met)
            is Completion.FileExists -> file { exists(workspace, completion.path) }
            is Completion.Command -> command(completion.cmd, workspace, timeoutSec, path, false)
        }
        withContext(NonCancellable) { save(path, record) }
        record.command?.cleanupError?.let { throw UnsafeCleanup(it, record) }
        if (record.command?.termination == Termination.CANCELLED) throw CancelledCheck(record)
        currentCoroutineContext().ensureActive()
        return record
    }
    suspend fun condition(condition: Condition, workspace: String, timeoutSec: Int, path: String): CheckRecord {
        val record = when (condition) {
            is Condition.FileExists -> file { exists(workspace, condition.path) }
            is Condition.FileContains -> file { contains(workspace, condition.path, condition.text) }
            is Condition.Command -> command(condition.cmd, workspace, timeoutSec, path, true)
            else -> error("Internal conditions do not execute checks")
        }
        withContext(NonCancellable) { save(path, record) }
        record.command?.cleanupError?.let { throw UnsafeCleanup(it, record) }
        if (record.command?.termination == Termination.CANCELLED) throw CancelledCheck(record)
        currentCoroutineContext().ensureActive()
        return record
    }
    private suspend fun save(path: String, record: CheckRecord) = io.recorder.write(io.runId, "$path/result.json", io.recorder.json.encodeToString(record), immutable = true)
}
class UnsafeCleanup(message: String, val check: CheckRecord? = null) : Exception(message)
class CancelledCheck(val check: CheckRecord) : kotlinx.coroutines.CancellationException("Check cancelled")
class CompletionChecker(private val checks: CheckRunner) {
    suspend fun check(step: Step, workspace: String, path: String) = checks.completion(step.completion, workspace, step.checkTimeoutSec, path)
}
