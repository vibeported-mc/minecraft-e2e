package dev.vibeported.mc.e2e.dsl.mc

import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Puts each client's window somewhere it can be seen.
 *
 * Two clients both land on the operating system's default position, which is to say on top of each
 * other, and a run of two becomes a run of one you can watch. The orchestrator knows how many
 * clients there are and which this is; the monitor's size is only knowable here, so the split is
 * ordinal from there, arithmetic here.
 */
internal object WindowLayout {

    private const val INDEX_PROPERTY = "e2e.window.index"
    private const val COUNT_PROPERTY = "e2e.window.count"

    /** Enough of a gap that two windows read as two windows. */
    private const val GAP = 8

    fun apply(minecraft: Minecraft) {
        val index = System.getProperty(INDEX_PROPERTY)?.toIntOrNull() ?: return
        val count = System.getProperty(COUNT_PROPERTY)?.toIntOrNull() ?: return
        if (count <= 1) return

        val handle = minecraft.window.handle()
        val monitor = GLFW.glfwGetPrimaryMonitor()
        val video = GLFW.glfwGetVideoMode(monitor) ?: return

        val width = minecraft.window.width + GAP
        val height = minecraft.window.height + GAP

        // Prefer one row. Only when the windows genuinely do not fit does a second row start, since
        // side by side is what makes two clients comparable at a glance.
        val columns = (video.width() / width).coerceIn(1, count)
        val rows = (count + columns - 1) / columns

        val originX = ((video.width() - columns * width) / 2).coerceAtLeast(0)
        val originY = ((video.height() - rows * height) / 2).coerceAtLeast(0)

        // Clamped, because a window whose title bar is off the screen cannot be dragged back on.
        val x = (originX + (index % columns) * width)
            .coerceIn(0, (video.width() - minecraft.window.width).coerceAtLeast(0))
        val y = (originY + (index / columns) * height)
            .coerceIn(0, (video.height() - minecraft.window.height).coerceAtLeast(0))

        GLFW.glfwSetWindowPos(handle, x, y)
    }
}
