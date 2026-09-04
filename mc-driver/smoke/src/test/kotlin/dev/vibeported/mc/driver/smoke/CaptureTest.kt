package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import dev.vibeported.mc.driver.junit.DrivesMinecraft
import dev.vibeported.mc.driver.UiLayer
import dev.vibeported.mc.driver.captureDirectory
import dev.vibeported.mc.driver.client
import dev.vibeported.mc.driver.lookAt
import dev.vibeported.mc.driver.moveMouseBy
import dev.vibeported.mc.driver.record
import dev.vibeported.mc.driver.screenshot
import dev.vibeported.mc.driver.setUiLayer
import dev.vibeported.mc.driver.teleport
import dev.vibeported.mc.driver.waitForPlayer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Pictures and video, which is the only way to find out that two of the mixins are in place.
 *
 * A screenshot is a read back from the GPU and a recording is a hook on `GameRenderer`; neither
 * announces itself when it fails to install, and nothing else in this module would notice.
 */
@DrivesMinecraft
class CaptureTest {

    @Test
    fun `the interface can be hidden and brought back`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        setUiLayer(ALEX, UiLayer.GUI, false)
        setUiLayer(ALEX, UiLayer.GUI, true)
    }

    @Test
    fun `a screenshot lands on disk`(cluster: ClusterScope) = cluster.driving {
        waitForPlayer(ALEX)
        val path = screenshot(ALEX, "smoke")
        assertTrue(File(path).isFile, "$path was not written")
    }

    @Test
    @DisplayName("a recording lands on disk with frames in it")
    fun `a recording lands`(cluster: ClusterScope) = cluster.driving(within = 2.minutes) {
        waitForPlayer(ALEX)

        // Something worth watching for a second or two: a recording of a still frame proves the
        // encoder started and nothing else.
        record(ALEX, "smoke.mp4") {
            teleport(ALEX, Where.PERCH.above(6), flying = true)
            client(ALEX) { moveMouseBy(240.0, 0.0, over = 1.seconds) }
            teleport(ALEX, Where.PERCH, flying = true)
            lookAt(ALEX, Where.GROUND)
        }

        // Where `ScreenRecorder` files one: `<capture dir>/recordings/<client>/<name>`.
        val file = File(File(File(captureDirectory(), "recordings"), ALEX), "smoke.mp4")
        assertTrue(file.isFile, "$file was not written")
        assertTrue(file.length() > 0, "$file is empty, so nothing was encoded into it")
        println("smoke: recorded ${file.length() / 1024} KiB to $file")
    }
}
