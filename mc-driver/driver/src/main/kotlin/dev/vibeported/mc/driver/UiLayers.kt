package dev.vibeported.mc.driver

import dev.vibeported.mc.driver.UiLayer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The stack of things drawn over a test client, and which of them are on.
 *
 * Bottom to top: Minecraft's own interface, then the framework's debug instruments, then the
 * cursor. A stack rather than a flag because the debug layer is coming and the cursor has to stay
 * above whatever it turns out to draw.
 *
 * The cursor is deliberately not a member of [UiLayer]. It is not chrome a test chooses to show; it
 * is the picture of what the mouse is doing, and a run that could accidentally turn it off would
 * produce screenshots that quietly lie about a drag.
 */
public object UiLayers {

    private val hidden = mutableSetOf<UiLayer>()

    public fun isVisible(layer: UiLayer): Boolean = layer !in hidden

    public fun setVisible(layer: UiLayer, visible: Boolean) {
        if (visible) hidden -= layer else hidden += layer
        if (layer == UiLayer.GUI) applyGuiVisibility()
    }

    /**
     * Hiding Minecraft's interface is Minecraft's own switch, the one F1 uses.
     *
     * Reusing it rather than intercepting the HUD render keeps every vanilla layer's own visibility
     * rule intact, and it costs nothing: an open screen is extracted whether the HUD is hidden or
     * not, which is what makes "the interface comes back the moment a screen opens" true without
     * anybody asking for it.
     */
    private fun applyGuiVisibility() {
        val minecraft = Minecraft.getInstance() ?: return
        val shouldHide = UiLayer.GUI in hidden
        if (minecraft.gui.hud.isHidden != shouldHide) {
            minecraft.gui.hud.toggle()
        }
    }

    /**
     * Draws everything above Minecraft, in stack order. Called from the tail of the frame.
     *
     * Nothing here may throw: it runs inside the game's own render extraction, where an exception
     * is a crash report rather than a failed test.
     */
    @JvmStatic
    public fun render(graphics: GuiGraphicsExtractor) {
        val minecraft = Minecraft.getInstance() ?: return
        try {
            if (isVisible(UiLayer.DEBUG)) DebugLayer.render(graphics, minecraft)
            CursorOverlay.render(graphics, minecraft)
        } catch (ignored: Throwable) {
            // Drawing an overlay is never worth taking the game down for.
        }
    }
}

/**
 * Where the debug instruments will go.
 *
 * A stub on purpose: the layer exists so the stack and its ordering are real and testable now, and
 * so adding the instruments later is filling this in rather than working out where they belong.
 */
internal object DebugLayer {
    fun render(graphics: GuiGraphicsExtractor, minecraft: Minecraft) = Unit
}
