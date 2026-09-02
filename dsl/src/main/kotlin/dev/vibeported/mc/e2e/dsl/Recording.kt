package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.DEFAULT_CLIENT
import dev.vibeported.mc.e2e.MinecraftClientName
import dev.vibeported.mc.e2e.client
import dev.vibeported.mc.e2e.dsl.mc.record.ScreenRecorder
import kotlinx.serialization.Serializable

/**
 * How to record. Every value has a default, so most tests name a file and stop there.
 *
 * Crosses to the client as a block argument, which is why it is serialisable.
 */
@Serializable
public data class RecordingOptions(
    /**
     * Frames a second of the recording, which is not the rate the game renders at.
     *
     * The game draws as fast as it likes; a frame is taken only when this clock says one is due. So
     * a higher number costs encoder time and file size rather than frame rate. 30 reads well for
     * watching what a test did, 60 is worth it only for something that moves quickly.
     */
    public val fps: Int = 30,

    /** Which hardware encoder. All three are NVENC and none of them touch the CPU. */
    public val codec: VideoCodec = VideoCodec.H264,

    /**
     * How many frames may be on the GPU at once.
     *
     * The whole budget for capture: one being copied into, a few queued for the encoder, a couple
     * held by NVENC. Below about four, frames start being dropped under load; much above eight is
     * video memory doing nothing. At 1280x720 each frame is about 3.5 MB.
     */
    public val frameBufferSize: Int = 8,

    /** Constant quality, on NVENC's scale where lower is better. */
    public val quality: Int = 23,

    /** NVENC preset, `p1` (fastest) through `p7` (best quality). */
    public val preset: String = "p4",
)

/** The hardware encoders this build carries. */
@Serializable
public enum class VideoCodec(public val encoder: String) {
    /** Plays everywhere. The one to pick unless there is a reason not to. */
    H264("h264_nvenc"),

    /** Roughly a third smaller than H.264 for the same quality, and pickier about players. */
    HEVC("hevc_nvenc"),

    /** Smaller again, and needs an Ada (RTX 40) or newer card to encode at all. */
    AV1("av1_nvenc"),
}

/**
 * Records one client while the block runs.
 *
 * ```kotlin
 * e2e("two players fight") {
 *     record("alex", "fight.mp4") {
 *         client("alex") { press(Key.W) }
 *         server { ... }
 *     }
 * }
 * ```
 *
 * The body is ordinary test DSL -- `client { }`, `server { }`, anything else a test says -- and the
 * recording covers exactly it. It stops even when the body fails, which is the run most worth having
 * the video of, and it stops before the block returns, so the file is closed and complete by the
 * time the next line runs.
 *
 * **The frame never reaches the CPU.** Minecraft's main render target is a `GL_RGBA8` texture, whose
 * bytes are what NVENC takes as packed 32-bit RGB, so it is flipped the right way up on the GPU,
 * copied device to device into the encoder's own memory, and encoded there. Recording costs the game
 * one blit and one copy per recorded frame rather than a read back, and if the encoder ever falls
 * behind, frames are dropped rather than the game being made to wait.
 *
 * Needs an NVIDIA GPU on the machine running the tests. Without one the recording is refused, with
 * the reason in the client's log, and the test carries on regardless.
 *
 * @param clientName which client to record; each one sees only its own window
 * @param videoFileName the file, under `<report dir>/recordings/<client>/`
 */
public suspend fun <R> record(
    @MinecraftClientName clientName: String = DEFAULT_CLIENT,
    videoFileName: String,
    options: RecordingOptions = RecordingOptions(),
    body: suspend () -> R,
): R {
    // Everything client-side goes through a client block, arguments and all: that is the only way
    // a value reaches the process the recorder lives in.
    client(clientName, videoFileName, options) { file, settings -> startRecording(file, settings) }
    return try {
        body()
    } finally {
        client(clientName) { stopRecording() }
    }
}

/**
 * Starts recording this client, for a test that wants to bracket something by hand.
 *
 * [record] is the better shape almost always -- it cannot forget to stop. Reach for these two only
 * when the start and the stop genuinely cannot be in one block.
 */
public fun ClientScope.startRecording(videoFileName: String, options: RecordingOptions = RecordingOptions()) {
    val file = ScreenRecorder.start(clientName, videoFileName, options)
    if (file != null) log("recording to ${file.absolutePath}")
}

/** Ends the recording and closes the file. Does nothing if none was running. */
public fun ClientScope.stopRecording() {
    val file = ScreenRecorder.stop()
    if (file != null) log("recorded ${file.absolutePath}")
}
