package sleepworker.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A per-run owner. Once cancelled (including cleanup failure), it can never start more work. */
class ManagedProcessRunner(private val executor: ProcessExecutor, private val cleanupTimeoutMs: Long = 5_000) {
    private val mutex = Mutex()
    private val processes = mutableListOf<RunningProcess>()
    private var stopped = false
    suspend fun start(spec: ProcessSpec): RunningProcess = withContext(NonCancellable) {
        mutex.withLock {
            check(!stopped) { "Run has stopped; additional execution is blocked" }
            executor.start(spec).also(processes::add)
        }
    }
    suspend fun stopStarts() = mutex.withLock { stopped = true }
    suspend fun release(process: RunningProcess) = mutex.withLock { processes.remove(process); Unit }
    suspend fun cancelAll() = withContext(NonCancellable) {
        val owned = mutex.withLock { stopped = true; processes.toList() }
        val errors = mutableListOf<Exception>()
        owned.forEach { try { cleanupProcess(it, cleanupTimeoutMs) } catch (e: Exception) { errors.add(e) } }
        if (errors.isNotEmpty()) throw ProcessCleanupException("Run cleanup failed", errors.first()).also { failure -> errors.drop(1).forEach(failure::addSuppressed) }
    }
}

/** A refusing platform process must not pin the run's control coroutine indefinitely. */
suspend fun cleanupProcess(process: RunningProcess, timeoutMs: Long) {
    val job = SupervisorJob()
    val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + job)
    val cleanup = scope.async { process.killTreeAndWait() }
    try {
        if (withTimeoutOrNull(timeoutMs) { cleanup.await(); true } != true)
            throw ProcessCleanupException("Process cleanup timed out after ${timeoutMs}ms; termination unconfirmed")
    } finally {
        // A platform call may itself be NonCancellable. Do not join it without a bound.
        job.cancel()
    }
}
