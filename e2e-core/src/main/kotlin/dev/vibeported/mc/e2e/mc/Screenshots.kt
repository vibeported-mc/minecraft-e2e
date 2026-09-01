package dev.vibeported.mc.e2e.mc

import kotlinx.coroutines.CompletableDeferred
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Grabs what a client is looking at, as a file on disk.
 *
 * The capture itself is a read back from the GPU, which finishes some frames after it is asked for,
 * so the caller is handed a deferred rather than a file that may not be written yet. Awaiting it is
 * what lets a test say "screenshot, then do the next thing" and mean it.
 */
internal object Screenshots {

    /** Where a run files its artefacts, given to every node on the command line. */
    private val REPORT_DIR: String? = System.getProperty("e2e.report.dir")

    /**
     * JPEG rather than PNG.
     *
     * A run takes a shot at every interesting moment on every client, and a lossless 1280x720 frame
     * is over a megabyte of mostly-sky. At this quality the difference is invisible and the
     * directory is a tenth of the size.
     */
    private const val QUALITY = 0.9f

    private val counters = mutableMapOf<String, Int>()

    fun directory(client: String, test: String): File? {
        val root = REPORT_DIR ?: return null
        return File(File(File(root, "screenshots"), sanitise(client)), sanitise(test))
    }

    /**
     * Captures the current frame and writes it, returning the file once it is really there.
     *
     * Numbered per client and test, so a directory reads in the order the test ran rather than in
     * whatever order the names happen to sort.
     */
    suspend fun capture(minecraft: Minecraft, client: String, test: String, name: String): File {
        val directory = directory(client, test)
            ?: error("No e2e.report.dir was set, so there is nowhere to put a screenshot")
        directory.mkdirs()

        val index = counters.merge("$client/$test", 1, Int::plus)!!
        val file = File(directory, "%02d-%s.jpg".format(Locale.ROOT, index, sanitise(name)))

        val captured = CompletableDeferred<BufferedImage>()
        Screenshot.takeScreenshot(minecraft.gameRenderer.mainRenderTarget()) { image ->
            // Copied out here, on the thread the callback runs on: the native image is freed the
            // moment this returns, and the pixels have to outlive it.
            val copy = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
            copy.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
            captured.complete(copy)
        }

        writeJpeg(captured.await(), file)
        return file
    }

    private fun writeJpeg(image: BufferedImage, file: File) {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        try {
            ImageIO.createImageOutputStream(file).use { output ->
                writer.output = output
                val parameters = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = QUALITY
                }
                writer.write(null, IIOImage(image, null, null), parameters)
            }
        } finally {
            writer.dispose()
        }
    }

    /**
     * Turns a sentence into something a file system will accept on any of the three platforms.
     *
     * Test and screenshot names are prose -- "alex looking at steve", quotes and colons and all --
     * and every one of those ends up as a directory or a file name.
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
