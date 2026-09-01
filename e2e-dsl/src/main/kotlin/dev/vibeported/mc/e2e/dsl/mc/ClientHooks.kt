package dev.vibeported.mc.e2e.dsl.mc

import dev.vibeported.mc.e2e.dsl.input.InputGate
import dev.vibeported.mc.e2e.node.FailureArtifacts
import net.minecraft.client.Minecraft
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Everything a test client sets up once, kept behind its own class.
 *
 * Loaded only from a client, so a dedicated server never has to resolve `Minecraft` to decide it
 * has nothing to do here.
 */
internal object ClientHooks {

    fun install() {
        // From here the keyboard belongs to the tests, so a stray keystroke aimed at another window
        // cannot reach a client that is being driven.
        InputGate.install(true)

        // The window has to exist before it can be moved, so this waits for the first client tick.
        val moved = AtomicBoolean(false)
        NeoForge.EVENT_BUS.addListener<ClientTickEvent.Post> {
            if (moved.compareAndSet(false, true)) {
                WindowLayout.apply(Minecraft.getInstance())
            }
        }

        // Photographing a failure is registered rather than built in: only this side knows how to
        // take a picture, and the transport has no idea what one is.
        FailureArtifacts.capturer = { procedure, label ->
            Screenshots.capture(
                minecraft = Minecraft.getInstance(),
                client = System.getProperty("e2e.node.name") ?: "client",
                test = label,
                name = "failed - " + procedure.value.substringAfterLast('/'),
            ).absolutePath
        }
    }
}
