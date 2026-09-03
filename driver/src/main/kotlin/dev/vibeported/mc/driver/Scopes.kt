package dev.vibeported.mc.driver

import dev.vibeported.rpc.RpcScope
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import kotlin.time.Duration

/** Keeps an inner block from seeing the receiver of an outer one. */
@DslMarker
public annotation class DriverDsl

/**
 * What a body sees on the node that runs it.
 *
 * This is the only place a receiver comes from: `server { }` and `client { }` push one, and nothing
 * else does. A body may not capture anything around it -- it runs in another process, where those
 * values do not exist -- so everything it needs arrives as an argument.
 *
 * An [RpcScope], so `node` and `services` come free and the framework can resolve this on the node a
 * body lands on.
 */
@DriverDsl
public interface NodeScope : RpcScope {

    /** This node's level. A [ServerLevel] on the server, a [ClientLevel] on a client. */
    public val level: Level

    /** How many ticks this node has seen. Only differences between readings mean anything. */
    public val currentTick: Long

    /** Suspends until [count] more ticks have gone by. */
    public suspend fun awaitTicks(count: Int = 1)

    /**
     * Suspends until [predicate] holds, checking once a tick.
     *
     * No deadline of its own, on purpose: a caller says how long it is prepared to wait with
     * `withTimeout`, which is the language's own answer and composes with everything else. A driver
     * that invented its own timeout vocabulary would be a driver with an opinion about failure.
     */
    public suspend fun awaitUntil(predicate: () -> Boolean) {
        while (!predicate()) awaitTicks(1)
    }
}

/**
 * A body running in the dedicated server process, on the server thread.
 *
 * Everything here is safe to touch directly: the body is dispatched onto the game loop, so there is
 * no thread to hop to and no wrapper to remember. Awaiting anything releases the loop, so the server
 * keeps ticking and the body resumes back on it.
 *
 * Separate from [ClientScope] deliberately. A server body cannot so much as name a client-side
 * value, because no accessor for one exists on this type -- the split is enforced by the type system
 * rather than by convention, and it is what lets a dist-cleaned server load these bodies at all.
 */
public interface ServerScope : NodeScope {

    public val minecraftServer: MinecraftServer

    /** The overworld, which is where anything without other ideas should go. */
    public val serverLevel: ServerLevel

    public val serverPlayers: List<ServerPlayer>

    /** The player called [name], or a failure naming who is actually connected. */
    public fun playerNamed(name: String): ServerPlayer

    /** The player called [name], or null if they are not here. */
    public fun playerOrNull(name: String): ServerPlayer?

    /** Places blocks, without the neighbour updates that would rewrite a fixture. */
    public fun worldBuild(body: WorldBuilderScope.() -> Unit)
}

/** A body running on one game client, on its render thread. @see ServerScope */
public interface ClientScope : NodeScope {

    /** The name this client runs under, which is also its player's name. */
    public val clientName: String

    public val minecraft: Minecraft

    /** This client's level, or null before it has joined one. */
    public val clientLevel: ClientLevel?

    /** This client's player, or null before it has joined a world. */
    public val clientPlayer: LocalPlayer?
}

/**
 * Building the world, inside a `server { }` body.
 *
 * Blocks are named as text -- `"minecraft:stairs[facing=north]"` -- and parsed by the game's own
 * parser, so anything the `/setblock` command accepts works here and a bad one fails where it was
 * written rather than as a missing block later.
 *
 * Positions are absolute. An origin would have to reach the node somehow, and a body's receiver is
 * resolved once per node rather than once per call, so there is nowhere to put one; offsetting a
 * `BlockPos` at the call site costs a method and reads no worse.
 */
public interface WorldBuilderScope : ServerScope {

    public fun at(x: Int, y: Int, z: Int, block: () -> String)

    public fun at(pos: BlockPos, block: () -> String)

    public fun fill(xs: IntRange, ys: IntRange, zs: IntRange, block: () -> String)
}

/**
 * Driving an open screen, inside a `waitForScreen { }` body.
 *
 * Every member reads the screen afresh, which is what lets a node offer this scope at all: there is
 * one instance per node and no per-call state in it. The cost is the other side of the same coin --
 * a body that holds on to something across a suspension can find the screen closed underneath it.
 */
public interface ScreenScope : ClientScope {

    /** The hotbar slot the player currently has selected. */
    public val selectedHotbar: InventorySlot

    /** What is in [slot] right now. */
    public fun stackAt(slot: InventorySlot): ItemStack

    /** What the pointer is holding, which is empty unless something was picked up. */
    public val carried: ItemStack

    /** The slot under the pointer, or null when it is over none. */
    public val hoveredSlot: InventorySlot?

    /** Where the pointer is, in the screen's own coordinates. */
    public val pointerInGui: Pair<Double, Double>

    public suspend fun moveToSlot(slot: InventorySlot, over: Duration = DEFAULT_DRAG)

    public suspend fun pickUp(slot: InventorySlot, over: Duration = DEFAULT_DRAG)

    public suspend fun dropOn(slot: InventorySlot, over: Duration = DEFAULT_DRAG)

    /** Picks up from [from] and drops on [to], returning once the stack has moved. */
    public suspend fun swapSlot(from: InventorySlot, to: InventorySlot, over: Duration = DEFAULT_DRAG)

    public suspend fun click(button: MouseButton = MouseButton.LEFT)
}
