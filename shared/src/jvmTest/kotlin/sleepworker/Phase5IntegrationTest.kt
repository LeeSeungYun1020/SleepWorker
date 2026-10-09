package sleepworker

import sleepworker.engine.*
import sleepworker.platform.*
import sleepworker.storage.*
import sleepworker.ui.history.*
import sleepworker.ui.settings.*
import kotlinx.coroutines.*
import okio.FileSystem
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class Phase5IntegrationTest {
    @Test fun worktreeDeletionProtectsMainDirtyAndLockedAndKeepsBranch() = runBlocking {
        val dir = Files.createTempDirectory("sleepworker-phase5-worktrees").toFile()
        val repo = dir.resolve("repo").apply { mkdir() }.canonicalPath
        val exec = JvmProcessExecutor()
        suspend fun git(vararg args: String) = captureProcess(exec, ProcessSpec(listOf("git") + args, repo)).also { assertEquals(0, it.exitCode, it.stderr.toString()) }
        git("init", "-b", "main")
        java.io.File(repo, "README").writeText("fixture")
        git("add", "."); git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture")
        val path = dir.resolve("worktree with spaces").canonicalPath
        git("worktree", "add", "-b", "topic", path)
        val lease = JvmRepositoryLock().acquire(repo.toPath())
        val history = HistoryViewModel(RunRecorder(FileSystem.SYSTEM, lease), exec)
        try {
            history.refreshWorktrees()
            assertFails { history.removeWorktree(history.worktrees.value.first(), true) }
            val entry = history.worktrees.value.last()
            assertFails { history.removeWorktree(entry, false) }
            java.io.File(path, "dirty").writeText("preserve")
            assertFails { history.removeWorktree(entry, true) }
            assertTrue(java.io.File(path, "dirty").exists())
            java.io.File(path, "dirty").delete()
            git("worktree", "lock", path); history.refreshWorktrees()
            assertFails { history.removeWorktree(history.worktrees.value.last(), true) }
            git("worktree", "unlock", path); history.refreshWorktrees()
            history.removeWorktree(history.worktrees.value.last(), true)
            assertFalse(java.io.File(path).exists())
            git("show-ref", "--verify", "refs/heads/topic"); Unit
        } finally { history.close(); lease.release(); dir.deleteRecursively() }
    }
    @Test fun notifierQuotesSubtitleAndControlCharactersAsArguments() = runBlocking {
        val executor = FakeProcessExecutor(FakeResult())
        MacNotifier(executor).notify(sleepworker.platform.APP_NAME, "quote\" slash\\ newline\n", "workflow\"name")
        val argv = executor.requests.single().command
        assertEquals(listOf("/usr/bin/osascript", "-e"), argv.take(2))
        assertTrue(argv[2].contains("subtitle \"workflow\\\"name\""))
        assertTrue(argv[2].contains("quote\\\" slash\\\\ newline\\n"))
    }
    @Test fun cliProbeWithoutNodePathReportsFailureAndNativeCandidateWorks() = runBlocking {
        val dir = Files.createTempDirectory("sleepworker-phase5-path").toFile()
        val nativeBinary = dir.resolve("native-codex").apply { writeText("#!/bin/sh\necho codex-cli 0.160.0\n"); setExecutable(true) }
        val failing = dir.resolve("launcher").apply { writeText("#!/usr/bin/env sleepworker-missing-runtime\n"); setExecutable(true) }
        val executor = object : ProcessExecutor {
            override fun start(spec: ProcessSpec) = JvmProcessExecutor().start(spec.copy(env = mapOf("PATH" to "/usr/bin:/bin")))
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vm = SettingsViewModel(desktopPlatform().copy(processes = executor, settingsPath = dir.resolve("settings.json").absolutePath.toPath(), pathDetector = object : PathDetector {
            override suspend fun detect(binary: String) = failing.absolutePath
            override suspend fun candidates(binary: String) = listOf(failing.absolutePath, nativeBinary.absolutePath)
        }), scope)
        try {
            vm.detect("codex").join()
            val choices = vm.candidates.value["codex"]!!
            assertNull(choices[0].version); assertEquals("0.160.0", choices[1].version)
            assertNull(vm.settings.value.codexPath)
        } finally { vm.close(); scope.cancel(); dir.deleteRecursively() }
    }
}
