package dev.vibeported.mc.e2e.mc

import com.mojang.serialization.Codec
import dev.vibeported.mc.e2e.rpc.JsonValueCodec
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.reflect.KClass

/**
 * Sends Minecraft values as bytes inside the ordinary payload.
 *
 * A `BlockPos` is not `@Serializable` and never will be, but it does have a Mojang `Codec`. So the
 * game's own codec turns it into NBT, the NBT is written to bytes, and the bytes ride along as a
 * string in the same kotlinx-serialized envelope as everything else. Anything without a registered
 * codec falls through to plain kotlinx serialization, which is what keeps `shared<Int>()` working.
 */
public class McValueCodec(
    private val fallback: ValueCodec = JsonValueCodec(),
) : ValueCodec {

    override fun encode(type: KClass<*>, value: Any?): JsonElement {
        val codec = codecFor(type) ?: return fallback.encode(type, value)
        @Suppress("UNCHECKED_CAST")
        val tag = (codec as Codec<Any?>)
            .encodeStart(NbtOps.INSTANCE, value)
            .getOrThrow { IllegalStateException("e2e: could not encode a ${type.simpleName}: $it") }
        return JsonPrimitive(NBT_PREFIX + toBase64(tag))
    }

    override fun decode(type: KClass<*>, element: JsonElement): Any? {
        val codec = codecFor(type) ?: return fallback.decode(type, element)
        val text = (element as? JsonPrimitive)?.contentOrNull
            ?: error("e2e: expected an encoded ${type.simpleName}, got $element")
        require(text.startsWith(NBT_PREFIX)) { "e2e: ${type.simpleName} was not encoded by this codec" }
        val tag = fromBase64(text.removePrefix(NBT_PREFIX))
        return codec.parse(NbtOps.INSTANCE, tag)
            .getOrThrow { IllegalStateException("e2e: could not decode a ${type.simpleName}: $it") }
    }

    private fun codecFor(type: KClass<*>): Codec<*>? = when (type) {
        BlockPos::class -> BlockPos.CODEC
        BlockState::class -> BlockState.CODEC
        ItemStack::class -> ItemStack.CODEC
        else -> null
    }

    /**
     * NBT is written inside a compound with a fixed key, because the root of an NBT stream has to be
     * a compound and a `BlockPos` encodes to a list.
     */
    private fun toBase64(tag: Tag): String {
        val wrapper = CompoundTag()
        wrapper.put(VALUE_KEY, tag)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { NbtIo.write(wrapper, it) }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    private fun fromBase64(text: String): Tag {
        val bytes = Base64.getDecoder().decode(text)
        val wrapper = DataInputStream(ByteArrayInputStream(bytes)).use { NbtIo.read(it) }
        return wrapper.get(VALUE_KEY) ?: error("e2e: encoded value had no $VALUE_KEY")
    }

    private companion object {
        const val NBT_PREFIX = "nbt:"
        const val VALUE_KEY = "v"
    }
}
