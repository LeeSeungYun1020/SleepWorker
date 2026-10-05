package aiflow.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A per-run owner. Once cancelled (including cleanup failure), it can never start more work. */
class ManagedProcessRunner(private val executor: ProcessExecutor) {
    private val mutex = Mutex()
    private val processes = mutableListOf<RunningProcess>()
    private var stopped = false
    suspend fun start(spec: ProcessSpec): RunningProcess = withContext(NonCancellable) {
        mutex.withLock {
            check(!stopped) { "Run has stopped; additional execution is blocked" }
            executor.start(spec).also(processes::add)
        }
    }
    suspend fun cancelAll() = withContext(NonCancellable) {
        val owned = mutex.withLock { stopped = true; processes.toList() }
        val errors = mutableListOf<Exception>()
        owned.forEach { try { it.killTreeAndWait() } catch (e: Exception) { errors.add(e) } }
        if (errors.isNotEmpty()) throw ProcessCleanupException("Run cleanup failed", errors.first()).also { failure -> errors.drop(1).forEach(failure::addSuppressed) }
    }
}
