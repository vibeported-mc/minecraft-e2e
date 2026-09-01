package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.mixin.CursorRequestAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Draws the pointer a test is driving, because nothing else does.
 *
 * The framework moves a pointer that has no picture: the machine's own cursor belongs to whoever is
 * at the keyboard and is somewhere else entirely. Without this, a screenshot of a drag is a
 * screenshot of an inventory with nothing happening in it.
 *
 * Only while a screen is open. In the world the mouse is grabbed and movement is camera rotation,
 * so there is no position a pointer could point at.
 */
internal object CursorOverlay {

    /** Sources are 32 across and drawn at half that, so they stay sharp at every GUI scale. */
    private const val SOURCE = 32
    private const val DRAWN = 16

    private const val MOUSE_SOURCE_WIDTH = 32
    private const val MOUSE_SOURCE_HEIGHT = 32
    private const val MOUSE_DRAWN_WIDTH = 16
    private const val MOUSE_DRAWN_HEIGHT = 16

    /** Clear of the aim point, so the glyph never covers the thing being clicked. */
    private const val MOUSE_OFFSET_X = 9
    private const val MOUSE_OFFSET_Y = 8

    /** Long enough to notice in a screenshot, short enough not to outlive the gesture. */
    private const val SCROLL_SHOWN_MILLIS = 600L

    private const val NO_TINT = -1

    /**
     * The mouse while a stack rides the cursor.
     *
     * Carrying is not a held button -- Minecraft moves an item with a click, a move and another
     * click, and holding the button is the quick-craft gesture instead -- so the state has a colour
     * of its own rather than a filled button that would be a lie about what the mouse is doing.
     */
    private const val CARRYING_TINT = 0xFFFFC46B.toInt()

    /**
     * Where each cursor points from.
     *
     * An arrow points from its tip, a crosshair from its middle. Drawing every one from its top-left
     * would put the picture a few pixels from where the click actually lands, which is the exact lie
     * this overlay exists to prevent. In drawn pixels, so half the source coordinates.
     */
    private val HOTSPOTS = mapOf(
        "arrow" to (1 to 0),
        "default" to (1 to 0),
        "pointing_hand" to (6 to 1),
        "ibeam" to (8 to 8),
        "crosshair" to (8 to 8),
        "resize_ns" to (8 to 8),
        "resize_ew" to (8 to 8),
        "resize_all" to (8 to 8),
        "not_allowed" to (8 to 8),
    )

    private fun cursorTexture(name: String) =
        Identifier.fromNamespaceAndPath("e2e", "textures/gui/cursor/$name.png")

    private fun mouseTexture(part: String) =
        Identifier.fromNamespaceAndPath("e2e", "textures/gui/mouse/$part.png")

    fun render(graphics: GuiGraphicsExtractor, minecraft: Minecraft) {
        if (minecraft.gui.screen() == null) return

        val window = minecraft.window
        val x = minecraft.mouseHandler.getScaledXPos(window).toInt()
        val y = minecraft.mouseHandler.getScaledYPos(window).toInt()

        drawCursor(graphics, x, y, cursorName(graphics))
        drawMouseState(graphics, x, y, isCarrying(minecraft))
    }

    /**
     * Whichever cursor the interface asked for, by name.
     *
     * A name rather than an identity check against the private constants, so a cursor type added
     * upstream falls back to the arrow instead of breaking the overlay.
     */
    private fun cursorName(graphics: GuiGraphicsExtractor): String {
        val requested = (graphics as CursorRequestAccessor).pendingCursor?.toString() ?: "arrow"
        return if (requested in HOTSPOTS) requested else "arrow"
    }

    private fun drawCursor(graphics: GuiGraphicsExtractor, x: Int, y: Int, name: String) {
        val (hotspotX, hotspotY) = HOTSPOTS[name] ?: (1 to 0)
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            cursorTexture(name),
            x - hotspotX,
            y - hotspotY,
            0f,
            0f,
            DRAWN,
            DRAWN,
            SOURCE,
            SOURCE,
            SOURCE,
            SOURCE,
            NO_TINT,
        )
    }

    /**
     * A little mouse beside the pointer, with the held buttons filled in.
     *
     * Composed from parts rather than a sprite per combination: two buttons held at once has to
     * read as two buttons held at once, and three buttons against three scroll states would
     * otherwise be twenty-four pictures to draw.
     */
    /** Whether a stack is riding the cursor, which only a container screen can answer. */
    private fun isCarrying(minecraft: Minecraft): Boolean {
        val screen = minecraft.gui.screen() as? AbstractContainerScreen<*> ?: return false
        return !screen.menu.carried.isEmpty
    }

    private fun drawMouseState(graphics: GuiGraphicsExtractor, x: Int, y: Int, carrying: Boolean) {
        val left = x + MOUSE_OFFSET_X
        val top = y + MOUSE_OFFSET_Y

        blitMouse(graphics, "body", left, top, if (carrying) CARRYING_TINT else NO_TINT)

        if (SyntheticInput.isHeld(GLFW.GLFW_MOUSE_BUTTON_LEFT)) blitMouse(graphics, "left", left, top)
        if (SyntheticInput.isHeld(GLFW.GLFW_MOUSE_BUTTON_RIGHT)) blitMouse(graphics, "right", left, top)
        if (SyntheticInput.isHeld(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) blitMouse(graphics, "wheel", left, top)

        // The arrow alone: a wheel that turned is not a wheel that was pressed, and filling the
        // button as well would say the middle button was down when it was not.
        val sinceScroll = System.currentTimeMillis() - SyntheticInput.lastScrollAtMillis
        if (SyntheticInput.lastScroll != 0.0 && sinceScroll < SCROLL_SHOWN_MILLIS) {
            blitMouse(graphics, if (SyntheticInput.lastScroll > 0) "scroll_up" else "scroll_down", left, top)
        }
    }

    private fun blitMouse(
        graphics: GuiGraphicsExtractor,
        part: String,
        x: Int,
        y: Int,
        tint: Int = NO_TINT,
    ) {
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            mouseTexture(part),
            x,
            y,
            0f,
            0f,
            MOUSE_DRAWN_WIDTH,
            MOUSE_DRAWN_HEIGHT,
            MOUSE_SOURCE_WIDTH,
            MOUSE_SOURCE_HEIGHT,
            MOUSE_SOURCE_WIDTH,
            MOUSE_SOURCE_HEIGHT,
            tint,
        )
    }
}
