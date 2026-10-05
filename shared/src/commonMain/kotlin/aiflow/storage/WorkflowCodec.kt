package aiflow.storage

import aiflow.model.*
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

class WorkflowCodec {
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true, encodeDefaults = false))
    fun decode(text: String): Workflow = yaml.decodeFromString(Workflow.serializer(), text)
    fun encode(value: Workflow): String = yaml.encodeToString(Workflow.serializer(), value)
    fun decodeDraft(text: String): WorkflowDraft = yaml.decodeFromString(WorkflowDraft.serializer(), text)
    fun encodeDraft(value: WorkflowDraft): String = yaml.encodeToString(WorkflowDraft.serializer(), value)
    fun decodeVersion(text: String): WorkflowVersion = yaml.decodeFromString(WorkflowVersion.serializer(), text)
    fun encodeVersion(value: WorkflowVersion): String = yaml.encodeToString(WorkflowVersion.serializer(), value)
}
