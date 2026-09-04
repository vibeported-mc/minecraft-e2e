package dev.vibeported.mc.driver.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What a build tells the driver about the games it wants driven.
 *
 * Settings only. It declares no mod, enables no testing and creates nothing -- everything here is a
 * value the driver could not work out for itself, and everything it *could* work out is read off
 * ModDevGradle rather than asked for a second time. A build that says nothing at all still gets
 * working runs.
 *
 * It lives inside the block it configures:
 *
 * ```kotlin
 * neoForge {
 *     version = "…"
 *     mods { create("example") { sourceSet(sourceSets.main.get()) } }
 *     unitTest { enable(); testedMod = mods.getByName("example") }
 *
 *     mcDriver {
 *         tileWindows = true
 *     }
 * }
 * ```
 */
public abstract class McDriverExtension {

    /** Where a client is told to connect. */
    public abstract val serverAddress: Property<String>

    /** Extra `server.properties` lines, which win over the defaults on the same key. */
    public abstract val serverProperties: ListProperty<String>

    /** Client window size. Minecraft's own default of 854x480 is too small to watch. */
    public abstract val clientWidth: Property<Int>
    public abstract val clientHeight: Property<Int>

    /** Whether clients move their windows so two do not land on top of each other. */
    public abstract val tileWindows: Property<Boolean>

    /** Where screenshots, recordings and game logs go. */
    public abstract val captureDir: DirectoryProperty
}
