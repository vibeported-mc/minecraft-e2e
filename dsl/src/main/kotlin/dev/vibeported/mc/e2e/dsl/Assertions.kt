package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.NodeScope
import dev.vibeported.mc.e2e.protocol.AssertionFailure
import kotlinx.coroutines.delay
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Fails the test unless [condition] holds.
 *
 * With the default [once] it is checked a single time. Given a retrying mode it is checked again
 * until it holds or the mode runs out, which is what a test should say instead of sleeping first and
 * hoping: the wait is expressed where the race actually is, and an expectation that is simply wrong
 * still fails at once.
 *
 * [condition] is a suspending lambda, so it may read a `shared` value or anything else that crosses
 * the wire, and it may be evaluated many times -- it should therefore only observe, never change
 * anything.
 */
public suspend fun NodeScope.assertThat(
    description: String,
    mode: AssertMode = once,
    condition: suspend () -> Boolean,
) {
    if (awaitCondition(mode, condition)) return
    throw AssertionFailure(description + mode.suffix())
}

/** @see assertThat */
public suspend fun NodeScope.assertThat(
    mode: AssertMode = once,
    condition: suspend () -> Boolean,
): Unit = assertThat("assertion failed", mode, condition)

/**
 * Fails the test unless the block at [pos] satisfies [predicate], read from this node's own level.
 *
 * The same call means different things on either side, and deliberately so: on the server it asks
 * what actually happened, and on a client what that client has been told, which is the only way to
 * test that a change reached the people it was for.
 */
public suspend fun NodeScope.assertBlock(
    description: String,
    pos: BlockPos,
    mode: AssertMode = once,
    predicate: (BlockState) -> Boolean,
) {
    if (awaitCondition(mode) { predicate(level.getBlockState(pos)) }) return

    // Composing on assertThat would only ever report a false boolean, so the state that was actually
    // there has to be gathered here. That is the whole reason for a typed assertion.
    val seen = level.getBlockState(pos)
    throw AssertionFailure("$description ($mode)\n  at $pos on ${self}: ${seen.block.descriptionId}")
}

/**
 * Evaluates [condition] until it holds or [mode] runs out.
 *
 * Waiting is done in game ticks rather than by sleeping, because a block body runs on the game loop:
 * awaiting a tick hands the loop back and resumes exactly when the game has next had a chance to
 * change the thing being watched. Counting a tick timeout in ticks rather than wall clock also means
 * a server that is ticking slowly gives an assertion the same number of chances, not fewer.
 */
/**
 * How the wait is described in a failure, or nothing at all when there was no wait.
 *
 * A bare `once` says nothing worth reading; a mode that retried explains how long the assertion kept
 * trying, which is the first thing anyone asks of a flaky-looking failure.
 */
private fun AssertMode.suffix(): String = if (timeout == null) "" else " ($this)"

internal suspend fun NodeScope.awaitCondition(
    mode: AssertMode,
    condition: suspend () -> Boolean,
): Boolean {
    if (condition()) return true

    val timeout = mode.timeout ?: return false
    val startedTick = currentTick
    val deadlineNanos = (timeout as? Span.Seconds)?.let {
        System.nanoTime() + (it.value * 1_000_000_000L).toLong()
    }

    while (true) {
        val outOfTime = when (timeout) {
            is Span.Ticks -> currentTick - startedTick >= timeout.value
            is Span.Seconds -> System.nanoTime() >= deadlineNanos!!
        }
        if (outOfTime) return false

        when (val interval = mode.interval) {
            is Span.Ticks -> awaitTicks(interval.value)
            is Span.Seconds -> delay((interval.value * 1000).toLong())
        }

        if (condition()) return true
    }
}
