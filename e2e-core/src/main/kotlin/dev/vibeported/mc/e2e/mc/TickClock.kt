package dev.vibeported.mc.e2e.mc

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Counts game ticks on this node, so a test can wait for one.
 *
 * A single listener that everything waiting shares, rather than one registration per wait: a
 * retrying assertion can ask for a tick hundreds of times, and adding and removing an event listener
 * each time would cost more than the check it is pacing.
 */
public class TickClock {

    private val ticks = MutableStateFlow(0L)

    public val current: Long get() = ticks.value

    /** Called from the game thread, once per tick. */
    public fun onTick() {
        ticks.update { it + 1 }
    }

    public suspend fun awaitTicks(count: Int) {
        val target = ticks.value + count.coerceAtLeast(1)
        ticks.first { it >= target }
    }
}
