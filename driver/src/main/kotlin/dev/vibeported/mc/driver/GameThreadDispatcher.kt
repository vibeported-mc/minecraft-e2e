package dev.vibeported.mc.driver

import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.util.thread.BlockableEventLoop
import kotlin.coroutines.CoroutineContext

/**
 * Runs coroutines on a Minecraft event loop.
 *
 * This is what lets a `server { }` or `client { }` body touch the game directly: the body is
 * dispatched here, so every statement in it runs on the game thread. It does not freeze the game,
 * because a suspension point releases the thread -- awaiting anything hands the loop back, the game
 * keeps ticking, and the continuation is queued onto the loop to resume there.
 *
 * Only bodies go through this. Everything the framework does for itself -- sockets, the peer -- stays
 * on `Default`, so a slow tick cannot delay the machinery driving it.
 */
public class GameThreadDispatcher(
    private val loop: BlockableEventLoop<*>,
) : CoroutineDispatcher() {

    /** Already on the game thread: run inline rather than queueing behind ourselves. */
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !loop.isSameThread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        loop.execute(block)
    }

    override fun toString(): String = "GameThread(${loop.javaClass.simpleName})"
}
