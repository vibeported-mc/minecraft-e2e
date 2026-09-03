package dev.vibeported.mc.driver

import dev.vibeported.rpc.RpcBody0
import dev.vibeported.rpc.RpcLift
import dev.vibeported.rpc.node
import dev.vibeported.rpc.rpcCallIn

/*
 * Two more calls, and the reason `@RpcLift` marks a parameter rather than a function.
 *
 * Neither is special to the plugin: both take a body at a marked parameter and hand it on, exactly
 * as `server` and `client` do. What they add is a richer receiver -- one that can place blocks, one
 * that can drive an open screen -- and, in the second case, something to do first.
 */

/**
 * Builds part of the world, on the server.
 *
 * ```kotlin
 * worldBuild {
 *     at(94, 200, 200) { "minecraft:stairs[facing=north]" }
 *     fill(90..98, 199..199, 196..204) { "minecraft:stone" }
 * }
 * ```
 *
 * Positions are absolute, and there is no origin to pass: a body's receiver is resolved once per
 * node rather than once per call, so there is nowhere to put one. Offsetting a `BlockPos` at the
 * call site costs a method and reads no worse than an origin would.
 *
 * Blocks are placed without neighbour updates, so a stair asked for `shape=straight` stays straight
 * whatever is put beside it. @see WorldBuilderScope
 */
public suspend fun worldBuild(
    @RpcLift("server") body: RpcBody0<WorldBuilderScope, Unit>,
): Unit = rpcCallIn(node(SERVER_NODE), body)

/**
 * Waits for a screen to be open on a client, then drives it.
 *
 * ```kotlin
 * waitForScreen("alex", "InventoryScreen") {
 *     pickUp(InventorySlot.HOTBAR_1)
 *     dropOn(InventorySlot.HELMET)
 * }
 * ```
 *
 * Two calls, because they are two different things: waiting is unbounded and belongs to the caller's
 * `withTimeout`, and the body is a lifted procedure of its own. What arrives in between is not
 * carried across -- [ScreenScope] reads `minecraft.screen` afresh on every member, which is what
 * lets a node offer the scope at all.
 *
 * The other side of that coin: a body holding on to something across a suspension can find the
 * screen closed underneath it. The members re-read each time; a hand-written body must too.
 */
public suspend fun <R> waitForScreen(
    client: String,
    screen: String,
    @RpcLift("client") body: RpcBody0<ScreenScope, R>,
): R {
    client(client, screen) { name -> awaitScreen(name) }
    return rpcCallIn(node(client), body)
}


