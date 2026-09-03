package dev.vibeported.mc.driver

import java.io.File

/**
 * Where a picture or a recording goes, and what it may be called.
 *
 * A driver is *told* where to write, by [CAPTURE_DIR_PROPERTY], and files everything under one
 * directory per client. It does not know what a run is, or a test, so it invents no directory level
 * to hold one -- whatever is driving names the file, and the names are what carry the meaning.
 */
internal object Capture {

    /** `<capture dir>/<kind>/<client>`, or null when nobody said where to write. */
    fun directory(kind: String, client: String): File? =
        captureDirectory()?.let { File(File(it, kind), sanitise(client)) }

    /**
     * Turns a sentence into something a file system will accept on any of the three platforms.
     *
     * The names that reach here are prose -- "alex looking at steve", quotes and colons and all --
     * and every one of them ends up as a directory or a file name.
     */
    fun sanitise(name: String): String {
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
