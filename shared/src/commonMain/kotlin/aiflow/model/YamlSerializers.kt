@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package aiflow.model

import com.charleskorn.kaml.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*

object WorkspaceSerializer : KSerializer<Workspace> {
    override val descriptor = PrimitiveSerialDescriptor("Workspace", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Workspace) = encoder.encodeString(when (value) {
        Workspace.Local -> "local"
        is Workspace.Worktree -> "worktree:${value.name}"
    })
    override fun deserialize(decoder: Decoder): Workspace = decoder.decodeString().let {
        when { it == "local" -> Workspace.Local; it.startsWith("worktree:") -> Workspace.Worktree(it.removePrefix("worktree:")); else -> throw SerializationException("Invalid workspace: $it") }
    }
}
object TargetSerializer : KSerializer<Target> {
    override val descriptor = PrimitiveSerialDescriptor("Target", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Target) = encoder.encodeString(when(value) { Target.End -> "end"; Target.Ask -> "ask"; is Target.StepId -> value.id })
    override fun deserialize(decoder: Decoder): Target = when(val s = decoder.decodeString()) { "end" -> Target.End; "ask" -> Target.Ask; else -> Target.StepId(s) }
}
@Serializable private data class ContainsValue(val path: String, val text: String)
@Serializable private data class CheckValue(val fileExists: String? = null, val command: String? = null, val fileContains: ContainsValue? = null)
private fun checkValue(decoder: Decoder): Pair<String?, CheckValue?> {
    if (decoder is JsonDecoder) {
        val element = decoder.decodeJsonElement()
        if (element is JsonPrimitive && element.isString) return element.content to null
        val value = decoder.json.decodeFromJsonElement(CheckValue.serializer(), element)
        if (listOf(value.fileExists, value.command, value.fileContains).count { it != null } != 1)
            throw SerializationException("Expected exactly one check")
        return null to value
    }
    val input = decoder as? YamlInput ?: throw SerializationException("Checks require YAML or JSON")
    return if (input.node is YamlScalar) (input.node as YamlScalar).content to null
    else null to input.yaml.decodeFromYamlNode(CheckValue.serializer(), input.node).also {
        if (listOf(it.fileExists, it.command, it.fileContains).count { v -> v != null } != 1) throw SerializationException("Expected exactly one check")
    }
}
object ConditionSerializer : KSerializer<Condition> {
    override val descriptor = buildSerialDescriptor("YamlCheck", SerialKind.CONTEXTUAL)
    override fun deserialize(decoder: Decoder): Condition {
        val (scalar, value) = checkValue(decoder)
        return when {
            scalar == "success" -> Condition.Success
            scalar == "failure" -> Condition.Failure
            scalar == "otherwise" -> Condition.Otherwise
            value?.fileExists != null -> Condition.FileExists(value.fileExists)
            value?.command != null -> Condition.Command(value.command)
            value?.fileContains != null -> Condition.FileContains(value.fileContains.path, value.fileContains.text)
            else -> throw SerializationException("Invalid condition: $scalar")
        }
    }
    override fun serialize(encoder: Encoder, value: Condition) {
        when(value) {
            Condition.Success -> encoder.encodeString("success")
            Condition.Failure -> encoder.encodeString("failure")
            Condition.Otherwise -> encoder.encodeString("otherwise")
            is Condition.FileExists -> encoder.encodeSerializableValue(CheckValue.serializer(), CheckValue(fileExists = value.path))
            is Condition.Command -> encoder.encodeSerializableValue(CheckValue.serializer(), CheckValue(command = value.cmd))
            is Condition.FileContains -> encoder.encodeSerializableValue(CheckValue.serializer(), CheckValue(fileContains = ContainsValue(value.path, value.text)))
        }
    }
}
object CompletionSerializer : KSerializer<Completion> {
    override val descriptor = buildSerialDescriptor("YamlCheck", SerialKind.CONTEXTUAL)
    override fun deserialize(decoder: Decoder): Completion {
        val (scalar, value) = checkValue(decoder)
        return when {
            scalar == "exitCode" -> Completion.ExitCode
            value?.fileExists != null -> Completion.FileExists(value.fileExists)
            value?.command != null -> Completion.Command(value.command)
            else -> throw SerializationException("Invalid completion: $scalar")
        }
    }
    override fun serialize(encoder: Encoder, value: Completion) {
        when(value) {
            Completion.ExitCode -> encoder.encodeString("exitCode")
            is Completion.FileExists -> encoder.encodeSerializableValue(CheckValue.serializer(), CheckValue(fileExists = value.path))
            is Completion.Command -> encoder.encodeSerializableValue(CheckValue.serializer(), CheckValue(command = value.cmd))
        }
    }
}
