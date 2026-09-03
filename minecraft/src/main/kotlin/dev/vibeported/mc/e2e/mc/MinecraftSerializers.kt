package dev.vibeported.mc.e2e.mc

import com.mojang.serialization.Codec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor as Descriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Sends a Minecraft value that will never be `@Serializable`, using the game's own codec.
 *
 * A `BlockPos` is not annotated and never will be -- it is Mojang's class -- but it has a `Codec`,
 * which is the same thing said in a different vocabulary. So the codec turns it into NBT, the NBT
 * becomes bytes, and the bytes ride inside the ordinary payload. CBOR carries a `ByteArray`
 * natively, which is why none of this has to be Base64'd to survive the trip.
 *
 * Adding a type is one line in [MinecraftSerializers]. Nothing about this class is specific to
 * positions.
 */
public class MojangCodecSerializer<T : Any>(
    name: String,
    private val codec: Codec<T>,
) : KSerializer<T> {

    private val bytes = ByteArraySerializer()

    override val descriptor: Descriptor = SerialDescriptor(name, bytes.descriptor)

    override fun serialize(encoder: Encoder, value: T) {
        val tag = codec.encodeStart(NbtOps.INSTANCE, value)
            .getOrThrow { IllegalStateException("e2e: could not encode a ${descriptor.serialName}: $it") }
        encoder.encodeSerializableValue(bytes, wrap(tag))
    }

    override fun deserialize(decoder: Decoder): T {
        val tag = unwrap(decoder.decodeSerializableValue(bytes))
        return codec.parse(NbtOps.INSTANCE, tag)
            .getOrThrow { IllegalStateException("e2e: could not decode a ${descriptor.serialName}: $it") }
    }

    /**
     * NBT goes inside a compound with a fixed key, because the root of an NBT stream has to be a
     * compound and a `BlockPos` encodes to a list.
     */
    private fun wrap(tag: Tag): ByteArray {
        val wrapper = CompoundTag()
        wrapper.put(VALUE, tag)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { NbtIo.write(wrapper, it) }
        return out.toByteArray()
    }

    private fun unwrap(bytes: ByteArray): Tag {
        val wrapper = DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it) }
        return wrapper.get(VALUE) ?: error("e2e: an encoded value had no `$VALUE`")
    }

    private companion object {
        const val VALUE = "v"
    }
}

/**
 * The Minecraft types a body may send, and how.
 *
 * Handed to the wire format when a node is built. The compiler is told the same list by name --
 * `rpc { contextual.add(...) }` in this module's build script -- so a type that is not in both
 * places fails to compile rather than at the first call that sends one.
 */
public object MinecraftSerializers {

    public val module: SerializersModule = SerializersModule {
        contextual(BlockPos::class, MojangCodecSerializer("net.minecraft.core.BlockPos", BlockPos.CODEC))
    }
}
