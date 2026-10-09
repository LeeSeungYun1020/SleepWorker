package sleepworker

import sleepworker.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class JvmPlatformTest {
    private fun spec(body: String, stdin: String? = null) = ProcessSpec(listOf("/bin/sh", "-c", body), "/tmp", stdin)
    @Test fun echoStdinExitAndFinalOutput() = runBlocking {
        val executor = JvmProcessExecutor()
        assertEquals(listOf("hi"), captureProcess(executor, spec("echo hi")).stdout)
        assertEquals(emptyList(), captureProcess(executor, spec("cat")).stdout)
        assertEquals(listOf("  first", "second"), captureProcess(executor, spec("cat", "  first\nsecond\n")).stdout)
        val final = captureProcess(executor, spec("echo last; echo warning >&2; exit 7"))
        assertEquals(7, final.exitCode)
        assertEquals(listOf("last"), final.stdout)
        assertEquals(listOf("warning"), final.stderr)
    }
    @Test fun processExitDoesNotMeanOutputReachedEof() = runBlocking {
        val process = JvmProcessExecutor().start(spec("printf before; (sleep 0.5; printf after) & sleep 0.1; exit 0"))
        val output = async { process.stdout.toList() }
        val errors = async { process.stderr.toList() }
        assertEquals(0, withTimeout(3000) { process.awaitExit() })
        assertFalse(output.isCompleted)
        assertEquals(listOf("beforeafter"), withTimeout(3000) { output.await() })
        assertTrue(errors.await().isEmpty())
    }
    @Test fun concurrentLargeOutputDoesNotDeadlock() = runBlocking {
        val result = captureProcess(JvmProcessExecutor(), spec("i=0; while [ \"\$i\" -lt 10000 ]; do echo out-\$i; echo err-\$i >&2; i=\$((i+1)); done"))
        assertEquals(10000, result.stdout.size)
        assertEquals(10000, result.stderr.size)
        assertEquals("out-9999", result.stdout.last())
    }
    @Test fun childTreeIsKilledAndPipesEnd() = runBlocking {
        val process = JvmProcessExecutor().start(spec("sleep 60 & echo \$!; wait"))
        val lines = mutableListOf<String>()
        val out = async { process.stdout.collect { lines.add(it) } }
        val err = async { process.stderr.toList() }
        withTimeout(3000) { while (lines.isEmpty()) delay(10) }
        val child = lines.first().toLong()
        delay(50)
        process.killTreeAndWait()
        withTimeout(4000) { out.await(); err.await(); process.awaitExit() }
        assertFalse(ProcessHandle.of(child).map { it.isAlive }.orElse(false))
    }
    @Test fun deniedCleanupPreservesEvidenceAndBlocksNewWork() = runBlocking {
        val observed = mutableListOf<ProcessHandle>()
        val control = object : ProcessControl {
            override fun destroy(handle: ProcessHandle, force: Boolean): Boolean { observed.add(handle); throw SecurityException("injected denial") }
        }
        val runner = ManagedProcessRunner(JvmProcessExecutor(control, 100))
        val process = runner.start(spec("echo retained; exec sleep 60"))
        val lines = mutableListOf<String>()
        val out = async { runCatching { process.stdout.collect { lines.add(it) } } }
        val err = async { runCatching { process.stderr.toList() } }
        try {
            withTimeout(3000) { while (lines.isEmpty()) delay(10) }
            assertFailsWith<ProcessCleanupException> { runner.cancelAll() }
            assertFailsWith<IllegalStateException> { runner.start(spec("echo forbidden")) }
            assertEquals(listOf("retained"), lines)
            withTimeout(2000) { out.await(); err.await() }
        } finally { observed.distinctBy { it.pid() }.forEach { it.destroyForcibly() } }
        assertNotEquals(0, withTimeout(2000) { process.awaitExit() })
    }
    @Test fun cleanupTimeoutIsExplicit() = runBlocking {
        val observed = mutableListOf<ProcessHandle>()
        val control = object : ProcessControl { override fun destroy(handle: ProcessHandle, force: Boolean): Boolean { observed.add(handle); return true } }
        val process = JvmProcessExecutor(control, 80).start(spec("exec sleep 60"))
        try { assertFailsWith<ProcessCleanupException> { process.killTreeAndWait() } }
        finally { observed.distinctBy { it.pid() }.forEach { it.destroyForcibly() } }
        assertNotEquals(0, withTimeout(2000) { process.awaitExit() })
    }
    @Test fun lockRejectsSecondOwnerAndReleases() {
        val dir = Files.createTempDirectory("sleepworker-lock-test")
        val locks = JvmRepositoryLock()
        try {
            val lease = locks.acquire(dir.toString().toPath())
            try { assertFails { JvmRepositoryLock().acquire(dir.resolve(".").toString().toPath()) } } finally { lease.release() }
            assertFails { lease.requireHeld() }
            locks.acquire(dir.toString().toPath()).release()
            assertTrue(Files.exists(dir.resolve(".sleepworker/app.lock")))
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun tempScriptPreservesContentAndCleansUp() {
        val files = JvmTempFiles()
        val content = "  echo one\necho two\n"
        val path = files.createScript(content)
        assertEquals(content, java.io.File(path.toString()).readText())
        assertTrue(java.io.File(path.toString()).canExecute())
        files.delete(path)
        assertFalse(java.io.File(path.toString()).exists())
    }
    @Test fun operatingSystemReleasesLockWhenOwningProcessExits() {
        val dir = Files.createTempDirectory("sleepworker-lock-process")
        val process = ProcessBuilder(
            System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("sleepworker.testClasspath"),
            "sleepworker.RepositoryLockProbe", dir.toString(),
        ).redirectErrorStream(true).start()
        try {
            val ready = java.util.concurrent.CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            assertEquals("OWNED", ready.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFails { JvmRepositoryLock().acquire(dir.toString().toPath()) }
            process.destroyForcibly()
            assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
            JvmRepositoryLock().acquire(dir.toString().toPath()).release()
        } finally {
            process.destroyForcibly()
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        }
    }
}

/** Standalone child used to verify that kernel locks release even without application cleanup. */
object RepositoryLockProbe {
    @JvmStatic fun main(args: Array<String>) {
        val lease = JvmRepositoryLock().acquire(args.single().toPath())
        println("OWNED")
        System.out.flush()
        System.`in`.read()
        lease.release()
    }
}
