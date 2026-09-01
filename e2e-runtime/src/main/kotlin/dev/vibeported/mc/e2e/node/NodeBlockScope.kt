package dev.vibeported.mc.e2e.node

import dev.vibeported.mc.e2e.BlockId
import dev.vibeported.mc.e2e.BlockScope
import dev.vibeported.mc.e2e.NodeId
import dev.vibeported.mc.e2e.SharedId
import dev.vibeported.mc.e2e.rpc.Event
import dev.vibeported.mc.e2e.rpc.InvokeBlock
import dev.vibeported.mc.e2e.rpc.Payload
import dev.vibeported.mc.e2e.rpc.SharedGet
import dev.vibeported.mc.e2e.rpc.SharedSet
import dev.vibeported.mc.e2e.rpc.ValueCodec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.reflect.KClass

/**
 * What a lifted block sees.
 *
 * Every member here crosses a process boundary or reaches into the local node -- there is nothing
 * else, because the compiler plugin has already guaranteed the block references nothing else.
 */
internal class NodeBlockScope(
    override val self: NodeId,
    override val runId: String,
    private val currentBlock: BlockId,
    private val facilities: Facilities,
    private val codec: ValueCodec,
    private val emitLog: (Event) -> Unit,
    /** Sends a payload to the orchestrator, or handles it directly when we *are* the orchestrator. */
    private val toOrchestrator: suspend (Payload) -> JsonElement?,
) : BlockScope {

    override fun log(message: String) {
        emitLog(
            Event(
                from = self,
                to = NodeId.ORCHESTRATOR,
                runId = runId,
                block = currentBlock,
                message = message,
                atMillis = System.currentTimeMillis(),
            )
        )
    }

    override suspend fun dispatch(block: BlockId, target: NodeId) {
        toOrchestrator(InvokeBlock(runId, block, target))
    }

    override suspend fun sharedGet(id: SharedId, type: KClass<*>): Any? {
        val encoded = toOrchestrator(SharedGet(runId, id, type.java.name)) ?: JsonNull
        return codec.decode(type, encoded)
    }

    override suspend fun sharedSet(id: SharedId, type: KClass<*>, value: Any?) {
        val encoded: JsonElement = codec.encode(type, value)
        toOrchestrator(SharedSet(runId, id, type.java.name, encoded))
    }

    override fun <T : Any> facility(type: KClass<T>): T = facilities.get(type)
}
