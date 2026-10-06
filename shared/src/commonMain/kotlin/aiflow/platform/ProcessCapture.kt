package aiflow.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

data class CapturedProcess(val exitCode: Int, val stdout: List<String>, val stderr: List<String>)
/** Bounded probe execution, including both EOFs. Engine execution has its own per-attempt lifecycle. */
suspend fun captureProcess(executor: ProcessExecutor, spec: ProcessSpec, timeoutMs: Long = 15_000): CapturedProcess {
    val process = executor.start(spec)
    try {
        return withTimeout(timeoutMs) {
            coroutineScope {
                val stdout = mutableListOf<String>()
                val stderr = mutableListOf<String>()
                val out = launch { process.stdout.collect { stdout.add(it) } }
                val err = launch { process.stderr.collect { stderr.add(it) } }
                val code = process.awaitExit()
                out.join(); err.join()
                CapturedProcess(code, stdout, stderr)
            }
        }
    } catch (t: Throwable) {
        try { withContext(NonCancellable) { withTimeout(5_000) { process.killTreeAndWait() } } } catch (cleanup: Throwable) { t.addSuppressed(cleanup) }
        throw t
    }
}
