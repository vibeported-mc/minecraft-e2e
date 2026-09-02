package dev.vibeported.mc.e2e.dsl.mc.record

/**
 * Decides which rendered frames become recorded frames.
 *
 * The game draws whenever it likes -- hundreds of frames a second on a machine like this one, and a
 * handful during a chunk load -- while a recording wants a steady clock. This reconciles the two by
 * putting every frame on the recording's own timeline and taking one only when the timeline has
 * moved on:
 *
 * * Rendering faster than the recording: most frames are not due, and cost a subtraction to reject.
 *   The game is never slowed down to match the recording.
 * * Rendering slower: every frame is taken, and the timestamp jumps. The player holds the previous
 *   frame for longer, which is what actually happened, rather than pretending the stall was not
 *   there.
 *
 * The result is wall-clock accurate: a recording of a five second test lasts five seconds.
 */
internal class FrameClock(fps: Int) {

    private val periodNanos: Long = 1_000_000_000L / fps

    private var startedAt: Long = 0
    private var lastTimestamp: Long = -1

    fun start() {
        startedAt = System.nanoTime()
        lastTimestamp = -1
    }

    /**
     * The timestamp to give a frame taken now, or null if the recording does not want one yet.
     *
     * Timestamps are in units of [periodNanos], which is what the encoder is told its time base is,
     * so this number is already the frame's presentation timestamp.
     */
    fun timestampIfDue(): Long? {
        val timestamp = (System.nanoTime() - startedAt) / periodNanos
        if (timestamp <= lastTimestamp) return null
        lastTimestamp = timestamp
        return timestamp
    }
}
