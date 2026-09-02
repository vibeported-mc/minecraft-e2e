package dev.vibeported.capture.example;

import dev.vibeported.capture.cuda.CudaDevice;
import dev.vibeported.capture.cuda.CudaGlImage;
import dev.vibeported.capture.libav.AudioEncoder;
import dev.vibeported.capture.libav.HwFramePool;
import dev.vibeported.capture.libav.Libav;
import dev.vibeported.capture.libav.Muxer;
import dev.vibeported.capture.libav.PixelFormat;
import dev.vibeported.capture.libav.VideoEncoder;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.opengl.GL30C.glFinish;

/**
 * Renders a rotating triangle and records it to MP4 through NVENC.
 *
 * <p>The recording path never touches the CPU: the frame is rendered into a
 * {@code GL_RGBA8} texture, that texture is mapped into CUDA and copied
 * device-to-device into a CUDA frame, and NVENC encodes it from there. There is
 * no glReadPixels, no hwdownload, and no swscale anywhere in this program.
 *
 * <p>Audio is a silent AAC track, so the result is a well-formed MP4 rather
 * than a video-only file.
 */
public final class RotatingTriangleCapture {

    private static final int FPS = 60;
    private static final int SECONDS = 5;
    private static final int FRAMES = FPS * SECONDS;

    private static int framesWritten;
    private static double elapsedSeconds;

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length > 0 ? args[0] : "out.mp4");

        Libav.init();
        Libav.logLevel(Libav.LogLevel.ERROR);
        System.out.println("libavcodec " + Libav.codecVersion()
                + "  natives from " + dev.vibeported.capture.libav.NativeBootstrap.directory());

        try (MinecraftLikeWindow window = new MinecraftLikeWindow("e2e-capture -- rotating triangle");
             Triangle triangle = new Triangle();
             // The GL context must exist before this: it picks the CUDA device
             // that is actually driving it, which matters on a multi-GPU box.
             CudaDevice cuda = CudaDevice.forCurrentGlContext()) {

            System.out.println("CUDA device " + cuda.ordinal() + ": " + cuda.name());
            System.out.println("Capture format: " + PixelFormat.RGB0
                    + " (libav calls it " + PixelFormat.RGB0.libavName() + ")");

            try (HwFramePool frames = cuda.framePool(
                        MinecraftLikeWindow.WIDTH, MinecraftLikeWindow.HEIGHT, PixelFormat.RGB0, 8);
                 CudaGlImage capture = cuda.registerGlTexture(window.colorTexture);
                 Muxer muxer = Muxer.create(output);
                 VideoEncoder video = VideoEncoder.h264Nvenc()
                         .frames(frames)
                         .fps(FPS)
                         .gopSize(FPS)
                         // Before opening, which is the only moment libavcodec reads it.
                         .globalHeader(true)
                         .option("preset", "p4")
                         .option("tune", "hq")
                         .option("rc", "vbr")
                         .option("cq", "23")
                         .open();
                 AudioEncoder audio = AudioEncoder.aac()
                         .sampleRate(48_000)
                         .channels(2)
                         .globalHeader(true)
                         .open()) {

                Muxer.Stream videoStream = muxer.add(video);
                Muxer.Stream audioStream = muxer.add(audio);
                muxer.open();

                // Frame timestamps are a pure function of the frame index, so the
                // recording is identical on any machine -- which is what an e2e
                // harness wants. But rendering unpaced on a fast GPU finishes
                // 300 frames in well under a second, and the file still says
                // five seconds, so the window would show the triangle spinning
                // ten times faster than the video ever will. Pace the loop to
                // the same clock the timestamps use and the two agree.
                final long frameNanos = 1_000_000_000L / FPS;
                final long startedAt = System.nanoTime();

                long audioSamples = 0;
                long audioSamplesPerFrame = audio.frameSize();
                long audioSamplesNeeded;

                for (int i = 0; i < FRAMES; i++) {
                    GLFW.glfwPollEvents();
                    if (GLFW.glfwWindowShouldClose(window.handle)) break;

                    window.bindForRendering();
                    triangle.draw((float) (i * 2.0 * Math.PI / (FPS * 2)));

                    // CUDA cannot see the GL queue, so the drawing has to be
                    // finished before the texture is copied out of.
                    glFinish();

                    try (var frame = frames.acquire()) {
                        frame.pts(i);
                        capture.copyInto(frame);
                        video.encode(frame, p -> muxer.write(p, videoStream));
                    }

                    // Keep the silent track level with the video clock.
                    audioSamplesNeeded = (long) (i + 1) * audio.sampleRate() / FPS;
                    while (audioSamples + audioSamplesPerFrame <= audioSamplesNeeded) {
                        try (var silence = audio.silence(audioSamples)) {
                            audio.encode(silence, p -> muxer.write(p, audioStream));
                        }
                        audioSamples += audioSamplesPerFrame;
                    }

                    window.blitToScreen();
                    GLFW.glfwSwapBuffers(window.handle);

                    long dueAt = startedAt + (long) (i + 1) * frameNanos;
                    long waitFor = dueAt - System.nanoTime();
                    if (waitFor > 0) LockSupport.parkNanos(waitFor);
                    framesWritten = i + 1;
                }

                elapsedSeconds = (System.nanoTime() - startedAt) / 1e9;

                // NVENC holds several frames in flight; without draining, the
                // tail of the recording is simply absent.
                video.drain(p -> muxer.write(p, videoStream));
                audio.drain(p -> muxer.write(p, audioStream));
            }
        }

        System.out.printf("Wrote %s (%d KiB): %d frames of video time, %.1fs wall clock%n",
                output.toAbsolutePath(), java.nio.file.Files.size(output) / 1024,
                framesWritten, elapsedSeconds);
    }
}
