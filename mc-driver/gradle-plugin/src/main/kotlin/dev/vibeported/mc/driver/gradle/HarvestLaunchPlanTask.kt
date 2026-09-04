package dev.vibeported.mc.driver.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Turns the game run tasks into a launch plan the driver can execute.
 *
 * This is the whole reason the driver can start a game at all. A Minecraft command line is not
 * something to reconstruct by hand -- the classpath alone runs to hundreds of entries chosen by a
 * dozen artifact transforms -- so it is read off the run task ModDevGradle already built.
 *
 * That reading is legitimate rather than a raid on internals: MDG's run task is a plain [JavaExec]
 * whose `exec()` only adds the classpath, working directory and environment from public properties
 * before delegating. The one awkward part is that the classpath is *assembled inside* `exec()`, so
 * it has to be taken from the provider rather than from `task.classpath`, which is empty until then.
 */
public abstract class HarvestLaunchPlanTask : DefaultTask() {

    @get:Input public abstract val serverRunTask: Property<String>
    @get:Input public abstract val clientRunTask: Property<String>
    @get:Input public abstract val serverAddress: Property<String>
    @get:Input public abstract val clientWidth: Property<Int>
    @get:Input public abstract val clientHeight: Property<Int>
    @get:Input public abstract val tileWindows: Property<Boolean>
    @get:org.gradle.api.tasks.Internal public abstract val captureDir: DirectoryProperty
    @get:OutputFile public abstract val planFile: RegularFileProperty

    init {
        // It reads other tasks, which is exactly what the configuration cache forbids.
        notCompatibleWithConfigurationCache("Reads the configuration of the ModDevGradle run tasks")

        // The plan is derived from the run tasks, and Gradle cannot see those as inputs. Left to its
        // own judgement this task goes up to date after the runs have changed underneath it, and
        // hands the driver a classpath describing a build that no longer exists.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    public fun harvest() {
        val out = planFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                append("{")
                append("\"server\":").append(spec("server", serverRunTask.get())).append(",")
                append("\"client\":").append(spec("client", clientRunTask.get())).append(",")
                append("\"serverAddress\":").append(quote(serverAddress.get())).append(",")
                append("\"clientWidth\":").append(clientWidth.get()).append(",")
                append("\"clientHeight\":").append(clientHeight.get()).append(",")
                append("\"tileWindows\":").append(tileWindows.get())
                append("}")
            }
        )
        logger.lifecycle("mcdriver: launch plan written to ${out.absolutePath}")
    }

    private fun spec(name: String, taskName: String): String {
        val task = project.tasks.findByName(taskName) as? JavaExec
            ?: error(
                "No JavaExec task named '$taskName'. Declare a ModDevGradle run of that name, or " +
                    "point the mcDriver extension at the right one."
            )

        // RunGameTask only assembles its classpath inside exec(), from this provider.
        val classpath = runCatching {
            val provider = task.javaClass.getMethod("getClasspathProvider").invoke(task)
            (provider as org.gradle.api.file.FileCollection).files
        }.getOrElse { task.classpath.files }

        val workingDir = runCatching {
            val dir = task.javaClass.getMethod("getGameDirectory").invoke(task)
            (dir as DirectoryProperty).get().asFile
        }.getOrElse { task.workingDir }

        val environment = runCatching {
            @Suppress("UNCHECKED_CAST")
            val env = task.javaClass.getMethod("getEnvironmentProperty").invoke(task)
            (env as org.gradle.api.provider.MapProperty<String, String>).get()
        }.getOrElse { emptyMap() }

        return buildString {
            append("{")
            append("\"name\":").append(quote(name)).append(",")
            append("\"javaExecutable\":").append(quote(task.executable ?: "java")).append(",")
            append("\"jvmArgs\":[").append(task.allJvmArgs.joinToString(",") { quote(it) }).append("],")
            append("\"mainClass\":").append(quote(task.mainClass.get())).append(",")
            append("\"programArgs\":[").append(task.args.orEmpty().joinToString(",") { quote(it) }).append("],")
            append("\"classpath\":[").append(classpath.joinToString(",") { quote(it.absolutePath) }).append("],")
            append("\"workingDir\":").append(quote(workingDir.absolutePath)).append(",")
            append("\"environment\":{")
            append(environment.entries.joinToString(",") { quote(it.key) + ":" + quote(it.value) })
            append("}")
            append("}")
        }
    }

    /**
     * JSON by hand, for the same reason the compiler plugin writes its manifest by hand: a Gradle
     * plugin's runtime classpath is exported onto the consuming build's plugin classpath, and
     * dragging a serialization library through that to write thirty lines of text is a poor trade.
     */
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (it < ' ') append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }
}
