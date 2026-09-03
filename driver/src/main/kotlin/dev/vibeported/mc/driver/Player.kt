package dev.vibeported.mc.driver

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/*
 * Free methods, every one of them written as a `server { }` or a `client { }`.
 *
 * None of these takes a receiver, because none of them needs one: `teleport(...)` is a sentence on
 * its own, and which side of the game carries it out is an implementation detail of this file. A
 * caller reaches for a block only when it wants several of these to cross the wire together.
 *
 * Nothing here has a deadline. Every wait is unbounded and the caller says how long it will stand
 * for with `withTimeout`, which composes with everything else the language does.
 */

/**
 * Waits until a player has joined and is actually somewhere.
 *
 * "Joined" is not enough on its own: a player exists on the server for a while before their chunk is
 * ticking, and reading a position in that window gives one they are about to move away from. So this
 * waits for all three -- present, alive, and standing in a chunk the server is ticking.
 */
public suspend fun waitForPlayer(client: String) {
    server(client) { name ->
        awaitUntil { minecraftServer.playerList.getPlayerByName(name)?.isReady() == true }
    }
}

/** Present, alive, and in a chunk the server is ticking, which is when a position means something. */
internal fun ServerPlayer.isReady(): Boolean =
    isAlive && (level() as? ServerLevel)?.isPositionEntityTicking(blockPosition()) == true

/**
 * Moves a player, and does not return until that client has actually arrived.
 *
 * Two procedures, and it reads as exactly what it is: the server moves the player, because only the
 * server can, and then the client is asked whether it has caught up. Waiting for the client is the
 * point -- the server sets its own copy of the position the instant it teleports, so a call that
 * returned there would let the next line run against a client that has not moved yet.
 */
public suspend fun teleport(client: String, pos: BlockPos, flying: Boolean = false) {
    server(client, pos, flying) { name, target, fly -> minecraftServer.movePlayer(name, target, fly) }
    client(client, pos) { target -> awaitArrival(target) }
}

/** Turns a player to face [pos], returning once that client is looking at it. @see teleport */
public suspend fun lookAt(client: String, pos: BlockPos) {
    server(client, pos) { name, target -> minecraftServer.turnPlayerToward(name, target) }
    client(client, pos) { target -> awaitFacing(target) }
}

/** Turns one player to face another, returning once they are. @see teleport */
public suspend fun lookAtPlayer(client: String, target: String) {
    server(client, target) { name, other -> minecraftServer.turnPlayerTowardPlayer(name, other) }
    client(client, target) { other -> awaitFacingPlayer(other) }
}

/**
 * Lets a player fly, without moving them.
 *
 * Both abilities, not just `flying`: without the permission the client refuses the state and the
 * player drops out of the sky a tick later.
 */
public suspend fun allowFlight(client: String) {
    server(client) { name ->
        val player = minecraftServer.playerNamed(name)
        player.abilities.mayfly = true
        player.abilities.flying = true
        player.onUpdateAbilities()
    }
}

/**
 * Waits until a player is dead.
 *
 * Answered on the server, which is the only party whose opinion settles it: a client can be looking
 * at a death screen while the server has already respawned them, and a killer's client shows a body
 * falling over before any of it is decided.
 */
public suspend fun awaitDeath(client: String) {
    server(client) { name ->
        awaitUntil { minecraftServer.playerList.getPlayerByName(name)?.isDeadOrDying == true }
    }
}

/**
 * Where a player is, or null if nobody by that name is connected.
 *
 * Read from the server, which is the authority. A client's own view of another player lags behind
 * and is worth asking for only inside a `client { }`, where it is one field access away.
 */
public suspend fun positionOf(player: String): BlockPos? =
    server(player) { name -> minecraftServer.playerList.getPlayerByName(name)?.blockPosition() }

/**
 * Whether that player is connected and alive.
 *
 * A dead player is still in the world -- they are looking at their own death screen -- so "there"
 * and "alive" are different questions, and anything that keeps acting on someone needs the second.
 */
public suspend fun isAlive(player: String): Boolean =
    server(player) { name -> minecraftServer.playerList.getPlayerByName(name)?.isAlive == true }

/**
 * Puts a stack in one of a player's slots, from the server.
 *
 * How the world gets arranged before it is exercised: giving a client something to drag is setup,
 * and doing it here keeps it out of whatever is being driven.
 */
public suspend fun giveItem(client: String, slot: InventorySlot, stack: ItemStack) {
    require(slot.isInPlayerInventory) {
        "$slot belongs to an open menu rather than to the player, so nothing can be put in it here"
    }
    server(client, slot.inventoryIndex, stack) { name, index, item ->
        val player = minecraftServer.playerNamed(name)
        player.inventory.setItem(index, item)
        // The client is told about its own inventory on the next broadcast, and anything that then
        // drags the stack has to see it there first.
        player.inventoryMenu.broadcastChanges()
    }
}
