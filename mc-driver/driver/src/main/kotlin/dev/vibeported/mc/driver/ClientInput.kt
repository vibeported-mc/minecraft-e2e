package dev.vibeported.mc.driver

import dev.vibeported.mc.driver.input.InputGate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/*
 * The keyboard and the mouse, on the [ClientScope] rather than as free methods.
 *
 * Almost everything else in this driver is a free method that opens a `client { }` of its own, and
 * these are the exception on purpose: input comes in sequences -- press, wait, release, look, click
 * -- and each free method is a round trip. Written inside one block they cost one. A caller wanting
 * a single keystroke still says `client("alex") { press(Key.W) }`, which is the same sentence.
 *
 * Everything here touches the client, so it lives apart from the plain value types in `Input.kt`. A
 * dedicated server has no `Minecraft` and no blaze3d, and a class it cannot verify is a class it
 * cannot load.
 */

// -- keyboard ---------------------------------------------------------------------------------

/** Presses a key and releases it [ticks] ticks later. */
public suspend fun ClientScope.press(key: Key, ticks: Int = 1) {
    keyDown(key)
    awaitTicks(ticks)
    keyUp(key)
}

public suspend fun ClientScope.keyDown(key: Key) {
    SyntheticInput.key(minecraft, key.code, SyntheticInput.PRESS)
    awaitTicks()
}

public suspend fun ClientScope.keyUp(key: Key) {
    SyntheticInput.key(minecraft, key.code, SyntheticInput.RELEASE)
    awaitTicks()
}

/** Types text into whatever has focus, one character event at a time. */
public suspend fun ClientScope.type(text: String) {
    text.codePoints().forEach { SyntheticInput.character(minecraft, it) }
    awaitTicks()
}

// -- mouse ------------------------------------------------------------------------------------

/** Presses a mouse button and releases it [ticks] ticks later. */
public suspend fun ClientScope.click(button: MouseButton = MouseButton.LEFT, ticks: Int = 1) {
    mouseDown(button)
    awaitTicks(ticks)
    mouseUp(button)
}

public suspend fun ClientScope.mouseDown(button: MouseButton = MouseButton.LEFT) {
    SyntheticInput.button(minecraft, button.code, SyntheticInput.PRESS)
    awaitTicks()
}

public suspend fun ClientScope.mouseUp(button: MouseButton = MouseButton.LEFT) {
    SyntheticInput.button(minecraft, button.code, SyntheticInput.RELEASE)
    awaitTicks()
}

public suspend fun ClientScope.scroll(amount: Double) {
    SyntheticInput.scroll(minecraft, 0.0, amount)
    awaitTicks()
}

/**
 * Moves the pointer to a window pixel, taking [over] to get there.
 *
 * The move is spread across ticks along an eased path rather than delivered as one jump, so a drag
 * is something a person can watch and anything sampling the mouse per frame sees a plausible track.
 * [Duration.ZERO] gives the jump back for callers that do not care.
 */
public suspend fun ClientScope.moveMouseTo(x: Double, y: Double, over: Duration = DEFAULT_MOVE) {
    val fromX = SyntheticInput.pointerX(minecraft)
    val fromY = SyntheticInput.pointerY(minecraft)

    val steps = max(1, (over.inWholeMilliseconds / MILLIS_PER_TICK).toInt())
    for (step in 1..steps) {
        val t = ease(step.toDouble() / steps)
        SyntheticInput.move(minecraft, fromX + (x - fromX) * t, fromY + (y - fromY) * t)
        if (steps > 1) awaitTicks()
    }
    if (steps == 1) awaitTicks()
}

/** @see moveMouseTo */
public suspend fun ClientScope.moveMouseTo(x: Double, y: Double, speed: Speed) {
    val dx = x - SyntheticInput.pointerX(minecraft)
    val dy = y - SyntheticInput.pointerY(minecraft)
    moveMouseTo(x, y, durationFor(sqrt(dx * dx + dy * dy), speed))
}

/** Moves the pointer by an offset. In a grabbed world this is a camera turn. @see moveMouseTo */
public suspend fun ClientScope.moveMouseBy(dx: Double, dy: Double, over: Duration = DEFAULT_MOVE) {
    moveMouseTo(
        SyntheticInput.pointerX(minecraft) + dx,
        SyntheticInput.pointerY(minecraft) + dy,
        over,
    )
}

/** @see moveMouseBy */
public suspend fun ClientScope.moveMouseBy(dx: Double, dy: Double, speed: Speed) {
    moveMouseBy(dx, dy, durationFor(sqrt(dx * dx + dy * dy), speed))
}

// -- the gate ---------------------------------------------------------------------------------

/**
 * Whether input from the machine's own keyboard and mouse reaches this client.
 *
 * Blocked from the moment the driver installs itself, because an automated client shares its
 * keyboard with whoever is watching it. Turning it off is a debugging affordance: it hands the
 * window back so you can drive it by hand.
 */
internal fun setInputBlocking(blocked: Boolean) {
    InputGate.setBlocking(blocked)
}

internal const val MILLIS_PER_TICK: Long = 50L

internal fun durationFor(distance: Double, speed: Speed): Duration =
    ((abs(distance) / speed.pixelsPerSecond) * 1000).roundToInt().milliseconds

/** Ease in and out, so a move starts and stops the way a hand does. */
internal fun ease(t: Double): Double = t * t * (3 - 2 * t)
