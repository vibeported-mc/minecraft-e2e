package dev.vibeported.rpc.e2e.a

import dev.vibeported.rpc.RpcSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** On every node. Whatever a body does with this, it can do anywhere. */
object Alpha {
    fun callA(): String = "A"
}

/**
 * A value from the common half, and not one anybody can annotate.
 *
 * Deliberately plain: no `@Serializable`, no companion, nothing kotlinx can find on its own. It
 * stands for the type a game gives you and does not let you change.
 */
class Ident(val value: String) {
    override fun equals(other: Any?): Boolean = other is Ident && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = "Ident($value)"
}

/**
 * How an [Ident] crosses, declared beside the type it serves.
 *
 * This annotation is the entire registration. The compiler records it in this module's manifest, so
 * the layer -- which only depends on this one -- may send an `Ident` without being configured to;
 * and every node holding this jar assembles it into its wire format as it starts. Node `b` gets it
 * for the same reason node `a` does, and neither was told about it.
 */
@RpcSerializer(Ident::class)
object IdentSerializer : KSerializer<Ident> {
    override val descriptor = PrimitiveSerialDescriptor("dev.vibeported.rpc.e2e.a.Ident", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Ident) = encoder.encodeString(value.value)
    override fun deserialize(decoder: Decoder) = Ident(decoder.decodeString())
}
