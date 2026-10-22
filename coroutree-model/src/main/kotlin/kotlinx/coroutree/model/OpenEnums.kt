package kotlinx.coroutree.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Reads an enum the proto3 way: a number this reader doesn't know is the entry with number 0 (`UNSPECIFIED`), not an
 * error. The generated serializer rejects it, and every milestone adds event kinds, so an old reader would stop at
 * the first new one. The numbers come from the entries' [ProtoNumber] annotations.
 */
internal open class OpenEnumSerializer<E : Enum<E>>(generated: KSerializer<E>, private val entries: List<E>) : KSerializer<E> {
    private val numbers = IntArray(entries.size) { i ->
        generated.descriptor.getElementAnnotations(i).filterIsInstance<ProtoNumber>().singleOrNull()?.number ?: i
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(generated.descriptor.serialName, PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeInt(numbers[value.ordinal])

    override fun deserialize(decoder: Decoder): E {
        val number = decoder.decodeInt()
        val index = numbers.indexOf(number)
        return entries[if (index < 0) 0 else index]
    }
}

internal object EventKindSerializer : OpenEnumSerializer<EventKind>(EventKind.serializer(), EventKind.entries)
internal object NodeKindSerializer : OpenEnumSerializer<NodeKind>(NodeKind.serializer(), NodeKind.entries)
internal object NodeStateSerializer : OpenEnumSerializer<NodeState>(NodeState.serializer(), NodeState.entries)
internal object OriginSerializer : OpenEnumSerializer<Origin>(Origin.serializer(), Origin.entries)
internal object BlockReasonSerializer : OpenEnumSerializer<BlockReason>(BlockReason.serializer(), BlockReason.entries)
internal object HandledBySerializer : OpenEnumSerializer<HandledBy>(HandledBy.serializer(), HandledBy.entries)
internal object PropagationDirectionSerializer : OpenEnumSerializer<PropagationDirection>(PropagationDirection.serializer(), PropagationDirection.entries)
internal object ContextElementKindSerializer : OpenEnumSerializer<ContextElementKind>(ContextElementKind.serializer(), ContextElementKind.entries)
internal object SeveritySerializer : OpenEnumSerializer<Diagnostic.Severity>(Diagnostic.Severity.serializer(), Diagnostic.Severity.entries)
internal object PaceReasonSerializer : OpenEnumSerializer<PaceDef.Reason>(PaceDef.Reason.serializer(), PaceDef.Reason.entries)
