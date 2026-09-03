package dev.vibeported.mc.e2e.dsl.mc

import dev.vibeported.mc.e2e.startedRole
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod

/**
 * The one thing the gameplay module has to do at startup: take the keyboard, and offer to take
 * pictures.
 *
 * It exists because the procedure layer next door should not know what a keyboard or a screenshot
 * is. Both are gameplay concerns that happen to need a hook at load time.
 *
 * Nothing here names a client class. A dedicated server loads this too, and every client-only type
 * lives behind [ClientHooks], which only a client ever loads.
 */
@Mod(DslMod.ID)
class DslMod(bus: IEventBus, container: ModContainer) {

    init {
        // Only under test: the mod sitting in an ordinary development client changes nothing.
        // Asked through `startedRole()` rather than compared to a literal here -- the literal is
        // how this stopped installing without anything failing.
        if (startedRole() == "client") {
            ClientHooks.install()
        }
    }

    companion object {
        const val ID: String = "e2e_dsl"
    }
}
