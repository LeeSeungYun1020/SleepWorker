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
    override suspend fun candidates(binary: String): List<String> = candidates(binary, ProcessProbe { captureProcess(executor, it) })
    override suspend fun candidates(binary: String, probe: ProcessProbe): List<String> = buildList {
        detect(binary, probe)?.let(::add)
        val home = System.getProperty("user.home")
        val paths = if (binary == "codex") listOf(
            "/Applications/Codex.app/Contents/Resources/codex", "/Applications/ChatGPT.app/Contents/Resources/codex-cli/bin/codex",
            "$home/.local/bin/codex", "/opt/homebrew/bin/codex", "/usr/local/bin/codex")
        else listOf("$home/.local/bin/agy", "/opt/homebrew/bin/agy", "/usr/local/bin/agy")
        addAll(paths.filter { Files.isExecutable(java.nio.file.Path.of(it)) })
        if (binary == "codex") {
            // Finder does not load NVM's interactive shell profile. Discover its installed
            // launchers and native binaries as explicit candidates, never as defaults.
            val nvm = java.nio.file.Path.of(home, ".nvm", "versions", "node")
            if (Files.isDirectory(nvm)) Files.list(nvm).use { versions ->
                versions.sorted().limit(32).forEach { version ->
                    val launcher = version.resolve("bin/codex")
                    if (Files.isExecutable(launcher)) add(launcher.toString())
                }
            }
            toList().forEach { candidate ->
                try {
                    val root = java.nio.file.Path.of(candidate).toRealPath().parent.parent
                    val arm = System.getProperty("os.arch") in listOf("aarch64", "arm64")
                    val arch = if (arm) "arm64" else "x64"
                    val target = if (arm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
                    listOf("node_modules/@openai/codex-darwin-$arch/vendor/$target/bin/codex", "vendor/$target/codex/codex").forEach { relative ->
                        val native = root.resolve(relative)
                        if (Files.isExecutable(native)) add(native.toString())
                    }
                } catch (_: Exception) { }
            }
        }
    }.distinct()
    override suspend fun detect(binary: String): String? = detect(binary, ProcessProbe { captureProcess(executor, it) })
    override suspend fun detect(binary: String, probe: ProcessProbe): String? {
        require(Regex("[A-Za-z0-9_.-]+").matches(binary))
        val result = probe.capture(ProcessSpec(listOf("/bin/zsh", "-lc", "command -v -- '$binary'"), System.getProperty("user.home")))
        return result.stdout.lastOrNull()?.takeIf { result.exitCode == 0 && it.startsWith('/') }
    }
}
class MacNotifier(private val executor: ProcessExecutor) : Notifier {
    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
    override suspend fun notify(title: String, body: String) = notify(title, body, "")
    override suspend fun notify(title: String, body: String, subtitle: String) {
        val result = captureProcess(executor, ProcessSpec(listOf("/usr/bin/osascript", "-e", "display notification ${quote(body)} with title ${quote(title)} subtitle ${quote(subtitle)}"), System.getProperty("user.home")))
        check(result.exitCode == 0) { result.stderr.joinToString("\n") }
    }
}
fun desktopPlatform(): Platform {
    val executor = JvmProcessExecutor()
    return Platform(executor, FileSystem.SYSTEM, JvmTempFiles(), MacNotifier(executor), ZshPathDetector(executor), JvmRepositoryLock(),
        System.getProperty("user.home").toPath() / "Library" / "Application Support" / "aiflow" / "settings.json", JvmFileDialogs(), JvmAppLog::write)
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
