package dev.vibeported.mc.driver

import dev.vibeported.mc.driver.input.InputGate
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything a driven client sets up once, kept behind its own class.
 *
 * Loaded only from a client, so a dedicated server never has to resolve `Minecraft` to decide it has
 * nothing to do here. Two jobs, and they are the two a driver cannot do without: take the keyboard,
 * and put the window somewhere it can be watched.
 */
internal object ClientHooks {

    fun install() {
        // From here the keyboard belongs to the driver, so a stray keystroke aimed at another window
        // cannot reach a client that is being driven.
        InputGate.install(true)

        // Said out loud because the alternative is what happened once: the role this mod checks for
        // was re-cased on the launcher side, these hooks quietly stopped installing, and nothing
        // failed -- nothing asserts on the keyboard being taken. A line in the client log is the
        // cheapest thing that would have caught it.
        println("mcdriver: client hooks installed for `" + startedNodeName() + "` -- input gated")

        // The window has to exist before it can be moved, so this waits for the first client tick.
        val moved = AtomicBoolean(false)
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            if (moved.compareAndSet(false, true)) {
                WindowLayout.apply(Minecraft.getInstance())
            }
        }
    }
}
