package dev.vibeported.mc.e2e.mc

import com.mojang.blaze3d.platform.InputConstants
import dev.vibeported.mc.e2e.input.InputGate
import dev.vibeported.mc.e2e.mixin.KeyboardHandlerInvoker
import dev.vibeported.mc.e2e.mixin.MouseHandlerInvoker
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonInfo
import org.lwjgl.glfw.GLFW

/**
 * Delivers input the way the operating system would.
 *
 * Every call here ends at the private method a GLFW callback invokes, so nothing downstream can
 * tell the difference: key mappings are clicked, `handleKeybinds` runs, screens receive their
 * events, and any mod listening on the way is listening on the same way. The alternative -- calling
 * `MultiPlayerGameMode` directly -- would send the same packets while skipping every one of those,
 * which is precisely the part a test of a mod wants exercised.
 *
 * All of it is render-thread work, which block bodies already are.
 */
internal object SyntheticInput {

    /**
     * Where the framework believes the pointer is, in window pixels.
     *
     * Minecraft cannot answer this once the mouse is grabbed: it stops tracking a position and
     * turns movement straight into camera rotation. So the pointer is ours to remember, and it is
     * what a move is relative to.
     */
    private var pointerX: Double = Double.NaN
    private var pointerY: Double = Double.NaN

    fun pointerX(minecraft: Minecraft): Double {
        if (pointerX.isNaN()) pointerX = minecraft.window.screenWidth / 2.0
        return pointerX
    }

    fun pointerY(minecraft: Minecraft): Double {
        if (pointerY.isNaN()) pointerY = minecraft.window.screenHeight / 2.0
        return pointerY
    }

    fun key(minecraft: Minecraft, code: Int, action: Int) {
        val scancode = GLFW.glfwGetKeyScancode(code)
        dispatch {
            (minecraft.keyboardHandler as KeyboardHandlerInvoker)
                .invokeKeyPress(minecraft.window.handle(), action, KeyEvent(code, scancode, 0))
        }
    }

    fun character(minecraft: Minecraft, codepoint: Int) {
        dispatch {
            (minecraft.keyboardHandler as KeyboardHandlerInvoker)
                .invokeCharTyped(minecraft.window.handle(), CharacterEvent(codepoint))
        }
    }

    fun button(minecraft: Minecraft, button: Int, action: Int) {
        dispatch {
            (minecraft.mouseHandler as MouseHandlerInvoker)
                .invokeOnButton(minecraft.window.handle(), MouseButtonInfo(button, 0), action)
        }
    }

    /** Moves to an absolute window pixel, clamped so the pointer cannot leave the window. */
    fun move(minecraft: Minecraft, x: Double, y: Double) {
        val clampedX = x.coerceIn(0.0, minecraft.window.screenWidth.toDouble())
        val clampedY = y.coerceIn(0.0, minecraft.window.screenHeight.toDouble())
        pointerX = clampedX
        pointerY = clampedY
        dispatch {
            (minecraft.mouseHandler as MouseHandlerInvoker)
                .invokeOnMove(minecraft.window.handle(), clampedX, clampedY)
        }
    }

    fun scroll(minecraft: Minecraft, dx: Double, dy: Double) {
        dispatch {
            (minecraft.mouseHandler as MouseHandlerInvoker)
                .invokeOnScroll(minecraft.window.handle(), dx, dy)
        }
    }

    /** GUI coordinates are what a screen thinks in; the handlers want window pixels. */
    fun guiToWindowX(minecraft: Minecraft, x: Double): Double =
        x * minecraft.window.screenWidth / minecraft.window.guiScaledWidth

    fun guiToWindowY(minecraft: Minecraft, y: Double): Double =
        y * minecraft.window.screenHeight / minecraft.window.guiScaledHeight

    const val PRESS: Int = InputConstants.PRESS
    const val RELEASE: Int = InputConstants.RELEASE

    /**
     * Marks the call as ours for the length of it.
     *
     * The same handlers drop everything else, so this flag is the only difference between a test
     * pressing a key and a person leaning on the keyboard.
     */
    private inline fun dispatch(body: () -> Unit) {
        InputGate.begin()
        try {
            body()
        } finally {
            InputGate.end()
        }
    }
}
