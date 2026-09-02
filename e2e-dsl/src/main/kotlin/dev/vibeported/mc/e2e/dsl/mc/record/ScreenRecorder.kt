package dev.vibeported.mc.e2e.dsl.mc.record

import com.mojang.blaze3d.opengl.GlTexture
import dev.vibeported.capture.cuda.CudaDevice
import dev.vibeported.capture.cuda.CudaGlImage
import dev.vibeported.capture.libav.AudioEncoder
import dev.vibeported.capture.libav.Frame
import dev.vibeported.capture.libav.HwFramePool
import dev.vibeported.capture.libav.Libav
import dev.vibeported.capture.libav.Muxer
import dev.vibeported.capture.libav.PixelFormat
import dev.vibeported.capture.libav.VideoEncoder
import dev.vibeported.mc.e2e.dsl.RecordingOptions
import dev.vibeported.mc.e2e.dsl.VideoCodec
import net.minecraft.client.Minecraft
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Records what a client is drawing, straight off the GPU.
 *
 * The frame never reaches the CPU. Minecraft's main render target is a `GL_RGBA8` texture; that is
 * registered with CUDA once, copied device to device into a CUDA frame on each recorded frame, and
 * encoded by NVENC out of the same memory. No read back, no format conversion: the cost to the game
 * is one pitched copy per recorded frame.
 *
 * Two threads. The render thread does the copy and nothing else; a recorder thread owns the encoder,
 * the muxer and the file. They meet at a small bounded queue that the render thread never waits on
 * -- when the encoder falls behind, frames are dropped and counted. A recording is allowed to be
 * worse; the test it is recording is not allowed to be slower.
 */
public object ScreenRecorder {

    /** The clock of the recording in progress. Rebuilt per recording, since fps is per call. */
    private var clock: FrameClock? = null

    /** Written and read on the render thread only. Null when nothing is being recorded. */
    private var session: Session? = null

    /** Why recording stopped, if it did. Kept so the same complaint is not printed every frame. */
    private var refusal: String? = null

    /**
     * Begins a recording, ending whatever was being recorded before.
     *
     * Nothing is built here. How big the picture is belongs to the render target, and this may be
     * called long before the next frame is drawn, so the first frame that comes due does the work.
     */
    fun start(client: String, videoFileName: String, options: RecordingOptions): File? {
        stop()
        val file = fileFor(client, videoFileName) ?: return null
        session = Session(file, options)
        clock = FrameClock(options.fps).also { it.start() }
        return file
    }

    /** Finishes the current recording and returns its file, or null if there was not one. */
    fun stop(): File? {
        val current = session ?: return null
        session = null
        clock = null
        return current.finish()
    }

    /**
     * Called on the render thread once a frame is complete, before it reaches the window.
     *
     * This runs for every frame the game draws whether or not anything is being recorded, so the two
     * early exits matter more than the rest of this class put together.
     */
    @JvmStatic
    public fun onFrameRendered(minecraft: Minecraft) {
        val current = session ?: return
        val timestamp = clock?.timestampIfDue() ?: return

        val target = minecraft.gameRenderer.mainRenderTarget()
        val texture = target.colorTexture
        if (texture !is GlTexture) {
            // Minecraft 26.2 can run on Vulkan, where there is no GL texture to hand CUDA. Nothing
            // is wrong; this capture path simply does not apply.
            stopBecause("the render target is not an OpenGL texture, so this client is not on the GL backend")
            return
        }

        try {
            current.capture(texture.glId(), target.width, target.height, timestamp)
        } catch (failure: Throwable) {
            // A recording that breaks must not take the test with it.
            stopBecause("capture failed: $failure")
        }
    }

    /**
     * Where a recording goes: `<report dir>/recordings/<client>/<name>`.
     *
     * The name a test gave is used as it wrote it, with an `.mp4` added when it did not, so a file
     * is findable by the name in the suite rather than by a mangling of it.
     */
    fun fileFor(client: String, videoFileName: String): File? {
        val root = System.getProperty("e2e.report.dir") ?: return null
        val directory = File(File(root, "recordings"), sanitise(client))
        directory.mkdirs()
        val name = sanitise(videoFileName.removeSuffix(".mp4")) + ".mp4"
        return File(directory, name)
    }

