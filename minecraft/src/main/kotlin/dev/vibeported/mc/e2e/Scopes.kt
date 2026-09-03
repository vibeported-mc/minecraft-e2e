package dev.vibeported.mc.e2e

import dev.vibeported.rpc.RpcScope
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level

/** Restricts implicit receivers so an inner block cannot silently call an outer scope's members. */
@DslMarker
public annotation class ProcedureDsl

/**
 * Common receiver of every block the compiler lifts, however deeply nested.
 *
 * What a block can reach is exactly this, its own arguments, and whatever is top-level or static --
 * by construction, since it runs in a process where nothing else it might have closed over exists.
 *
 * An [RpcScope], so the framework can resolve it on the node a body lands on. Everything below this
 * line is Minecraft; everything above it has never heard of the game.
 */
@ProcedureDsl
public interface ProcedureScope : RpcScope {

    /** Where this body is running, as a failure message should print it. */
    public val self: String get() = node.id.value

    /** The name of the test being run, as written in the suite. What artefacts are filed under. */
    public val testName: String

    /** Identifies the one test run this block belongs to; used to correlate logs. */
    public val runId: String

    /** Appends a line to this node's captured log, which the report interleaves by time. */
    public fun log(message: String)
}

/**
 * Receiver of a block that runs inside a game process.
 *
 * Blocks run on the game loop, so the tick is the natural unit of waiting here: awaiting one hands
 * the loop back and picks up exactly where the game next got a chance to change something.
 */
public interface NodeScope : ProcedureScope {

    /** This node's level. A [ServerLevel] on the server, a [ClientLevel] on a client. */
    public val level: Level

    /** How many ticks this node has seen. Only differences between readings mean anything. */
    public val currentTick: Long

    /** Suspends until [count] more ticks have gone by. */
    public suspend fun awaitTicks(count: Int = 1)

    /**
     * The client this node is, when something has to name one and nobody said which.
     *
     * Inside a `client("steve") { }` body that is steve, so a helper can act on "this client"
     * without being told. On the server there is no such obvious answer, so it is the default.
     */
    public val thisClient: String
}

/**
 * Receiver of a `server { }` block, which runs in the dedicated server process, **on the server
 * thread**.
 *
 * Everything here is safe to touch directly: the body is dispatched onto the game loop, so there is
 * no thread to hop to and no wrapper to remember. Awaiting anything -- a nested `client { }`, a
 * `delay` -- releases the loop so the server keeps ticking, and the body resumes back on it.
 *
 * Separate from [ClientScope] deliberately. A server block cannot so much as name a client-side
 * value, because no accessor for one exists on this type: the split is enforced by the type system
 * rather than by convention.
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

    /** The name this client runs under, which is also its player's name. */
    public val clientName: String

    public val minecraft: Minecraft

    /** This client's level, or null before it has joined one. */
    public val clientLevel: ClientLevel?

    /** This client's player, or null before it has joined a world. */
    public val clientPlayer: LocalPlayer?
}
