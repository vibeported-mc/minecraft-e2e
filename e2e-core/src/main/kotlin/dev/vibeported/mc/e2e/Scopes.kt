package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.BlockId
import dev.vibeported.mc.e2e.protocol.NodeId
import dev.vibeported.mc.e2e.protocol.SharedId
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import kotlin.reflect.KClass

/** Restricts implicit receivers so an inner block cannot silently call an outer scope's members. */
@DslMarker
public annotation class E2eDsl

/**
 * Common receiver of everything the compiler plugin lifts: every `server`/`client` block, however
 * deeply nested.
 *
 * After the plugin has run, the single argument of every lifted function is a [BlockScope], which
 * the local node supplies. Nothing else is in scope, by construction -- that is the whole point.
 */
@E2eDsl
public interface E2eBlockScope {
    /** Where this block is currently executing. */
    public val self: NodeId

    /** Identifies the one test run this block belongs to; used to correlate logs and shared state. */
    public val runId: String

    /** Appends a line to this node's captured log, which the report interleaves by time. */
    public fun log(message: String)

    /** Sends a lifted block to [target] and suspends until that node has finished running it. */
    public suspend fun dispatch(block: BlockId, target: NodeId)

    /**
     * A handle on one `shared` value, bound to this node.
     *
     * Deliberately not suspending: the compiler plugin emits this wherever a shared value is
     * mentioned, so it has to be legal everywhere a plain expression is -- inside a non-suspending
     * lambda, or on the way into a helper function. Crossing the wire is what [Shared.get] and
     * [Shared.set] are for.
     */
    public fun <T : Any> sharedHandle(id: SharedId, type: KClass<T>): Shared<T>
}

/**
 * Receiver of an `e2e` test body.
 *
 * A compile-time receiver only: a test body is declarative -- shared declarations and blocks, and
 * nothing else -- so it is read at compile time and never executed. That is why it offers no way to
 * reach either side of the game.
 */
public interface E2eScope : E2eBlockScope

/**
 * Receiver of a block that runs inside a game process.
 *
 * Blocks run on the game loop, so the tick is the natural unit of waiting here: awaiting one hands
 * the loop back and picks up exactly where the game next got a chance to change something.
 */
public interface NodeScope : E2eBlockScope {

    /** This node's level. A [ServerLevel] on the server, a [ClientLevel] on a client. */
    public val level: Level

    /** How many ticks this node has seen. Only differences between readings mean anything. */
    public val currentTick: Long

    /** Suspends until [count] more ticks have gone by. */
    public suspend fun awaitTicks(count: Int = 1)
}

/**
 * Receiver of a `server { }` block, which runs in the dedicated server process, **on the server
 * thread**.
 *
 * Everything here is safe to touch directly: the block body is dispatched onto the game loop, so
 * there is no thread to hop to and no wrapper to remember. Awaiting anything -- a `shared` value, a
 * nested `client { }`, a `delay` -- releases the loop so the server keeps ticking, and the block
 * resumes back on it.
 *
 * Separate from [ClientScope] deliberately. A server block cannot so much as name a client-side
 * value, because no accessor for one exists on this type: the split is enforced by the type system
 * rather than by convention. Values cross between the two sides only as `shared`.
 */
public interface ServerScope : NodeScope {
    public val minecraftServer: MinecraftServer

    /** The overworld, which is where a test without other ideas should put things. */
    public val serverLevel: ServerLevel

    public val serverPlayers: List<ServerPlayer>

    /** The first connected player, or null if nobody has joined yet. */
    public val serverPlayer: ServerPlayer?
}

/** Receiver of a `client { }` block, which runs on that client's render thread. @see ServerScope */
public interface ClientScope : NodeScope {
    /** Which client this is, when a test runs more than one. */
    public val clientIndex: Int

    public val minecraft: Minecraft

    /** This client's level, or null before it has joined one. */
    public val clientLevel: ClientLevel?

    /** This client's player, or null before it has joined a world. */
    public val clientPlayer: LocalPlayer?
}

/**
 * The concrete scope a node hands to a lifted block.
 *
 * It satisfies both node scopes so that one generated `invoke` can serve server and client blocks
 * alike. Which members are legal in a given block is still settled at compile time by the receiver
 * its source lambda declared: a `server { }` body is typed `suspend ServerScope.() -> Unit` and can
 * never see a client accessor, whatever the runtime object handed to it happens to also implement.
 */
public interface BlockScope : ServerScope, ClientScope