    private fun stopBecause(reason: String) {
        stop()
        if (refusal == reason) return
        refusal = reason
        println("e2e: screen recording stopped -- $reason")
    }

    /**
     * One recording: one file, one encoder, one thread.
     *
     * Everything but [capture] happens on the recorder thread, so the encoder, the muxer and the
     * file are owned by exactly one thread for the whole of their lives.
     */
    private class Session(private val file: File, private val options: RecordingOptions) {

        /**
         * Deliberately short. A deep queue only lets the recorder fall further behind before anyone
         * notices, and every frame in it is a frame of video memory held hostage.
         */
        private val queue = ArrayBlockingQueue<Work>(4)

        private val dropped = AtomicInteger()
        private val encoded = AtomicInteger()

        private var device: CudaDevice? = null
        private var pool: HwFramePool? = null
        private var image: CudaGlImage? = null
        private var flipper: FrameFlipper? = null
        private var width = 0
        private var height = 0

        private var worker: Thread? = null

        @Volatile
        private var broken: String? = null

        /** A frame on its way to the encoder, or word that there will be no more. */
        private sealed interface Work {
            class Encode(val frame: Frame) : Work
            data object End : Work
        }

        fun capture(glTexture: Int, targetWidth: Int, targetHeight: Int, timestamp: Long) {
            broken?.let { error(it) }

            when {
                pool == null -> open(glTexture, targetWidth, targetHeight)

                targetWidth != width || targetHeight != height ->
                    // An encoder is fixed at the size it opened with. Rather than quietly record a
                    // stretched picture, stop: what has been written so far is valid and playable.
                    error("the window resized mid-recording, ${width}x$height to ${targetWidth}x$targetHeight")

            }

            val frame = try {
                pool!!.acquire()
            } catch (exhausted: RuntimeException) {
                // Every frame in the pool is in flight. Skipping this one is the right answer:
                // waiting for the encoder here would put its backlog into the game's frame time.
                dropped.incrementAndGet()
                return
            }

            try {
                frame.pts(timestamp)
                // Right way up first, then off the GPU. Minecraft may have rebuilt its target
                // since the last frame; the flipper re-attaches when the id changes, and what CUDA
                // reads is our own texture either way.
                flipper!!.flip(glTexture)
                image!!.copyInto(frame)
            } catch (failure: Throwable) {
                frame.close()
                throw failure
            }

            if (!queue.offer(Work.Encode(frame))) {
                dropped.incrementAndGet()
                frame.close()
            }
        }

        private fun open(glTexture: Int, targetWidth: Int, targetHeight: Int) {
            Libav.init()
            // Needs a current GL context, which on this thread there is: the device is chosen by
            // asking CUDA which of its devices is driving this very context.
            val cuda = CudaDevice.forCurrentGlContext()
            device = cuda
            width = targetWidth
            height = targetHeight
            pool = cuda.framePool(targetWidth, targetHeight, PixelFormat.RGB0, options.frameBufferSize)
            flipper = FrameFlipper(targetWidth, targetHeight)
            image = cuda.registerGlTexture(flipper!!.textureId)

            worker = Thread({ encodeLoop() }, "e2e-screen-recorder").apply {
                isDaemon = true
                start()
            }

            println(
                "e2e: recording ${targetWidth}x$targetHeight at ${options.fps} fps with " +
                    "${options.codec.encoder} on ${cuda.name()} into ${file.absolutePath}"
            )
        }

