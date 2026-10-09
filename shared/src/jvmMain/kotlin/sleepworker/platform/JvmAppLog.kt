package sleepworker.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

object JvmAppLog {
    private val path = Path.of(System.getProperty("user.home"), "Library", "Logs", "aiflow", "app.log")
    @Synchronized fun write(error: Throwable) {
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, "${Instant.now()}\n${error.stackTraceToString()}\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) { System.err.println(error.stackTraceToString()) }
    }
}
