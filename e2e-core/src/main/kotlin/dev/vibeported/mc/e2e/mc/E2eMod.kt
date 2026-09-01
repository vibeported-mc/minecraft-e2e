package dev.vibeported.mc.e2e.mc

import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.protocol.NodeId
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import org.slf4j.LoggerFactory

/**
 * The framework mod, present in both game processes.
 *
 * It does nothing at all unless the orchestrator started this process, which is what keeps the mod
 * harmless in a normal launch: no `e2e.node.role`, no test harness.
 */
@Mod(E2eMod.ID)
class E2eMod(eventBus: IEventBus, container: ModContainer) {

    init {
        val role = System.getProperty(ROLE_PROPERTY)
        if (role == null) {
            LOG.info("e2e: no {} set, so this process is not part of a test run", ROLE_PROPERTY)
        } else {
            val host = System.getProperty("e2e.orchestrator.host", "127.0.0.1")
            val port = System.getProperty("e2e.orchestrator.port")?.toIntOrNull()
                ?: error("e2e: $ROLE_PROPERTY is set but e2e.orchestrator.port is not")

            when (role.uppercase()) {
                "SERVER" -> ServerNode.install(host, port)
                "CLIENT" -> {
                    // The orchestrator names the client on the command line; the same name is
                    // the player's username, which is how a test addresses it.
                    val name = System.getProperty("e2e.node.name") ?: DEFAULT_CLIENT
                    ClientNode.install(host, port, NodeId.client(name))
                }

                else -> error("e2e: unknown $ROLE_PROPERTY '$role'")
            }
        }
    }

    companion object {
        const val ID: String = "e2e"
        const val ROLE_PROPERTY: String = "e2e.node.role"
        val LOG: org.slf4j.Logger = LoggerFactory.getLogger("e2e")
    }
}
