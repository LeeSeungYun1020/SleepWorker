package sleepworker.storage

class RunHistory(private val recorder: RunRecorder) {
    suspend fun list() = recorder.list()
}
