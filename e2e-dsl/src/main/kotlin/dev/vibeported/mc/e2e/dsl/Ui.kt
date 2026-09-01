package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.dsl.mc.UiLayers

/**
 * What is drawn over a test client, bottom to top.
 *
 * The cursor is deliberately absent: it is not chrome a test chooses to show but the picture of
 * what the mouse is doing, and it is always drawn last, above whatever a layer here has put on the
 * screen.
 */
public enum class UiLayer {
    /** Everything Minecraft draws for itself: the HUD, and any open screen. */
    GUI,

    /** The framework's debug instruments. Nothing draws here yet. */
    DEBUG,
}

/**
 * Whether Minecraft's own interface is drawn on this client.
 *
 * `ui = false` leaves the world and nothing else, which is what a screenshot of a contraption
 * usually wants -- no hotbar, no hearts, no chat backlog across the middle of it.
 *
 * **An open screen is drawn regardless.** Interacting with an invisible inventory would prove
 * nothing and show less, so opening one brings the interface back for as long as it is open, with
 * nothing to call.
 *
 * The change lands on the next rendered frame, which is the honest description of a rendering flag.
 */
public var ClientScope.ui: Boolean
    get() = UiLayers.isVisible(UiLayer.GUI)
    set(value) = UiLayers.setVisible(UiLayer.GUI, value)

/** Turns one layer on or off and waits for the frame that shows it. @see ui */
public suspend fun ClientScope.setUiLayer(layer: UiLayer, visible: Boolean) {
    UiLayers.setVisible(layer, visible)
    awaitTicks()
}

/**
 * Turns [layers] on for the length of [body], and puts them back afterwards.
 *
 * The way to get the hotbar into one screenshot of an otherwise bare world. Restored even when the
 * body throws, so a failing assertion cannot leave a client dressed differently from the rest of
 * the run.
 */
public suspend fun <T> ClientScope.enableUiLayer(vararg layers: UiLayer, body: suspend () -> T): T {
    val before = layers.associateWith { UiLayers.isVisible(it) }
    layers.forEach { UiLayers.setVisible(it, true) }
    awaitTicks()
    return try {
        body()
    } finally {
        before.forEach { (layer, wasVisible) -> UiLayers.setVisible(layer, wasVisible) }
    }
}
