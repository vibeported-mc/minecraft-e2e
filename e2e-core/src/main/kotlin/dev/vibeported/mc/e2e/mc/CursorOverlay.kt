package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.mixin.CursorRequestAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Draws what the framework is doing to the mouse, because nothing else does.
 *
 * The machine's own cursor belongs to whoever is at the keyboard and is somewhere else entirely, so
 * without this a screenshot of a drag is a screenshot of an inventory with nothing happening in it.
 *
 * What it shows is the **physical state of the input**: the buttons this framework has pressed and
 * not yet released. Deliberately not Minecraft's opinion of them -- Minecraft only tracks buttons
 * while no screen is open, and even where it does, what it reports is an interpretation. A held
 * button is drawn as held for exactly as long as it is held, however long that turns out to be.
 */
internal object CursorOverlay {

    /** Sources are 32 across and drawn at half that, so they stay sharp at every GUI scale. */
    private const val SOURCE = 32
    private const val DRAWN = 16

    /** Clear of the aim point, so the glyph never covers the thing being clicked. */
    private const val MOUSE_OFFSET_X = 9
    private const val MOUSE_OFFSET_Y = 8

    /**
     * How long an indicator takes to go out after the input ends.
     *
     * A click can be a single tick, which at sixty frames a second is three of them -- gone before
     * anyone watching registers that it happened. Fading leaves a trace long enough to see while
     * never claiming the button is still down: full brightness means held, dimmer means just
     * released.
     */
    private const val FADE_MILLIS = 450L

    private const val OPAQUE = -1

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
        if (minecraft.gui.screen() != null) {
            // A pointer only means something in a screen: in the world the mouse is grabbed and
            // movement is camera rotation, so there is no position to draw an arrow at.
            val window = minecraft.window
            val x = minecraft.mouseHandler.getScaledXPos(window).toInt()
            val y = minecraft.mouseHandler.getScaledYPos(window).toInt()
            drawCursor(graphics, x, y, cursorName(graphics))
            drawMouseState(graphics, x + MOUSE_OFFSET_X, y + MOUSE_OFFSET_Y)
        } else {
            // The buttons still matter out here -- mining holds attack for seconds at a time -- so
            // the glyph parks beside the crosshair, where whoever is watching is already looking.
            drawMouseState(graphics, graphics.guiWidth() / 2 + 12, graphics.guiHeight() / 2 + 4)
        }
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
        blit(graphics, cursorTexture(name), x - hotspotX, y - hotspotY, OPAQUE)
    }

    /**
     * A little mouse with the pressed buttons filled in.
     *
     * Composed from parts rather than a sprite per combination: two buttons held at once has to
     * read as two buttons held at once, and three buttons against three scroll states would
     * otherwise be twenty-four pictures to draw.
     */
    private fun drawMouseState(graphics: GuiGraphicsExtractor, x: Int, y: Int) {
        blitMouse(graphics, "body", x, y, OPAQUE)

        blitButton(graphics, "left", GLFW.GLFW_MOUSE_BUTTON_LEFT, x, y)
        blitButton(graphics, "right", GLFW.GLFW_MOUSE_BUTTON_RIGHT, x, y)
        blitButton(graphics, "wheel", GLFW.GLFW_MOUSE_BUTTON_MIDDLE, x, y)

        // The arrow alone: a wheel that turned is not a wheel that was pressed, and filling the
        // button as well would say the middle button was down when it never went down.
        if (SyntheticInput.lastScroll != 0.0) {
            val alpha = fadedSince(SyntheticInput.lastScrollAtMillis)
            if (alpha != 0) {
                val part = if (SyntheticInput.lastScroll > 0) "scroll_up" else "scroll_down"
                blitMouse(graphics, part, x, y, alpha)
            }
        }
    }

    /** Full while the button is down, then fading out from the moment it was let go. */
    private fun blitButton(
        graphics: GuiGraphicsExtractor,
        part: String,
        button: Int,
        x: Int,
        y: Int,
    ) {
        val alpha = if (SyntheticInput.isHeld(button)) {
            OPAQUE
        } else {
            fadedSince(SyntheticInput.releasedAtMillis(button))
        }
        if (alpha != 0) blitMouse(graphics, part, x, y, alpha)
    }

    /** An ARGB tint going from opaque to nothing over [FADE_MILLIS]. Zero once it has faded out. */
    private fun fadedSince(atMillis: Long): Int {
        if (atMillis == 0L) return 0
        val elapsed = System.currentTimeMillis() - atMillis
        if (elapsed >= FADE_MILLIS) return 0
        val alpha = (255L * (FADE_MILLIS - elapsed) / FADE_MILLIS).toInt().coerceIn(0, 255)
        return (alpha shl 24) or 0xFFFFFF
    }

    private fun blitMouse(graphics: GuiGraphicsExtractor, part: String, x: Int, y: Int, tint: Int) {
        blit(graphics, mouseTexture(part), x, y, tint)
    }

    private fun blit(
        graphics: GuiGraphicsExtractor,
        texture: Identifier,
        x: Int,
        y: Int,
        tint: Int,
    ) {
        graphics.blit(
            RenderPipelines.GUI_TEXTURED,
            texture,
            x,
            y,
            0f,
            0f,
            DRAWN,
            DRAWN,
            SOURCE,
            SOURCE,
            SOURCE,
            SOURCE,
            tint,
        )
    }
}
