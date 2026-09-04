package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.connectedClients
import dev.vibeported.mc.driver.server
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * That a cluster comes up at all, and that this JVM is the one it needs to be.
 *
 * Everything else in this module assumes both. If these fail, nothing after them means anything.
 */
@DrivesMinecraft
class ClusterTest {

    @Test
    @DisplayName("this test runs inside FancyModLoader's own class loader")
    fun `the class loader is the transformed one`() {
        // The claim the whole arrangement rests on, and the one that fails silently when it is
        // wrong. ModDevGradle puts the test output on `-Dfml.modFolders` so these classes belong to
        // a mod; junit-fml boots FML and swaps the thread context class loader before JUnit
        // discovers anything. Get the tested mod wrong and this test class loads on Gradle's worker
        // loader instead -- and every Minecraft type it names becomes a second copy of itself,
        // which surfaces much later as a value failing to match the type it plainly is.
        val here = javaClass.classLoader
        val game = BlockPos::class.java.classLoader

        assertSame(game, here, "test classes and Minecraft classes must come from one loader")
        assertTrue(
            "TransformingClassLoader" in here.javaClass.simpleName || "fml" in here.javaClass.name.lowercase(),
            "expected FancyModLoader's loader, got ${here.javaClass.name}",
        )
    }

    @Test
    fun `the roster knows the client`(cluster: ClusterScope) = cluster.driving {
        assertTrue(ALEX in connectedClients(), "connectedClients() said ${connectedClients()}")
    }

    @Test
    fun `a server body runs`(cluster: ClusterScope) = cluster.driving {
        assertTrue(server { minecraftServer.serverVersion }.isNotBlank())
    }

    @Test
    fun `a client body runs`(cluster: ClusterScope) = cluster.driving {
        assertNotNull(
            client(ALEX) { clientLevel?.dimension()?.toString() },
            "the client is in no level",
        )
    }
}
