package dev.vibeported.rpc.e2e.b

import dev.vibeported.rpc.RpcSerializer
import dev.vibeported.rpc.e2e.a.Alpha
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Only on nodes holding role `B`. A node without this jar cannot load any class that names it. */
object Beta {
    fun callB(): String = Alpha.callA() + "B"
}

/** A value from the half not every node has. @see Beta */
class Tag(val name: String, val level: Int) {
    override fun equals(other: Any?): Boolean = other is Tag && other.name == name && other.level == level
    override fun hashCode(): Int = name.hashCode() * 31 + level
    override fun toString(): String = "Tag($name, $level)"
}

/**
 * How a [Tag] crosses.
 *
 * The second half of the same claim [dev.vibeported.rpc.e2e.a.IdentSerializer] makes, and the
 * interesting half: a serializer travels with the jar declaring it, so node `a` -- which has no
 * part-b -- assembles a wire format that has never heard of `Tag`. Serializers are not a global
 * registry any more than tables are; each node ends up with what its own classpath declared.
 */
@RpcSerializer(Tag::class)
object TagSerializer : KSerializer<Tag> {
    override val descriptor = PrimitiveSerialDescriptor("dev.vibeported.rpc.e2e.b.Tag", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Tag) = encoder.encodeString(value.name + "/" + value.level)
    override fun deserialize(decoder: Decoder): Tag {
        val (name, level) = decoder.decodeString().split("/", limit = 2)
        return Tag(name, level.toInt())
    }
}