        /**
         * The recorder thread: everything that is not the copy.
         *
         * The encoder and muxer are built here rather than handed in so that one thread owns them
         * from beginning to end and none of this has to be thread safe.
         */
        private fun encodeLoop() {
            var muxer: Muxer? = null
            var video: VideoEncoder? = null
            var audio: AudioEncoder? = null
            try {
                // Fragmented, because the orchestrator terminates clients rather than asking
                // them to leave: a recording has to be playable without ever being closed.
                muxer = Muxer.create(file.toPath()).fragmented()
                // Settled before the encoders open, not after: libavcodec reads this flag when
                // the codec opens, and a fragmented MP4 writes its header before the first packet,
                // so there is no second chance to put the codec configuration anywhere.
                val globalHeader = muxer.globalHeaderRequired()

                video = builderFor(options.codec)
                    .frames(pool!!)
                    .fps(options.fps)
                    .gopSize(options.fps)
                    .globalHeader(globalHeader)
                    .option("preset", options.preset)
                    .option("rc", "vbr")
                    .option("cq", options.quality.toString())
                    .open()
                audio = AudioEncoder.aac()
                    .sampleRate(SAMPLE_RATE)
                    .channels(2)
                    .globalHeader(globalHeader)
                    .open()
                val videoStream = muxer.add(video)
                val audioStream = muxer.add(audio)
                muxer.open()

                var samplesWritten = 0L
                val samplesPerFrame = audio.frameSize().toLong()

                while (true) {
                    val work = queue.poll(POLL_SECONDS, TimeUnit.SECONDS) ?: continue
                    if (work is Work.End) break
                    val frame = (work as Work.Encode).frame
                    frame.use {
                        val presentedAt = it.pts()
                        video.encode(it) { packet -> muxer.write(packet, videoStream) }
                        encoded.incrementAndGet()

                        // Keep the silent track level with the picture. A video-only MP4 is legal,
                        // but plenty of players and browser pipelines are happier with a track.
                        val samplesDue = presentedAt * SAMPLE_RATE / options.fps
                        while (samplesWritten + samplesPerFrame <= samplesDue) {
                            audio.silence(samplesWritten).use { silence ->
                                audio.encode(silence) { packet -> muxer.write(packet, audioStream) }
                            }
                            samplesWritten += samplesPerFrame
                        }
                    }
                }

                // NVENC keeps several frames in flight; without draining, the tail is missing.
                video.drain { packet -> muxer.write(packet, videoStream) }
                audio.drain { packet -> muxer.write(packet, audioStream) }
            } catch (failure: Throwable) {
                broken = "recorder thread failed: $failure"
                println("e2e: screen recording failed -- $failure")
            } finally {
                // The trailer has to be written before anything it refers to goes away, and every
                // queued frame has to be released whatever else happened.
                runCatching { muxer?.close() }
                runCatching { video?.close() }
                runCatching { audio?.close() }
                releaseQueued()
            }
        }

        private fun builderFor(codec: VideoCodec): VideoEncoder.Builder = when (codec) {
            VideoCodec.H264 -> VideoEncoder.h264Nvenc()
            VideoCodec.HEVC -> VideoEncoder.hevcNvenc()
            VideoCodec.AV1 -> VideoEncoder.av1Nvenc()
        }

        private fun releaseQueued() {
            while (true) {
                val work = queue.poll() ?: break
                (work as? Work.Encode)?.frame?.close()
            }
        }

        /** Stops the recorder thread, waits for the file to be closed, and lets the GPU go. */
        fun finish(): File {
            queue.offer(Work.End, OFFER_SECONDS, TimeUnit.SECONDS)
            worker?.join(JOIN_MILLIS)

            image?.close()
            flipper?.close()
            pool?.close()
            device?.close()
            image = null
            flipper = null
            pool = null
            device = null

            if (encoded.get() == 0 && worker == null) {
                // Started and stopped without a frame ever coming due, so there is no file to
                // claim: saying otherwise sends someone looking for one.
                println("e2e: nothing was recorded for ${file.name}")
                return file
            }

            val lost = dropped.get()
            println(
                "e2e: recorded ${encoded.get()} frames into ${file.absolutePath}" +
                    if (lost > 0) " ($lost dropped, the encoder could not keep up)" else ""
            )
            return file
        }

        private companion object {
            const val SAMPLE_RATE = 48_000
            const val POLL_SECONDS = 5L
            const val OFFER_SECONDS = 5L
            const val JOIN_MILLIS = 30_000L
        }
    }

    /**
     * Turns a sentence into something a file system will accept.
     *
     * The same treatment screenshots get, and for the same reason: test names are prose.
     */
    private fun sanitise(name: String): String {
        val cleaned = buildString {
            name.trim().forEach { character ->
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    character == '-' || character == '_' -> append(character)
                    else -> append('-')
                }
            }
        }
        return cleaned.replace(Regex("-+"), "-").trim('-').ifEmpty { "unnamed" }.take(80)
    }
}
