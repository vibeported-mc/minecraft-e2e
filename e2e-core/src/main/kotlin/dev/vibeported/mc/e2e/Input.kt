package dev.vibeported.mc.e2e

import org.lwjgl.glfw.GLFW
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * A key, by GLFW code.
 *
 * The GLFW constants are compile-time `Int`s, so nothing in this file refers to a client-only class
 * at runtime -- which matters, because a suite may declare a top-level `val` of one of these types
 * and a suite file is loaded on the server as well.
 */
@JvmInline
public value class Key(public val code: Int) {

    public companion object {
        public val W: Key = Key(GLFW.GLFW_KEY_W)
        public val A: Key = Key(GLFW.GLFW_KEY_A)
        public val S: Key = Key(GLFW.GLFW_KEY_S)
        public val D: Key = Key(GLFW.GLFW_KEY_D)
        public val E: Key = Key(GLFW.GLFW_KEY_E)
        public val Q: Key = Key(GLFW.GLFW_KEY_Q)
        public val F: Key = Key(GLFW.GLFW_KEY_F)
        public val T: Key = Key(GLFW.GLFW_KEY_T)
        public val SPACE: Key = Key(GLFW.GLFW_KEY_SPACE)
        public val ESCAPE: Key = Key(GLFW.GLFW_KEY_ESCAPE)
        public val ENTER: Key = Key(GLFW.GLFW_KEY_ENTER)
        public val TAB: Key = Key(GLFW.GLFW_KEY_TAB)
        public val SHIFT: Key = Key(GLFW.GLFW_KEY_LEFT_SHIFT)
        public val CONTROL: Key = Key(GLFW.GLFW_KEY_LEFT_CONTROL)

        /**
         * Anything else, by GLFW code.
         *
         * The named constants above are the ones a test reaches for; an enum of two hundred entries
         * would still be missing the one you want, and every GLFW code is a plain `Int`.
         */
        public fun code(value: Int): Key = Key(value)
    }
}

/** A mouse button, by GLFW code. */
public enum class MouseButton(public val code: Int) {
    LEFT(GLFW.GLFW_MOUSE_BUTTON_LEFT),
    RIGHT(GLFW.GLFW_MOUSE_BUTTON_RIGHT),
    MIDDLE(GLFW.GLFW_MOUSE_BUTTON_MIDDLE),
}

/** How fast the pointer travels, when a fixed duration would be wrong for a varying distance. */
@JvmInline
public value class Speed(public val pixelsPerSecond: Double)

public fun pixelsPerSecond(value: Double): Speed = Speed(value)

/** Long enough to see, short enough not to pad a suite. */
public val DEFAULT_MOVE: Duration = 300.milliseconds
