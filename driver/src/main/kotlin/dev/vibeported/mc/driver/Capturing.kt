package dev.vibeported.mc.driver

import dev.vibeported.mc.driver.record.ScreenRecorder
import dev.vibeported.rpc.currentNode

/*
 * Pictures, video, and the chrome that ends up in them.
 *
 * All free methods, all written as a `client { }`. Where the files go is not decided here: the
 * driver is told a directory with `mcdriver.capture.dir` and files everything under it by client,
 * because naming a directory after a run or a test is the business of whatever is driving.
 */

/**
 * Takes a picture of what a client is looking at, and returns where it was written.
 *
 * Suspends until the file is really there. The capture is a read back from the GPU that finishes
 * some frames after it is asked for, so a call that returned earlier would hand back a path to
 * nothing.
 */
public suspend fun screenshot(client: String, name: String): String =
    client(client, name) { title -> Screenshots.capture(minecraft, clientName, title).absolutePath }

/**
 * Records one client while [body] runs.
 *
 * ```kotlin
 * record("alex", "fight.mp4") {
 *     client("alex") { press(Key.W) }
 *     teleport("steve", there)
 * }
 * ```
 *
 * [body] is ordinary caller code -- it runs here, not on the client -- so it takes no receiver and
 * adds nothing to what is written inside it. The recording covers exactly it, and stops even when it
 * throws, which is the run most worth having the video of. It stops before this returns, so the file
 * is closed and complete by the next line.
 *
 * **The frame never reaches the CPU.** Minecraft's main render target is a `GL_RGBA8` texture, whose
 * bytes are what NVENC takes as packed 32-bit RGB, so it is flipped the right way up on the GPU,
 * copied device to device into the encoder's own memory, and encoded there. Recording costs the game
 * one blit and one copy per recorded frame rather than a read back, and when the encoder falls
 * behind, frames are dropped rather than the game being made to wait.
 *
 * Needs an NVIDIA GPU on the machine running the game. Without one the recording is refused, with
 * the reason in that client's log, and everything carries on regardless.
 */
public suspend fun <R> record(
    client: String,
    file: String,
    options: RecordingOptions = RecordingOptions(),
    body: suspend () -> R,
): R {
    startRecording(client, file, options)
    return try {
        body()
    } finally {
        stopRecording(client)
    }
}

/**
 * Starts recording a client, for a caller that wants to bracket something by hand.
 *
 * [record] is the better shape almost always -- it cannot forget to stop. Reach for these two only
 * when the start and the stop genuinely cannot be in one block.
 */
public suspend fun startRecording(
    client: String,
    file: String,
    options: RecordingOptions = RecordingOptions(),
) {
    client(client, file, options) { name, settings ->
        ScreenRecorder.start(clientName, name, settings)
        Unit
    }
}

/** Ends the recording and closes the file. Does nothing if none was running. */
public suspend fun stopRecording(client: String) {
    client(client) {
        ScreenRecorder.stop()
        Unit
    }
}

/**
 * Turns one drawn layer on or off, and waits for the frame that shows it.
 *
 * `setUiLayer(client, UiLayer.GUI, false)` leaves the world and nothing else, which is what a
 * picture of a contraption usually wants -- no hotbar, no hearts, no chat backlog across the middle.
 *
 * **An open screen is drawn regardless.** Driving an invisible inventory would prove nothing and
 * show less, so opening one brings the interface back for as long as it is open.
 */
public suspend fun setUiLayer(client: String, layer: UiLayer, visible: Boolean) {
    client(client, layer, visible) { which, show ->
        UiLayers.setVisible(which, show)
        awaitTicks()
    }
}

/** Whether that layer is currently drawn on that client. */
public suspend fun uiVisible(client: String, layer: UiLayer): Boolean =
    client(client, layer) { which -> UiLayers.isVisible(which) }

/**
 * Turns [layers] on for the length of [body], and puts them back afterwards.
 *
 * The way to get the hotbar into one picture of an otherwise bare world. Restored even when the body
 * throws, so a failure cannot leave a client dressed differently from everything after it.
 */
public suspend fun <R> enableUiLayer(
    client: String,
    vararg layers: UiLayer,
    body: suspend () -> R,
): R {
    val before = layers.associateWith { uiVisible(client, it) }
    layers.forEach { setUiLayer(client, it, true) }
    return try {
        body()
    } finally {
        before.forEach { (layer, wasVisible) -> setUiLayer(client, layer, wasVisible) }
    }
}

/**
 * Whether the machine's own keyboard and mouse reach a client.
 *
 * Blocked from the moment the driver installs itself, because an automated client shares its
 * keyboard with whoever is watching it. Unblocking is a debugging affordance: it hands the window
 * back so you can drive it by hand.
 */
public suspend fun blockInput(client: String, blocked: Boolean = true) {
    client(client, blocked) { block ->
        setInputBlocking(block)
        awaitTicks()
    }
}

/**
 * The clients currently in the cluster, by name.
 *
 * Read off this node's own roster rather than asked of anybody, so it costs nothing and says what
 * this process would actually be able to address right now.
 */
public suspend fun connectedClients(): Set<String> =
    currentNode().membership.snapshot()
        .filter { CLIENT_ROLE in it.roles }
        .map { it.id.value }
        .toSet()
