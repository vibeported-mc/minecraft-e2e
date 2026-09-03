package dev.vibeported.mc.driver

/**
 * Which screen a client has open, named as text.
 *
 * A `Screen` cannot cross a wire and neither can a `Class`, so a screen is named by its simple class
 * name -- `"InventoryScreen"`, `"ChatScreen"` -- and matched against what is open. The same trade
 * blocks make: a string is looser than a type, and it is the only thing a caller in another process
 * can say at all.
 */
public fun ClientScope.currentScreen(): String? = minecraft.gui.screen()?.javaClass?.simpleName

/**
 * Waits until this client is showing [screen].
 *
 * Opening a screen is never instant: a container is a round trip to the server, and even a local one
 * arrives on the next tick. Waiting for it rather than sleeping is what lets the line after this one
 * touch the screen and mean it. No deadline of its own -- a caller wraps this in `withTimeout`.
 */
public suspend fun ClientScope.awaitScreen(screen: String) {
    awaitUntil { currentScreen() == screen }
}

/** Waits until no screen is open, which is what closing one actually means. */
public suspend fun ClientScope.awaitNoScreen() {
    awaitUntil { minecraft.gui.screen() == null }
}
