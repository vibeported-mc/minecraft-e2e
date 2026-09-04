package dev.vibeported.mc.driver

/**
 * What is drawn over a driven client, bottom to top.
 *
 * The cursor is deliberately absent: it is not chrome anybody chooses to show but the picture of
 * what the mouse is doing, and it is always drawn last, above whatever a layer here has put on the
 * screen.
 */
public enum class UiLayer {
    /** Everything Minecraft draws for itself: the HUD, and any open screen. */
    GUI,

    /** The driver's own instruments. Nothing draws here yet. */
    DEBUG,
}
