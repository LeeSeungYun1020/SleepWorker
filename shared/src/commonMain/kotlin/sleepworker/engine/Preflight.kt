package sleepworker.engine

import sleepworker.model.*
import sleepworker.platform.*
import sleepworker.provider.*
import sleepworker.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okio.Path.Companion.toPath

fun interface Preflight {
    suspend fun check(version: WorkflowVersion, settings: AppSettings, io: ExecutionIO)
    companion object { val None = Preflight { _, _, _ -> } }
}

@Serializable enum class PreflightStatus { OK, INFO, WARN, ERROR }
@Serializable data class PreflightItem(val name: String, val status: PreflightStatus, val detail: String)
@Serializable data class PreflightReport(val version: WorkflowVersion, val settings: AppSettings, val items: List<PreflightItem>, val metadata: Map<Provider, Pair<String?, String?>>) {
    val passed get() = items.none { it.status == PreflightStatus.ERROR }
}

/** Read-only probes; never substitutes a model, changes authentication, or creates a worktree. */
class CliPreflight(private val platform: Platform, private val registry: ProviderRegistry = ProviderRegistry()) {
    suspend fun inspect(version: WorkflowVersion, settings: AppSettings, io: ExecutionIO? = null): PreflightReport {
        val items = mutableListOf<PreflightItem>()
        val metadata = mutableMapOf<Provider, Pair<String?, String?>>()
        var resolved = settings
        val workflow = version.workflow
        // Preview uses its own owner; execution uses the run's owner and durable IO.
        val previewRunner = if (io == null) CommandRunner(ManagedProcessRunner(platform.processes)) else null
        var fatal: Exception? = null
        var commandNo = 0
        val processes = ProcessProbe { spec ->
            fatal?.let { throw it }
            val result = try {
                if (io != null) io.command("preflight/probes/${++commandNo}", spec, 30_000)
                else previewRunner!!.run(spec, 30_000)
            } catch (e: RecordingException) { fatal = e; throw e }
            result.cleanupError?.let { fatal = UnsafeCleanup(it); throw fatal!! }
            currentCoroutineContext().ensureActive()
            check(result.termination == Termination.NORMAL && result.exitCode != null) {
                result.error ?: "Probe failed: ${result.termination}"
            }
            CapturedProcess(result.exitCode, result.stdout, result.stderr)
        }

        fun item(name: String, status: PreflightStatus, detail: String) { items += PreflightItem(name, status, detail) }
        suspend fun probe(name: String, block: suspend () -> Unit) {
            try { withTimeout(30_000) { block() } }
            catch (e: TimeoutCancellationException) { item(name, PreflightStatus.ERROR, "확인 시간 초과 (30초)") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { item(name, PreflightStatus.ERROR, e.message ?: e.toString()) }
            // Adapters may describe ordinary auth failures, but must not hide unsafe cleanup/storage.
            fatal?.let { throw it }
        }
        try {
            WorkflowValidator(platform.files).validate(workflow).forEach {
                item("워크플로", if (it.severity == Severity.ERROR) PreflightStatus.ERROR else PreflightStatus.WARN, listOfNotNull(it.stepId, it.message).joinToString(": "))
            }
            val agents = workflow.steps.filter { it.effectiveKind == StepKind.AGENT }
            agents.groupBy { workflow.sessions[it.session?.ref]?.provider }.forEach { (provider, steps) ->
                if (provider == null) return@forEach
                probe(provider.name) {
                    val binary = if (provider == Provider.CODEX) "codex" else "agy"
                    val path = (if (provider == Provider.CODEX) settings.codexPath else settings.agyPath)
                        ?.takeIf { it.isNotBlank() } ?: platform.pathDetector.detect(binary, processes)
                    require(path != null && path.toPath().isAbsolute && platform.files.exists(path.toPath())) { "$binary 경로를 찾을 수 없음 — 설정 파일에서 절대 경로 지정" }
                    resolved = if (provider == Provider.CODEX) resolved.copy(codexPath = path) else resolved.copy(agyPath = path)
                    val output = processes.capture(ProcessSpec(listOf(path, "--version"), workflow.repoPath))
                    require(output.exitCode == 0) { "버전 확인 실패: ${output.stderr}" }
                    val actual = Regex("\\b\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?\\b").find(output.stdout.joinToString("\n"))?.value ?: error("버전 형식 확인 불가")
                    val contract = VerifiedCliContract.forVersion(provider, actual)
                    item(binary, if (contract == null) PreflightStatus.WARN else PreflightStatus.INFO,
                        "$path · $actual · ${contract?.id ?: "실측 기록 없음 — 실행 가능"}")
                    metadata[provider] = actual to contract?.id
                    steps.forEach { step ->
                        val modes = buildSet { add(step.session!!.mode); if (workflow.steps.any { s -> s.transitions.any { it.resetSession && (it.next as? sleepworker.model.Target.StepId)?.id == step.id } }) add(SessionMode.NEW) }
                        modes.forEach { mode -> contract?.validateRequest(actual, ExecRequest(workflow.repoPath, "", step.model, step.effort, if (mode == SessionMode.RESUME) "preflight" else null, path))?.forEach {
                            item(step.id, PreflightStatus.ERROR, it.detail)
                        } }
                        if (contract != null && step.effort?.let { contract.modelEfforts[ModelEffort(step.model.orEmpty(), it)]?.status } != Verification.VERIFIED) {
                            item(step.id, PreflightStatus.WARN, "${step.model} / ${step.effort} 실측 기록 없음 — 실행 가능")
                        }
                    }
                    val adapter = registry.adapterFor(steps.first(), workflow)
                    val cfg = ProviderConfig(path, workflow.repoPath, actual)
                    when (val auth = adapter.probeAuth(processes, cfg)) {
                        AuthStatus.LoggedIn -> item("$binary 인증", PreflightStatus.OK, "로그인 확인")
                        AuthStatus.LoggedOut -> item("$binary 인증", PreflightStatus.ERROR, "로그인 필요")
                        is AuthStatus.Unknown -> item("$binary 인증", PreflightStatus.ERROR, "인증 상태 확인 불가: ${auth.detail}")
                        AuthStatus.NotApplicable -> item("$binary 인증", PreflightStatus.ERROR, "인증 계약 확인 불가")
                    }
                    val models = if (provider == Provider.CODEX) settings.codexModels else adapter.listModels(processes, cfg)
                    steps.mapNotNull { it.model }.distinct().forEach { model ->
                        if (models == null || model !in models) item("모델", PreflightStatus.WARN, "$model 목록에서 확인되지 않음 — 요청한 모델로 실행")
                    }
                }
            }
            probe("저장소") {
                require(platform.files.metadataOrNull(workflow.repoPath.toPath())?.isDirectory == true) { "저장소 경로 없음" }
                suspend fun git(vararg args: String): CapturedProcess = processes.capture(ProcessSpec(listOf("git") + args, workflow.repoPath))
                require(git("rev-parse", "--is-inside-work-tree").let { it.exitCode == 0 && it.stdout.firstOrNull() == "true" }) { "Git 저장소가 아님" }
                val dirty = git("status", "--porcelain")
                require(dirty.exitCode == 0) { "Git 상태 확인 실패" }
                item("저장소", if (dirty.stdout.isEmpty()) PreflightStatus.OK else PreflightStatus.WARN, if (dirty.stdout.isEmpty()) "clean" else "커밋되지 않은 변경 있음")
                val listed = git("worktree", "list", "--porcelain")
                require(listed.exitCode == 0) { "워크트리 조회 실패" }
                val entries = listed.stdout.joinToString("\n").split("\n\n").map { block -> block.lines().associate { it.substringBefore(' ') to it.substringAfter(' ', "") } }
                workflow.worktrees.forEach { def ->
                    require(git("check-ref-format", "--branch", def.branch).exitCode == 0) { "잘못된 브랜치: ${def.branch}" }
                    val target = WorkspacePaths(platform.files).resolve(workflow, Workspace.Worktree(def.name))
                    val entry = entries.firstOrNull { it["worktree"]?.toPath(normalize = true) == target }
                    if (entry != null) {
                        require(entry["branch"] == "refs/heads/${def.branch}" && platform.files.metadataOrNull(target)?.isDirectory == true) { "워크트리 경로/브랜치 불일치: $target" }
                        item(def.name, PreflightStatus.INFO, "재사용: $target · ${def.branch}")
                    } else {
                        require(!platform.files.exists(target)) { "미등록 워크트리 경로 존재: $target" }
                        val occupied = entries.any { it["branch"] == "refs/heads/${def.branch}" }
                        require(!occupied) { "브랜치가 다른 워크트리에서 사용 중: ${def.branch}" }
                        val branch = git("show-ref", "--verify", "--quiet", "refs/heads/${def.branch}")
                        require(branch.exitCode in listOf(0, 1)) { "브랜치 조회 실패: ${def.branch}" }
                        if (branch.exitCode == 0) item(def.name, PreflightStatus.WARN, "기존 브랜치 재사용: ${def.branch}")
                    }
                }
            }
            for (binary in listOf("git", "gh")) probe(binary) {
                val path = platform.pathDetector.detect(binary, processes)
                item(binary, if (path != null) PreflightStatus.OK else if (binary == "gh") PreflightStatus.WARN else PreflightStatus.ERROR, path ?: "$binary 없음")
            }
            return PreflightReport(version, resolved, items.toList(), metadata.toMap())
        } catch (e: Exception) {
            item("프로브 실행", PreflightStatus.ERROR, fatal?.message ?: e.message ?: e.toString())
            throw e
        } finally {
            if (io != null) withContext(NonCancellable) {
                io.recorder.write(io.runId, "preflight/report.json",
                    io.recorder.json.encodeToString(PreflightReport(version, resolved, items.toList(), metadata.toMap())), immutable = true)
            }
        }
    }
}
