package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.dsl.mc.Screenshots
import java.io.File

/**
 * Captures what this client is looking at, and returns the file once it is written.
 *
 * Per client and per test by construction: the shot goes to
 * `screenshots/<client>/<test>/<n>-<name>.jpg`, numbered in the order the test took them, so a
 * directory reads as a story rather than as a pile. Names are prose and are escaped on the way to
 * the file system.
 *
 * It returns only once the file exists, and returns nothing: a `File` is not something that can
 * cross to another process, and a procedure that ended with one would fail at the wire rather than
 * at the keyboard. The capture is a read back from the GPU that lands a few
 * frames later, and a test that carried on regardless would be writing shots of a scene it had
 * already changed.
 */
public suspend fun ClientScope.makeScreenshot(name: String) {
    val file = Screenshots.capture(minecraft, clientName, testName, name)
    log("screenshot: ${file.absolutePath}")
}
