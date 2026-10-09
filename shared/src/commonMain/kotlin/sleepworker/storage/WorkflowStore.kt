package sleepworker.storage

import sleepworker.model.*
import sleepworker.platform.RepositoryLease
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant
import okio.FileSystem
import okio.Path
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalUuidApi::class)
class WorkflowStore(
    private val fs: FileSystem,
    private val lease: RepositoryLease,
    private val codec: WorkflowCodec = WorkflowCodec(),
    private val validator: WorkflowValidator = WorkflowValidator(fs),
    private val newId: () -> String = { Uuid.random().toString() },
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val mutex = lease.writeMutex
    private val root = lease.repoPath / ".sleepworker" / "workflows"
    private fun id(value: String): String {
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)) { "Invalid application UUID" }
        return value
    }
    private fun directory(workflowId: String): Path {
        lease.requireHeld()
        val path = root / id(workflowId)
        // Reject symlink redirection at every existing boundary, including the repository metadata directory.
        var cursor = lease.repoPath
        listOf(".sleepworker", "workflows", workflowId, "versions").forEach {
            cursor /= it
            require(fs.metadataOrNull(cursor)?.symlinkTarget == null) { "Symlink storage path: $cursor" }
        }
        return path
    }
    private fun read(path: Path): String {
        require(fs.metadataOrNull(path)?.symlinkTarget == null) { "Symlink storage file" }
        return fs.read(path) { readUtf8() }
    }
    private fun atomicWrite(path: Path, content: String, immutable: Boolean = false) {
        lease.requireHeld()
        fs.createDirectories(path.parent!!)
        val temp = path.parent!! / ".${newId()}.tmp"
        try {
            fs.write(temp, mustCreate = true) { writeUtf8(content) }
            // OS repository lease + store mutex exclude other writers. Never replace an existing version.
            require(!immutable || !fs.exists(path)) { "Version already exists" }
            fs.atomicMove(temp, path)
        } finally { fs.delete(temp, mustExist = false) }
    }
    suspend fun importYaml(text: String): WorkflowDraft = mutex.withLock {
        WorkflowDraft(id(newId()), workflow = codec.decode(text)).also { writeDraft(it) }
    }
    suspend fun saveDraft(draft: WorkflowDraft) = mutex.withLock { writeDraft(draft) }
    private fun writeDraft(draft: WorkflowDraft) {
        draft.restoredFrom?.let(::id)
        atomicWrite(directory(draft.workflowId) / "draft.yaml", codec.encodeDraft(draft))
    }
    suspend fun loadDraft(workflowId: String): WorkflowDraft = mutex.withLock {
        codec.decodeDraft(read(directory(workflowId) / "draft.yaml")).also { require(it.workflowId == workflowId) }
    }
    private fun versions(workflowId: String): List<WorkflowVersion> {
        val dir = directory(workflowId) / "versions"
        return if (!fs.exists(dir)) emptyList() else fs.list(dir).filter { it.name.endsWith(".yaml") }.map { file ->
            val versionId = id(file.name.removeSuffix(".yaml"))
            codec.decodeVersion(read(file)).also { require(it.workflowId == workflowId && it.versionId == versionId) }
        }.sortedWith(compareBy({ it.createdAt }, { it.versionId }))
    }
    suspend fun listWorkflows(): List<String> = mutex.withLock {
        lease.requireHeld()
        require(fs.metadataOrNull(root)?.symlinkTarget == null) { "Symlink workflow storage" }
        if (!fs.exists(root)) emptyList() else fs.list(root).filter {
            fs.metadataOrNull(it)?.isDirectory == true && Regex("[0-9a-f-]{36}").matches(it.name)
        }.map { id(it.name) }
    }
    suspend fun listVersions(workflowId: String): List<WorkflowVersion> = mutex.withLock { versions(workflowId) }
    suspend fun loadVersion(workflowId: String, versionId: String): WorkflowVersion = mutex.withLock { version(workflowId, versionId) }
    private fun version(workflowId: String, versionId: String) = codec.decodeVersion(read(directory(workflowId) / "versions" / "${id(versionId)}.yaml")).also {
        require(it.workflowId == workflowId && it.versionId == versionId)
    }
    suspend fun saveVersion(draft: WorkflowDraft, warningsAcknowledged: Boolean = false): WorkflowVersion = mutex.withLock {
        val issues = validator.validate(draft.workflow)
        require(issues.none { it.severity == Severity.ERROR }) { "Invalid workflow: $issues" }
        require(warningsAcknowledged || issues.none { it.severity == Severity.WARNING }) { "Warnings must be acknowledged: $issues" }
        val latest = versions(draft.workflowId).lastOrNull()
        if (draft.restoredFrom == null && latest?.workflow == draft.workflow) return@withLock latest
        draft.restoredFrom?.let { version(draft.workflowId, it) }
        val saved = WorkflowVersion(draft.workflowId, id(newId()), now(), draft.restoredFrom, draft.workflow)
        atomicWrite(directory(draft.workflowId) / "versions" / "${saved.versionId}.yaml", codec.encodeVersion(saved), immutable = true)
        writeDraft(draft.copy(restoredFrom = null))
        saved
    }
    suspend fun restoreToDraft(workflowId: String, versionId: String): WorkflowDraft = mutex.withLock {
        WorkflowDraft(workflowId, versionId, version(workflowId, versionId).workflow).also(::writeDraft)
    }
    suspend fun deleteWorkflow(workflowId: String, confirmed: Boolean, isRunning: (String) -> Boolean) = mutex.withLock {
        require(confirmed && !isRunning(workflowId)) { "Deletion requires confirmation and an idle workflow" }
        fs.deleteRecursively(directory(workflowId), mustExist = true)
    }
}
