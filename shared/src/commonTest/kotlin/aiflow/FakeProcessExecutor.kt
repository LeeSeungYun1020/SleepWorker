package aiflow

import aiflow.platform.*
import kotlinx.coroutines.flow.asFlow

data class FakeResult(val stdout: List<String> = emptyList(), val stderr: List<String> = emptyList(), val exitCode: Int = 0, val cleanupFailure: Exception? = null, val delayMs: Long = 0)
class FakeProcessExecutor(vararg results: FakeResult) : ProcessExecutor {
    private val results = ArrayDeque(results.toList())
    val requests = mutableListOf<ProcessSpec>()
    var killed = 0
    override fun start(spec: ProcessSpec): RunningProcess {
        requests.add(spec)
        val result = results.removeFirst()
        return object : RunningProcess {
            override val stdout = result.stdout.asFlow()
            override val stderr = result.stderr.asFlow()
            override suspend fun awaitExit(): Int { kotlinx.coroutines.delay(result.delayMs); return result.exitCode }
            override suspend fun killTreeAndWait() { killed++; result.cleanupFailure?.let { throw it } }
        }
    }
}
