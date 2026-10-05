package aiflow

import aiflow.model.*
import aiflow.platform.*
import aiflow.provider.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProviderTest {
    private val req = ExecRequest("/repo/worktree", "  prompt\nunchanged\n", "model", Effort.LOW, null, "/custom/bin")
    @Test fun exactCommandsAndNoPromptMutation() {
        val codex = CodexAdapter()
        val prefix = listOf("/custom/bin", "exec", "--json", "-C", req.cwd, "-m", "model", "-c", "model_reasoning_effort=low", "-c", "sandbox_mode=danger-full-access", "-c", "approval_policy=never")
        assertEquals(ProcessSpec(prefix + "-", req.cwd, req.script), codex.buildCommand(req))
        assertEquals(prefix + listOf("resume", "session", "-"), codex.buildCommand(req.copy(resumeSessionId = "session")).command)
        val agy = AntigravityAdapter()
        val command = listOf("/custom/bin", "-p", req.script, "--output-format", "json", "--model", "model", "--effort", "low", "--dangerously-skip-permissions")
        assertEquals(ProcessSpec(command, req.cwd), agy.buildCommand(req))
        assertEquals(command + listOf("--conversation", "session"), agy.buildCommand(req.copy(resumeSessionId = "session")).command)
        assertFailsWith<ProviderConfigException> { codex.buildCommand(req.copy(resumeSessionId = "")) }
        val shell = ShellAdapter()
        assertEquals(listOf("/bin/zsh", "-lc", " echo ok "), shell.buildCommand(req.copy(script = " echo ok ")).command)
        assertEquals(listOf("/bin/zsh", "-l", "/tmp/script"), shell.buildCommand(req.copy(scriptFilePath = "/tmp/script")).command)
        assertFailsWith<ProviderConfigException> { shell.buildCommand(req) }
        val step = Step("x", " !echo ok", session = SessionRef("s", SessionMode.NEW))
        assertIs<ShellAdapter>(ProviderRegistry().adapterFor(step, workflow(step)))
    }
    private fun parse(adapter: ProviderAdapter, lines: List<String>, code: Int? = 0, termination: Termination = Termination.NORMAL): ProviderReport {
        val parser = adapter.createParser(req)
        lines.forEach { parser.accept(it, Stream.STDOUT) }
        return parser.finalizeOutput(code, termination)
    }
    private val codexSuccess = listOf("""{"type":"thread.started","thread_id":"id"}""", """{"type":"item.completed","item":{"type":"agent_message","text":"ok"}}""", """{"type":"turn.completed"}""")
    @Test fun codexSuccessFailurePrecedenceAndIsolation() {
        val adapter = CodexAdapter()
        val parser = adapter.createParser(req)
        assertIs<AgentEvent.Diagnostic>(parser.accept("error is a word in this warning", Stream.STDERR).single())
        assertIs<AgentEvent.Raw>(parser.accept("ordinary error log", Stream.STDOUT).single())
        val events = codexSuccess.flatMap { parser.accept(it, Stream.STDOUT) }
        assertEquals(1, events.count { it is AgentEvent.SessionStarted })
        assertEquals(ProviderOutcome.SUCCEEDED, parser.finalizeOutput(0, Termination.NORMAL).outcome)
        assertFails { parser.finalizeOutput(0, Termination.NORMAL) }
        assertFails { parser.accept("late", Stream.STDOUT) }
        assertEquals(ProviderOutcome.FAILED, parse(adapter, codexSuccess + """{"type":"turn.failed","error":{"message":"no"}}""").outcome)
        assertEquals(ProviderOutcome.FAILED, parse(adapter, listOf("""{"type":"error","message":"no"}""") + codexSuccess).outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess.dropLast(1)).outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess.drop(1)).outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess + "{\"type\":").outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess + """{"type":"thread.started","thread_id":"other"}""").outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, emptyList()).outcome)
        assertEquals(ProviderOutcome.FAILED, parse(adapter, codexSuccess, 1).outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess, termination = Termination.OUTPUT_INCOMPLETE).outcome)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, codexSuccess, null).outcome)
    }
    @Test fun agyFinalEventsAndMandatoryResponse() {
        val adapter = AntigravityAdapter()
        val success = """{"conversation_id":"id","status":"SUCCESS","response":"ok"}"""
        val report = parse(adapter, listOf(success))
        assertEquals(ProviderOutcome.SUCCEEDED, report.outcome)
        assertEquals(listOf(AgentEvent.SessionStarted("id"), AgentEvent.Message("ok"), AgentEvent.Completed), report.finalEvents)
        listOf("", "{}", "{", """{"status":"SUCCESS","response":"ok"}""", """{"conversation_id":"id","status":"SUCCESS","response":""}""").forEach {
            assertEquals(ProviderOutcome.PROTOCOL_ERROR, parse(adapter, listOf(it)).outcome)
        }
        assertEquals(ProviderOutcome.FAILED, parse(adapter, listOf("""{"conversation_id":"id","status":"ERROR","error":"failed"}""")).outcome)
        assertEquals(ProviderOutcome.FAILED, parse(adapter, listOf("""{"status":"SUCCESS","error":"failed"}""")).outcome)
        val cancelled = parse(adapter, listOf("""{"status":"ERROR","error":"context canceled"}"""), 1, Termination.CANCELLED)
        assertEquals(ProviderOutcome.FAILED, cancelled.outcome) // caller keeps termination; not an automatic-retry signal.
        val parser = adapter.createParser(req.copy(resumeSessionId = "original"))
        parser.accept(success, Stream.STDOUT)
        assertEquals(ProviderOutcome.PROTOCOL_ERROR, parser.finalizeOutput(0, Termination.NORMAL).outcome)
        assertEquals(ProviderOutcome.NOT_APPLICABLE, parse(ShellAdapter(), emptyList()).outcome)
    }
    @Test fun authenticationUnknownIsNotLoggedOut() = runTest {
        val cfg = ProviderConfig("/bin/cli", "/repo")
        assertEquals(AuthStatus.LoggedIn, CodexAdapter().probeAuth(FakeProcessExecutor(FakeResult()), cfg))
        assertEquals(AuthStatus.LoggedOut, CodexAdapter().probeAuth(FakeProcessExecutor(FakeResult(stderr = listOf("Not logged in"), exitCode = 1)), cfg))
        assertIs<AuthStatus.Unknown>(CodexAdapter().probeAuth(FakeProcessExecutor(FakeResult(stderr = listOf("network error"), exitCode = 1)), cfg))
        val agy = AntigravityAdapter()
        listOf(CodexAdapter(), agy).forEach { adapter ->
            val delayed = FakeProcessExecutor(FakeResult(delayMs = 20_000))
            assertIs<AuthStatus.Unknown>(adapter.probeAuth(delayed, cfg))
            assertEquals(1, delayed.killed)
        }
        assertEquals(AuthStatus.LoggedIn, agy.probeAuth(FakeProcessExecutor(FakeResult(stdout = listOf("slug\tLabel"))), cfg))
        listOf(FakeResult(stderr = listOf("Not logged in"), exitCode = 1), FakeResult(stdout = listOf("partial")), FakeResult(stdout = emptyList())).forEach {
            assertIs<AuthStatus.Unknown>(agy.probeAuth(FakeProcessExecutor(it), cfg))
        }
        assertEquals(listOf("slug"), agy.listModels(FakeProcessExecutor(FakeResult(stdout = listOf("slug\tLabel"))), cfg))
        assertNull(agy.parseModels("slug\t"))
        assertNull(agy.parseModels("slug\tLabel\npartial"))
    }
    @Test fun contractDoesNotPromoteUnverifiedModelsOrFeatures() {
        val c = VerifiedCliContract.antigravity
        val measured = req.copy(model = "gemini-3.8-flash-low")
        assertTrue(c.validateRequest("1.2.16", measured).isEmpty())
        assertTrue(c.validateRequest("1.2.15", measured).isNotEmpty())
        assertTrue(c.validateRequest("1.2.16", measured.copy(effort = Effort.MAX)).isNotEmpty())
        assertTrue(c.validateRequest("1.2.16", measured, setOf("modelsJson")).isNotEmpty())
        assertTrue(c.validateRequest("1.2.16", measured, setOf("unauthenticated")).isNotEmpty())
        assertTrue(VerifiedCliContract.codex.validateRequest("0.160.0", req.copy(model = "gpt-6-luna")).isNotEmpty())
    }
    @Test fun cleanupFailureBlocksAdditionalExecution() = runTest {
        val fake = FakeProcessExecutor(FakeResult(stdout = listOf("evidence"), cleanupFailure = ProcessCleanupException("denied")))
        val runner = ManagedProcessRunner(fake)
        runner.start(ProcessSpec(listOf("fake"), "/repo"))
        assertFailsWith<ProcessCleanupException> { runner.cancelAll() }
        assertFailsWith<IllegalStateException> { runner.start(ProcessSpec(listOf("fake"), "/repo")) }
        assertEquals(1, fake.requests.size)
    }
    @Test fun explicitSessionRejectionPreservesIdAndDoesNotRetryAsNew() {
        val cases = listOf(
            CodexAdapter() to """{"type":"turn.failed","error":{"message":"Thread requested-id not found"}}""",
            AntigravityAdapter() to """{"status":"ERROR","error":"Conversation requested-id does not exist"}""",
        )
        cases.forEach { (adapter, line) ->
            val parser = adapter.createParser(req.copy(resumeSessionId = "requested-id"))
            parser.accept(line, Stream.STDOUT)
            val result = parser.finalizeOutput(1, Termination.NORMAL)
            assertEquals(ProviderOutcome.FAILED, result.outcome)
            assertEquals(FailureKind.SESSION_INVALID, result.failure?.kind)
            assertEquals("requested-id", result.sessionId)
        }
        val parser = CodexAdapter().createParser(req.copy(resumeSessionId = "id"))
        parser.accept("thread id not found in unrelated diagnostic", Stream.STDERR)
        codexSuccess.forEach { parser.accept(it, Stream.STDOUT) }
        assertEquals(ProviderOutcome.SUCCEEDED, parser.finalizeOutput(0, Termination.NORMAL).outcome)
    }
}
