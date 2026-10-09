package aiflow.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.toList
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption

class JvmTempFiles : TempFiles {
    override fun createScript(content: String): Path {
        val path = Files.createTempFile("aiflow-", ".zsh")
        try {
            Files.writeString(path, content)
            Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            return path.toString().toPath()
        } catch (t: Throwable) { Files.deleteIfExists(path); throw t }
    }
    override fun delete(path: Path) { Files.deleteIfExists(java.nio.file.Path.of(path.toString())) }
}
class JvmRepositoryLock : RepositoryLock {
    override fun acquire(repoPath: Path): RepositoryLease {
        val canonical = java.nio.file.Path.of(repoPath.toString()).toRealPath()
        val metadata = canonical.resolve(".aiflow")
        require(!Files.isSymbolicLink(metadata)) { "Repository metadata cannot be a symlink" }
        Files.createDirectories(metadata)
        val lockPath = metadata.resolve("app.lock")
        require(!Files.isSymbolicLink(lockPath)) { "Lock cannot be a symlink" }
        val channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val lock = try { channel.tryLock() ?: error("Repository already owned by another app") } catch (t: Throwable) { channel.close(); throw t }
        return object : RepositoryLease {
            override val writeMutex = kotlinx.coroutines.sync.Mutex()
            override val repoPath = canonical.toString().toPath()
            override fun requireHeld() { check(lock.isValid && channel.isOpen) { "Repository ownership lost" } }
            override fun release() { try { if (lock.isValid) lock.release() } finally { channel.close() } }
        }
    }
}
class ZshPathDetector(private val executor: ProcessExecutor) : PathDetector {
    override suspend fun detect(binary: String): String? = detect(binary, ProcessProbe { captureProcess(executor, it) })
    override suspend fun detect(binary: String, probe: ProcessProbe): String? {
        require(Regex("[A-Za-z0-9_.-]+").matches(binary))
        val result = probe.capture(ProcessSpec(listOf("/bin/zsh", "-lc", "command -v -- '$binary'"), System.getProperty("user.home")))
        return result.stdout.lastOrNull()?.takeIf { result.exitCode == 0 && it.startsWith('/') }
    }
}
class MacNotifier(private val executor: ProcessExecutor) : Notifier {
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    override suspend fun notify(title: String, body: String) {
        val result = captureProcess(executor, ProcessSpec(listOf("/usr/bin/osascript", "-e", "display notification ${quote(body)} with title ${quote(title)}"), System.getProperty("user.home")))
        check(result.exitCode == 0) { result.stderr.joinToString("\n") }
    }
}
fun desktopPlatform(): Platform {
    val executor = JvmProcessExecutor()
    return Platform(executor, FileSystem.SYSTEM, JvmTempFiles(), MacNotifier(executor), ZshPathDetector(executor), JvmRepositoryLock(),
        System.getProperty("user.home").toPath() / "Library" / "Application Support" / "aiflow" / "settings.json", JvmFileDialogs())
}

class JvmFileDialogs : FileDialogs {
    private suspend fun pick(mode: Int, directory: Boolean, name: String? = null): String? = withContext(Dispatchers.Swing) {
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, if (directory) "저장소 선택" else "YAML 파일", mode)
        try {
            System.setProperty(property, directory.toString())
            if (name != null) dialog.file = name
            dialog.isVisible = true
            dialog.file?.let { java.io.File(dialog.directory, it).absolutePath }
        } finally {
            dialog.dispose()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
    override suspend fun directory() = pick(java.awt.FileDialog.LOAD, true)
    override suspend fun openYaml() = pick(java.awt.FileDialog.LOAD, false)
    override suspend fun saveYaml(suggestedName: String) = pick(java.awt.FileDialog.SAVE, false, suggestedName)
}
