package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.E2eAssertionError
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack

/**
 * Waits until a player has joined and is actually somewhere, and returns them.
 *
 * "Joined" is not enough on its own: a player exists on the server for a while before their chunk is
 * ticking, and a test that grabs them in that window sees a position it is about to move away from.
 * So this waits for all three -- present, alive, and standing in a chunk the server is ticking.
 *
 * Worth calling even where the harness happens to guarantee a player already. The guarantee today is
 * a chain of coincidences: a client only dials the orchestrator once it has a level and a player, and
 * the orchestrator only starts tests once every client has dialled in. Saying it out loud costs one
 * line and survives that chain changing.
 */
public suspend fun ServerScope.waitForPlayer(
    @MinecraftClientName client: String = DEFAULT_CLIENT,
    mode: AssertMode = timeoutSec(30),
): ServerPlayer {
    var found: ServerPlayer? = null

    val arrived = awaitCondition(mode) {
        found = minecraftServer.playerList.getPlayerByName(client)?.takeIf { it.isReady() }
        found != null
    }

    if (!arrived) {
        throw E2eAssertionError(
            "no player named `$client` was ready on the server within $mode; " +
                "connected: ${serverPlayers.map { it.name.string }}"
        )
    }
    return found!!
}

/** Present, alive, and in a chunk the server is ticking, which is when a position means something. */
private fun ServerPlayer.isReady(): Boolean =
    isAlive && (level() as? ServerLevel)?.isPositionEntityTicking(blockPosition()) == true

/**
 * Puts a stack in one of a player's slots, from the server.
 *
 * How a test arranges the world it is about to exercise: giving a client something to drag is
 * setup, not the thing under test, and doing it here keeps it out of the part that is.
 */
public suspend fun ServerScope.giveItem(
    @MinecraftClientName client: String = DEFAULT_CLIENT,
    slot: InventorySlot,
    stack: ItemStack,
) {
    require(slot.isInPlayerInventory) {
        "$slot belongs to an open menu rather than to the player, so nothing can be put in it here"
    }
    val player = waitForPlayer(client)
    player.inventory.setItem(slot.inventoryIndex, stack)
    // The client is told about its own inventory on the next broadcast, and a test that drags the
    // stack has to see it there first.
    player.inventoryMenu.broadcastChanges()
}

/**
 * The named client's player as the server has it, dead or alive, or null if they are not connected.
 *
 * Distinct from [waitForPlayer], which insists on someone who is up and about: after a death the
 * player is still in the list, still addressable, and emphatically not ready to be tested against.
 */
public fun ServerScope.playerOrNull(@MinecraftClientName client: String = DEFAULT_CLIENT): ServerPlayer? =
    minecraftServer.playerList.getPlayerByName(client)

/**
 * Fails the test unless the named player is dead.
 *
 * Answered on the server, which is the only party whose opinion settles it: a client can be looking
 * at a death screen while the server has already respawned them, and a killer's client shows a body
 * falling over before any of it is decided.
 */
public suspend fun ServerScope.assertPlayerDead(
    @MinecraftClientName client: String = DEFAULT_CLIENT,
    mode: AssertMode = timeoutSec(20),
) {
    if (awaitCondition(mode) { playerOrNull(client)?.isDeadOrDying == true }) return

    val player = playerOrNull(client)
    val seen = when {
        player == null -> "nobody by that name is connected"
        else -> "they are alive on ${player.health} health"
    }
    throw E2eAssertionError("`$client` should be dead ($mode)\n  $seen")
}

/**
 * Where this client currently sees another player, or null if it cannot see them at all.
 *
 * This client's own opinion, deliberately: it is what the person at this screen would be looking
 * at, and it is the position a test should chase when it wants to keep up with someone moving.
 */
public fun ClientScope.positionOf(@MinecraftClientName client: String): BlockPos? =
    clientLevel?.players()?.firstOrNull { it.name.string == client }?.blockPosition()

/**
 * Whether this client can see that player, alive, right now.
 *
 * A dead player is still in the world -- they are looking at their own death screen -- so "there"
 * and "alive" are different questions, and a test that keeps acting on someone needs the second.
 */
public fun ClientScope.isAlive(@MinecraftClientName client: String): Boolean =
    clientLevel?.players()?.firstOrNull { it.name.string == client }?.isAlive == true
