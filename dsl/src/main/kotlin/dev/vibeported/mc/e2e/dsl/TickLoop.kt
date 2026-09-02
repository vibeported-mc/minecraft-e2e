package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.NodeScope
import dev.vibeported.mc.e2e.protocol.AssertionFailure

/**
 * Runs a block once per tick until it says to stop.
 *
 * The unit of animation on a node. Anything that has to change a little every tick -- walking a
 * player along a path, easing a camera, driving a machine for a while -- is this: the body computes
 * what this tick should look like and returns `true` to be called again, `false` to finish.
 *
 * ```kotlin
 * server {
 *     var remaining = 40
 *     serverTickLoop {
 *         nudgeSomething()
 *         remaining-- > 0
 *     }
 * }
 * ```
 *
 * The body runs on the game thread and the wait between calls hands that thread back, so the game
 * keeps ticking normally while the loop runs. It is called once immediately, so a body that returns
 * `false` the first time has still run once.
 *
 * [maxTicks] is a guard, not a schedule: a loop whose body never returns `false` would otherwise
 * hang the run with nothing to show, and a test that hits the guard has a bug worth a message.
 */
public suspend fun NodeScope.tickLoop(
    maxTicks: Int = 20 * 60,
    body: suspend () -> Boolean,
) {
    var ticks = 0
    while (body()) {
        if (++ticks >= maxTicks) {
            throw AssertionFailure("a tick loop ran for $maxTicks ticks without finishing")
        }
        awaitTicks(1)
    }
}

/**
 * [tickLoop], named for where it runs.
 *
 * Reads better at a call site inside a `server { }` block, and says plainly that the ticks being
 * counted are the server's.
 */
public suspend fun NodeScope.serverTickLoop(
    maxTicks: Int = 20 * 60,
    body: suspend () -> Boolean,
): Unit = tickLoop(maxTicks, body)
