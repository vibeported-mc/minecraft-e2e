package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.ServerScope
import dev.vibeported.mc.e2e.facility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * The server side of the game, reachable only from a `server { }` block.
 *
 * These are extensions rather than members of the scope interface so that `e2e-api` stays free of
 * Minecraft and its compiler-plugin tests stay fast. The separation they enforce is real either
 * way: there is no `ClientScope` receiver in sight here, so a client accessor cannot be named.
 */
public val ServerScope.server: MinecraftServer get() = facility()

/**
 * Runs [action] on the server thread and waits for it.
 *
 * Everything that touches the world has to go through here. A block body runs on a coroutine so it
 * can suspend on the orchestrator, which means it is never on the game thread, and touching a level
 * from off it is how a test ends up debugging Minecraft rather than the mod under test.
 */
public suspend fun <T> ServerScope.onServer(action: MinecraftServer.() -> T): T {
    val minecraftServer = server
    // Both event loops are Executors, and going through supplyAsync sidesteps the submit overloads
    // while still landing the work on the game thread.
    val future = CompletableFuture.supplyAsync(Supplier { action(minecraftServer) }, minecraftServer)
    return withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
}

/** The overworld, which is where the sample suites put things. */
public suspend fun ServerScope.overworld(): ServerLevel = onServer { overworld() }

/** The first player connected to the dedicated server, or null while nobody has joined. */
public suspend fun ServerScope.firstPlayer(): ServerPlayer? = onServer { playerList.players.firstOrNull() }

/** @see ServerScope.server */
public val ClientScope.minecraft: Minecraft get() = facility()

/** @see onServer */
public suspend fun <T> ClientScope.onClient(action: Minecraft.() -> T): T {
    val client = minecraft
    val future = CompletableFuture.supplyAsync(Supplier { action(client) }, client)
    return withContext(Dispatchers.IO) { future.get(60, TimeUnit.SECONDS) }
}

/** This client's level, or null before it has joined one. */
public suspend fun ClientScope.clientLevel(): ClientLevel? = onClient { level }

/** This client's player, or null before it has joined a world. */
public suspend fun ClientScope.localPlayer(): LocalPlayer? = onClient { player }
