package dev.vibeported.mc.driver.gradle

import net.neoforged.moddevgradle.dsl.ModModel
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet

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
     * The source set whose runtime classpath the games are launched from.
     *
     * It has to be one source set for all three runs: the driver mod is a dependency of whatever
     * this build writes, and FancyModLoader only finds it as a mod if its jar is on that classpath.
     */
    public abstract val sourceSet: Property<SourceSet>

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
