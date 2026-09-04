package dev.vibeported.mc.driver

import com.mojang.serialization.Codec
import dev.vibeported.rpc.RpcSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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
 * Adding a type is one `object` extending this and carrying `@RpcSerializer`. Nothing about this
 * class is specific to positions.
 */
public open class MojangCodecSerializer<T : Any>(
    name: String,
    private val codec: Codec<T>,
) : KSerializer<T> {

    private val bytes = ByteArraySerializer()

    override val descriptor: SerialDescriptor = SerialDescriptor(name, bytes.descriptor)

    override fun serialize(encoder: Encoder, value: T) {
        val tag = codec.encodeStart(NbtOps.INSTANCE, value)
            .getOrThrow { IllegalStateException("mcdriver: could not encode a ${descriptor.serialName}: $it") }
        encoder.encodeSerializableValue(bytes, wrap(tag))
    }

    override fun deserialize(decoder: Decoder): T {
        val tag = unwrap(decoder.decodeSerializableValue(bytes))
        return codec.parse(NbtOps.INSTANCE, tag)
            .getOrThrow { IllegalStateException("mcdriver: could not decode a ${descriptor.serialName}: $it") }
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
        return wrapper.get(VALUE) ?: error("mcdriver: an encoded value had no `$VALUE`")
    }

    private companion object {
        const val VALUE = "v"
    }
}

/**
 * A position, which is what most of this driver sends.
 *
 * The annotation is the whole registration: the compiler reads it, stops refusing `BlockPos` as an
 * argument or a result, and records it in this module's manifest; every node holding this jar
 * assembles it into its wire format as it starts.
 */
@RpcSerializer(BlockPos::class)
public object BlockPosSerializer :
    MojangCodecSerializer<BlockPos>("net.minecraft.core.BlockPos", BlockPos.CODEC)

/*
 * There is deliberately no `ItemStack` serializer.
 *
 * Not because one cannot be written -- `ItemStack.CODEC` is right there -- but because a driver
 * could never make one to send. A stack's components are bound while a server loads its resources,
 * and a driver process runs no game: constructing `ItemStack(Items.DIAMOND_SWORD)` there throws
 * `NullPointerException: Components not bound yet` from inside `Item.components()`, long before
 * anything is encoded. So an item crosses as text and is built where the registries exist, exactly
 * as a block is. @see parseItem
 */
