package sleepworker.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Injection point for deterministic permission-denial and timeout tests. */
interface ProcessControl {
    fun descendants(handle: ProcessHandle): List<ProcessHandle> = handle.descendants().use { it.toList() }
    fun destroy(handle: ProcessHandle, force: Boolean): Boolean = if (force) handle.destroyForcibly() else handle.destroy()
}
class JvmProcessExecutor(private val control: ProcessControl = object : ProcessControl {}, private val cleanupTimeoutMs: Long = 3_000) : ProcessExecutor {
    override fun start(spec: ProcessSpec): RunningProcess {
        require(spec.command.isNotEmpty())
        val process = ProcessBuilder(spec.command).directory(java.io.File(spec.cwd)).apply { environment().putAll(spec.env) }.start()
        return JvmRunningProcess(process, spec.stdin, control, cleanupTimeoutMs)
    }
}
private class JvmRunningProcess(
    private val process: Process, stdin: String?, private val control: ProcessControl, private val cleanupTimeoutMs: Long,
) : RunningProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val out = Channel<String>(Channel.UNLIMITED)
    private val err = Channel<String>(Channel.UNLIMITED)
    override val stdout = out.receiveAsFlow()
    override val stderr = err.receiveAsFlow()
    private val handles = ConcurrentHashMap<Long, ProcessHandle>().apply { put(process.pid(), process.toHandle()) }
    private val stopping = AtomicBoolean(false)
    private val cleanupMutex = kotlinx.coroutines.sync.Mutex()
    private fun read(input: InputStream, output: Channel<String>) = scope.launch {
        try { input.bufferedReader(Charsets.UTF_8).use { reader -> while (true) output.send(reader.readLine() ?: break) }; output.close() }
        catch (t: Throwable) { output.close(t) }
    }
    private val readers = listOf(read(process.inputStream, out), read(process.errorStream, err))
    private val writer = scope.launch {
        try { process.outputStream.bufferedWriter(Charsets.UTF_8).use { if (stdin != null) it.write(stdin) } }
        catch (t: Exception) { err.close(t) }
    }
    private val tracking = scope.launch {
        while (isActive) {
            try {
                handles.values.toList().filter { it.isAlive }.forEach { parent ->
                    control.descendants(parent).forEach { child ->
                        handles[child.pid()] = child
                        if (stopping.get()) control.destroy(child, true)
                    }
                }
            } catch (_: SecurityException) { /* killTreeAndWait will report denied enumeration. */ }
            delay(10)
        }
    }
    init {
        scope.launch {
            process.waitFor()
            readers.joinAll()
            writer.join()
            tracking.cancel()
            scope.cancel()
        }
    }
    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        process.waitFor()
    }
    override suspend fun killTreeAndWait() = withContext(NonCancellable + Dispatchers.IO) {
        cleanupMutex.lock()
        try {
            stopping.set(true)
            val errors = mutableListOf<Throwable>()
            val deadline = System.nanoTime() + cleanupTimeoutMs * 1_000_000
            var force = false
            do {
                handles.values.toList().filter { it.isAlive }.forEach { parent ->
                    try { control.descendants(parent).forEach { handles[it.pid()] = it } } catch (t: Exception) { errors.add(t) }
                }
                val alive = handles.values.filter { it.isAlive }.sortedBy { it.pid() == process.pid() }
                alive.forEach { handle ->
                    try { if (!control.destroy(handle, force) && handle.isAlive) errors.add(IllegalStateException("Termination denied: ${handle.pid()}")) }
                    catch (t: Exception) { errors.add(t) }
                }
                if (handles.values.none { it.isAlive }) break
                delay(20)
                force = true
            } while (System.nanoTime() < deadline)
            if (handles.values.any { it.isAlive }) errors.add(IllegalStateException("Process cleanup timed out"))
            if (withTimeoutOrNull(cleanupTimeoutMs) { readers.joinAll(); true } != true) {
                errors.add(IllegalStateException("Output drain timed out"))
            }
            // Preserve queued evidence even when cleanup fails; close channels with an explicit I/O error.
            if (errors.isNotEmpty()) {
                val failure = ProcessCleanupException("Process cleanup failed", errors.first())
                errors.drop(1).forEach(failure::addSuppressed)
                out.close(failure); err.close(failure)
                // Closing a ProcessPipeInputStream synchronously can block behind readLine.
                scope.launch { runCatching { process.inputStream.close() } }
                scope.launch { runCatching { process.errorStream.close() } }
                throw failure
            }
            tracking.cancel()
        } finally { cleanupMutex.unlock() }
    }
}
