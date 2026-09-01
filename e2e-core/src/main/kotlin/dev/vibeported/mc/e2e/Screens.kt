package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.protocol.E2eAssertionError
import net.minecraft.client.gui.screens.Screen

/**
 * Waits until this client is showing a [T], and hands it back.
 *
 * Opening a screen is never instant: a container is a round trip to the server, and even a local
 * one arrives on the next tick. Waiting for the class rather than sleeping is what lets the line
 * after this one touch the screen and mean it.
 */
public suspend fun <T : Screen> ClientScope.awaitScreen(
    type: Class<T>,
    mode: AssertMode = timeoutSec(10),
): T {
    if (!awaitCondition(mode) { type.isInstance(minecraft.gui.screen()) }) {
        val open = minecraft.gui.screen()
        throw E2eAssertionError(
            "expected ${type.simpleName} on $self after $mode, but " +
                (open?.let { "${it.javaClass.simpleName} was open" } ?: "no screen was open")
        )
    }
    return type.cast(minecraft.gui.screen())
}

/** @see awaitScreen */
public suspend inline fun <reified T : Screen> ClientScope.awaitScreen(
    mode: AssertMode = timeoutSec(10),
): T = awaitScreen(T::class.java, mode)

/** Waits until no screen is open, which is what closing one actually means. */
public suspend fun ClientScope.awaitNoScreen(mode: AssertMode = timeoutSec(10)) {
    if (awaitCondition(mode) { minecraft.gui.screen() == null }) return
    throw E2eAssertionError(
        "expected no screen on $self after $mode, but " +
            "${minecraft.gui.screen()?.javaClass?.simpleName} was still open"
    )
}
