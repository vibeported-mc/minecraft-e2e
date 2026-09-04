package dev.vibeported.mc.driver.record

import org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0
import org.lwjgl.opengl.GL30C.GL_COLOR_BUFFER_BIT
import org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER
import org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE
import org.lwjgl.opengl.GL30C.GL_NEAREST
import org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER
import org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING
import org.lwjgl.opengl.GL30C.GL_RGBA
import org.lwjgl.opengl.GL30C.GL_RGBA8
import org.lwjgl.opengl.GL30C.GL_TEXTURE_2D
import org.lwjgl.opengl.GL30C.GL_TEXTURE_BINDING_2D
import org.lwjgl.opengl.GL30C.GL_TEXTURE_MAG_FILTER
import org.lwjgl.opengl.GL30C.GL_TEXTURE_MIN_FILTER
import org.lwjgl.opengl.GL30C.GL_UNSIGNED_BYTE
import org.lwjgl.opengl.GL30C.glBindFramebuffer
import org.lwjgl.opengl.GL30C.glBindTexture
import org.lwjgl.opengl.GL30C.glBlitFramebuffer
import org.lwjgl.opengl.GL30C.glCheckFramebufferStatus
import org.lwjgl.opengl.GL30C.glDeleteFramebuffers
import org.lwjgl.opengl.GL30C.glDeleteTextures
import org.lwjgl.opengl.GL30C.glFramebufferTexture2D
import org.lwjgl.opengl.GL30C.glGenFramebuffers
import org.lwjgl.opengl.GL30C.glGenTextures
import org.lwjgl.opengl.GL30C.glGetInteger
import org.lwjgl.opengl.GL30C.glTexImage2D
import org.lwjgl.opengl.GL30C.glTexParameteri

/**
 * Turns Minecraft's frame the right way up for a video file.
 *
 * OpenGL puts the origin of a texture at the bottom left; every video format puts it at the top
 * left. Handing the render target straight to an encoder therefore records the game upside down --
 * Minecraft's own screenshot code flips for exactly the same reason, it just does it on the CPU
 * where a recording cannot afford to.
 *
 * So the flip happens where the frame already is. One `glBlitFramebuffer` with the destination
 * rectangle inverted in Y copies the render target into a texture of our own, upside down, which is
 * to say the right way up. It is a GPU blit of one frame: next to the encode it costs nothing, and
 * unlike a read back it never stalls the render thread.
 *
 * The texture it writes into is also what CUDA registers, so its id never changes even when
 * Minecraft rebuilds its own target.
 */
internal class FrameFlipper(private val width: Int, private val height: Int) : AutoCloseable {

    private val texture = glGenTextures()
    private val readFramebuffer = glGenFramebuffers()
    private val drawFramebuffer = glGenFramebuffers()

    /** Which texture is currently attached for reading, so it is only re-attached when it changes. */
    private var source = 0

    init {
        val previousTexture = glGetInteger(GL_TEXTURE_BINDING_2D)
        val previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)

        glBindTexture(GL_TEXTURE_2D, texture)
        // GL_RGBA8, the same format Minecraft's own target uses, so the blit is a straight copy and
        // the bytes CUDA later reads are exactly what NVENC takes as packed 32-bit RGB.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        glBindTexture(GL_TEXTURE_2D, previousTexture)

        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer)
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        val status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw)
        check(status == GL_FRAMEBUFFER_COMPLETE) {
            "Recording framebuffer is incomplete: 0x${Integer.toHexString(status)}"
        }
    }

    /** The right-way-up copy. This is what gets registered with CUDA. */
    val textureId: Int get() = texture

    /**
     * Copies [from] into this flipper's texture, inverted.
     *
     * Every binding it touches is put back as it was found: this runs in the middle of Minecraft's
     * own frame, and leaving the read or draw target moved would be someone else's bug to find.
     */
    fun flip(from: Int) {
        val previousRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val previousDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)

        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer)
        if (from != source) {
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, from, 0)
            source = from
        }
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer)

        // The whole flip: read bottom-to-top, write top-to-bottom.
        glBlitFramebuffer(
            0, 0, width, height,
            0, height, width, 0,
            GL_COLOR_BUFFER_BIT, GL_NEAREST,
        )

        glBindFramebuffer(GL_READ_FRAMEBUFFER, previousRead)
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDraw)
    }

    override fun close() {
        glDeleteFramebuffers(readFramebuffer)
        glDeleteFramebuffers(drawFramebuffer)
        glDeleteTextures(texture)
    }
}
