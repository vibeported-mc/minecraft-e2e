package dev.vibeported.capture.example;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL30C.*;

/**
 * A window and an offscreen render target set up the way Minecraft sets its
 * own up, so that what this records is what a capture of the real game would
 * have to deal with.
 *
 * <p>The hints match {@code com.mojang.blaze3d.opengl.GlBackend.setWindowHints}
 * in Minecraft 26.2: OpenGL 3.3 core, forward-compatible, native context API.
 * The offscreen target matches {@code MainTarget}: a {@code GL_RGBA8} colour
 * texture ({@code GpuFormat.RGBA8_UNORM}) with a depth attachment, at 854x480
 * ({@code MainTarget.DEFAULT_WIDTH/HEIGHT}).
 *
 * <p>The {@code GL_RGBA8} choice is the one that matters downstream: its bytes
 * are R,G,B,A, which is exactly what NVENC takes as packed 32-bit RGB, so a
 * capture needs no conversion pass at all.
 */
final class MinecraftLikeWindow implements AutoCloseable {

    static final int WIDTH = 854;    // MainTarget.DEFAULT_WIDTH
    static final int HEIGHT = 480;   // MainTarget.DEFAULT_HEIGHT

    final long handle;
    final int framebuffer;
    final int colorTexture;
    private final int depthBuffer;

    MinecraftLikeWindow(String title) {
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_OPENGL_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_CREATION_API, GLFW.GLFW_NATIVE_CONTEXT_API);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.GLFW_TRUE);
        // Minecraft creates the window hidden and shows it once it is ready.
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);

        handle = GLFW.glfwCreateWindow(WIDTH, HEIGHT, title, 0L, 0L);
        if (handle == 0L) throw new IllegalStateException("Could not create the GLFW window");

        GLFW.glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        GLFW.glfwSwapInterval(0);   // record as fast as the encoder allows
        GLFW.glfwShowWindow(handle);

        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, WIDTH, HEIGHT, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glBindTexture(GL_TEXTURE_2D, 0);

        depthBuffer = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthBuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, WIDTH, HEIGHT);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        framebuffer = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depthBuffer);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Framebuffer incomplete: 0x" + Integer.toHexString(status));
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    void bindForRendering() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        glViewport(0, 0, WIDTH, HEIGHT);
    }

    /** Puts the offscreen image on screen, the way Minecraft blits its target. */
    void blitToScreen() {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, WIDTH, HEIGHT, 0, 0, WIDTH, HEIGHT, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    @Override
    public void close() {
        glDeleteFramebuffers(framebuffer);
        glDeleteRenderbuffers(depthBuffer);
        glDeleteTextures(colorTexture);
        GLFW.glfwDestroyWindow(handle);
        GLFW.glfwTerminate();
    }
}
