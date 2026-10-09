package aiflow.engine

import aiflow.platform.ProcessSpec
import aiflow.provider.*
import aiflow.storage.RunRecorder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlin.time.Clock
import kotlinx.serialization.encodeToString

class ExecutionIO(val runner: CommandRunner, val recorder: RunRecorder, val runId: String, val logs: MutableSharedFlow<LogLine>) {
    suspend fun command(path: String, spec: ProcessSpec, timeoutMs: Long?, visitNo: Int? = null, attemptNo: Int? = null,
                        onLine: suspend (String, Stream) -> Unit = { _, _ -> }): CommandResult {
        recorder.write(runId, "$path/command.txt", recorder.json.encodeToString(spec.command))
        recorder.write(runId, "$path/stdout.log", "")
        recorder.write(runId, "$path/stderr.log", "")
        val contextVisit = visitNo ?: path.split('/').let { parts ->
            if (parts.firstOrNull() == "visits") parts.getOrNull(1)?.substringBefore('-')?.toIntOrNull() else null
        }
        val contextAttempt = attemptNo ?: path.split('/').let { parts ->
            val index = parts.indexOf("attempts")
            if (index >= 0) parts.getOrNull(index + 1)?.toIntOrNull() else null
        }
        val result = runner.run(spec, timeoutMs) { text, stream ->
            recorder.append(runId, "$path/${if (stream == Stream.STDOUT) "stdout" else "stderr"}.log", "$text\n")
            val line = LogLine(contextVisit, contextAttempt, path, stream, text, Clock.System.now())
            recorder.append(runId, "logs.jsonl", kotlinx.serialization.json.Json.encodeToString(line) + "\n")
            logs.tryEmit(line)
            onLine(text, stream)
        }
        withContext(NonCancellable) { recorder.write(runId, "$path/process.json", recorder.json.encodeToString(result), immutable = true) }
        return result
    }
}
