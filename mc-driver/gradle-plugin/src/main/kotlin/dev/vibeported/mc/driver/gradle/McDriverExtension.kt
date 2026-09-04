package dev.vibeported.mc.driver.gradle

import net.neoforged.moddevgradle.dsl.ModModel
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * What a build tells the driver about the games it wants driven.
 *
 * Everything has a default except the two things nobody else can know: which source set the runs are
 * built from, and which class to run once the loader is up.
 *
 * ```kotlin
 * mcDriver {
 *     sourceSet = sourceSets.main.get()
 *     mainClass = "com.example.Smoke"
 * }
 * ```
 */
public abstract class McDriverExtension {

    /**
     * The mod this build produces, created for you.
     *
     * Saying it here rather than in `neoForge { mods { } }` is the difference between naming the mod
     * once and naming it three times -- the id, the source set it is built from, and the source set
     * the games are launched from were all the same two facts written out twice.
     *
     * It has to match the `modId` in this project's `neoforge.mods.toml`, which is the one place a
     * build still says it: that file carries things a plugin has no business generating, mixin
     * configurations among them.
     *
     * A build that declares its own mods can leave this unset and name one with [testedMod].
     */
    public abstract val modId: Property<String>

    /**
     * The class whose `main(String[])` runs once the loader is up.
     *
     * Optional. A build that only writes tests needs no main at all, and leaving this unset simply
     * means no `driverMain` run and no `runDriver` task.
     */
    public abstract val mainClass: Property<String>

    /**
     * The mod the tests belong to.
     *
     * Defaults to the project's single declared mod, which is the usual case. It matters more than
     * it looks: the tested mod is what puts the test output on `-Dfml.modFolders`, and test classes
     * that are not in a mod load outside FancyModLoader's class loader -- where every Minecraft type
     * they name is a second copy of itself.
     */
    public abstract val testedMod: Property<ModModel>

    /**
     * Which dist the driver process itself prepares.
     *
     * `DEDICATED_SERVER` by default, which is the smaller world and dist-cleaned exactly as a real
     * server is -- so a driver that reaches for a client class finds out here rather than later.
     */
    public abstract val dist: Property<String>

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

    /** The Java version the runs use. Minecraft 26.2 needs 25. */
    public abstract val javaVersion: Property<Int>
}
