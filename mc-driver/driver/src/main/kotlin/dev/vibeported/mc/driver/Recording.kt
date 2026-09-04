package dev.vibeported.mc.driver

import kotlinx.serialization.Serializable

/**
 * How to record. Every value has a default, so most callers name a file and stop there.
 *
 * Crosses to the client as an argument, which is why it is serialisable.
 */
@Serializable
public data class RecordingOptions(
    /**
     * Frames a second of the recording, which is not the rate the game renders at.
     *
     * The game draws as fast as it likes; a frame is taken only when this clock says one is due. So
     * a higher number costs encoder time and file size rather than frame rate. 30 reads well for
     * watching what happened, 60 is worth it only for something that moves quickly.
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
