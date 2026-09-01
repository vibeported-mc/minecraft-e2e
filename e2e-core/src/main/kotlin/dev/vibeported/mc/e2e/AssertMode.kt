package dev.vibeported.mc.e2e

/** A length of time, in whichever unit the author found natural. */
public sealed interface Span {
    public data class Seconds(val value: Double) : Span
    public data class Ticks(val value: Int) : Span
}

/**
 * When, and how often, an assertion is evaluated.
 *
 * The default everywhere is [once]. An assertion that retries is asking to wait for something, and
 * saying so at the point where the wait actually exists reads better than a sleep before it -- and
 * fails in milliseconds when the expectation is simply wrong rather than late.
 */
public class AssertMode internal constructor(
    internal val timeout: Span?,
    internal val interval: Span,
) {
    /** Retry this often instead of every tick. */
    public fun intervalSec(seconds: Number): AssertMode =
        AssertMode(timeout, Span.Seconds(seconds.toDouble()))

    public fun intervalTicks(ticks: Int): AssertMode = AssertMode(timeout, Span.Ticks(ticks))

    override fun toString(): String = when (timeout) {
        null -> "once"
        is Span.Seconds -> "within ${timeout.value}s"
        is Span.Ticks -> "within ${timeout.value} ticks"
    }
}

/** Evaluate a single time. */
public val once: AssertMode = AssertMode(timeout = null, interval = Span.Ticks(1))

/** Retry until it holds or [seconds] have passed, once per tick unless told otherwise. */
public fun timeoutSec(seconds: Number): AssertMode =
    AssertMode(Span.Seconds(seconds.toDouble()), Span.Ticks(1))

/**
 * Retry until it holds or [ticks] game ticks have passed.
 *
 * Ticks rather than wall clock, so a busy server that ticks slowly gets the same number of chances
 * rather than fewer.
 */
public fun timeoutTicks(ticks: Int): AssertMode = AssertMode(Span.Ticks(ticks), Span.Ticks(1))
