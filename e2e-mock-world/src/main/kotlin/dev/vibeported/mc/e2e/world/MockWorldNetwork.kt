package dev.vibeported.mc.e2e.world

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A toy stand-in for Minecraft's own chunk replication.
 *
 * Note this is *not* the framework's RPC layer. The harness talks to nodes over
 * [dev.vibeported.mc.e2e.rpc.Transport]; the game replicates its world by its own means, and the
 * mock keeps those two apart so the sample test exercises the same asymmetry a real one would:
 * the server writes, and a client sees nothing until the write has been replicated.
 */
public class MockWorldNetwork {

    public val server: MockServerWorld = MockServerWorld(this)

    private val clients = ConcurrentHashMap<Int, MockClientWorld>()

    public fun client(index: Int): MockClientWorld =
        clients.computeIfAbsent(index) { MockClientWorld(index) }

    internal fun replicate(deltas: Map<BlockPos, Block>) {
        clients.values.forEach { it.apply(deltas) }
    }
}

/** The authoritative world. Only a `server { }` block can reach one. */
public class MockServerWorld internal constructor(
    private val network: MockWorldNetwork,
) : World {
    private val blocks = ConcurrentHashMap<BlockPos, Block>()
    private val pending = ConcurrentHashMap<BlockPos, Block>()

    override fun getBlock(pos: BlockPos): Block = blocks[pos] ?: Block.AIR

    override fun blocks(): Map<BlockPos, Block> = blocks.toMap()

    /** Places a block. Clients do not see it until [sync]. */
    public fun setBlock(pos: BlockPos, block: Block) {
        blocks[pos] = block
        pending[pos] = block
    }

    /** Flushes everything written since the last call out to every connected client. */
    public fun sync() {
        if (pending.isEmpty()) return
        val deltas = pending.toMap()
        pending.clear()
        network.replicate(deltas)
    }
}

/** A client's replica. Lags the server until the server syncs. */
public class MockClientWorld internal constructor(
    public val index: Int,
) : World {
    private val blocks = ConcurrentHashMap<BlockPos, Block>()

    /** Bumped on every applied delta, so waiters re-test their predicate. */
    private val version = MutableStateFlow(0L)

    override fun getBlock(pos: BlockPos): Block = blocks[pos] ?: Block.AIR

    override fun blocks(): Map<BlockPos, Block> = blocks.toMap()

    internal fun apply(deltas: Map<BlockPos, Block>) {
        blocks.putAll(deltas)
        version.update { it + 1 }
    }

    /**
     * Suspends until this replica sees [block] at [pos].
     *
     * Returns false on timeout instead of throwing, so the caller can assert with a message saying
     * what it actually saw. A [MutableStateFlow] replays its current value to a new collector, so
     * a delta landing between the check and the wait cannot be missed.
     */
    public suspend fun awaitBlock(
        pos: BlockPos,
        block: Block,
        timeout: Duration = 5.seconds,
    ): Boolean = withTimeoutOrNull(timeout) {
        version.first { getBlock(pos) == block }
        true
    } ?: false
}
