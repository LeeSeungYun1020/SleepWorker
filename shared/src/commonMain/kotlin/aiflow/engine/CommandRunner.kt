package aiflow.engine

import aiflow.platform.*
import aiflow.provider.*
import aiflow.storage.RecordingException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlin.time.TimeSource

/** Pipe readers survive body cancellation long enough to drain evidence after bounded cleanup. */
class CommandRunner(private val owner: ManagedProcessRunner, private val drainMs: Long = 5_000, private val cleanupMs: Long = 5_000) {
    suspend fun run(spec: ProcessSpec, timeoutMs: Long? = null, onLine: suspend (String, Stream) -> Unit = { _, _ -> }): CommandResult {
        val mark = TimeSource.Monotonic.markNow()
        val pipeJob = SupervisorJob()
        val pipes = CoroutineScope(currentCoroutineContext().minusKey(Job) + pipeJob)
        var process: RunningProcess? = null
        var exit: Deferred<Int>? = null
        var drain: Deferred<Unit>? = null
        var code: Int? = null
        var termination = Termination.NORMAL
        var error: String? = null
        var cleanup: String? = null
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        try {
            currentCoroutineContext().ensureActive()
            process = owner.start(spec)
            val active = process
            val lines = Channel<Pair<String, Stream>>(256)
            val consumer = pipes.async {
                for ((line, stream) in lines) {
                    (if (stream == Stream.STDOUT) stdout else stderr).add(line)
                    onLine(line, stream)
                }
            }
            val out = pipes.async { active.stdout.collect { lines.send(it to Stream.STDOUT) } }
            val err = pipes.async { active.stderr.collect { lines.send(it to Stream.STDERR) } }
            drain = pipes.async {
                try { out.await(); err.await() } finally { lines.close() }
                consumer.await()
            }
            exit = pipes.async { active.awaitExit() }
            suspend fun finish() {
                // Fail immediately on reader/parser errors, even if the process never exits.
                select<Unit> {
                    exit.onAwait { code = it }
                    drain.onAwait { code = exit.await() }
                    consumer.onAwait { code = exit.await() }
                }
                if (withTimeoutOrNull(drainMs) { drain.await(); true } == null) {
                    termination = Termination.OUTPUT_INCOMPLETE
                    throw Exception("Output did not reach EOF within drain limit")
                }
            }
            if (timeoutMs == null) finish() else if (withTimeoutOrNull(timeoutMs) { finish(); true } == null) {
                termination = Termination.TIMED_OUT
                throw Exception("Body timeout")
            }
        } catch (e: Throwable) {
            termination = when {
                e is CancellationException -> Termination.CANCELLED
                process == null -> Termination.START_FAILED
                termination != Termination.NORMAL -> termination
                else -> Termination.OUTPUT_INCOMPLETE
            }
            error = e.message ?: e.toString()
            withContext(NonCancellable) {
                try { process?.let { cleanupProcess(it, cleanupMs) } }
                catch (failure: Exception) {
                    cleanup = failure.stackTraceToString()
                    try { withTimeout(cleanupMs) { owner.cancelAll() } } catch (_: Exception) { }
                }
                try { withTimeout(drainMs) { exit?.let { code = it.await() }; drain?.await() } }
                catch (failure: Exception) { if (failure is RecordingException) throw failure }
            }
            if (e is RecordingException) throw e
        } finally {
            withContext(NonCancellable) {
                pipeJob.cancel()
                if (withTimeoutOrNull(cleanupMs) { pipeJob.join(); true } != true) {
                    cleanup = listOfNotNull(cleanup, "Process I/O tasks did not stop within cleanup limit").joinToString("; ")
                    owner.stopStarts()
                }
                if (cleanup == null) process?.let { owner.release(it) }
            }
        }
        return CommandResult(code, termination, stdout.toList(), stderr.toList(), mark.elapsedNow().inWholeMilliseconds, error, cleanup, process != null)
    }
}
