package dev.vibeported.mc.e2e.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Turns the run tasks into a launch plan the orchestrator can execute.
 *
 * ModDevGradle's run task is a plain [JavaExec] whose `exec()` only adds the classpath, working
 * directory and environment from public properties before delegating, so everything needed to
 * reproduce the launch can be read off the task without reaching into anything private.
 */
abstract class HarvestLaunchPlanTask : DefaultTask() {

    @get:Input abstract val serverRunTask: Property<String>
    @get:Input abstract val clientRunTasks: ListProperty<String>
    @get:InputFiles abstract val indexFiles: ConfigurableFileCollection
    @get:Internal abstract val reportDir: DirectoryProperty
    @get:Input abstract val serverAddress: Property<String>
    @get:Input abstract val startupTimeoutSeconds: Property<Long>
    @get:Input abstract val testTimeoutSeconds: Property<Long>
    @get:OutputFile abstract val planFile: RegularFileProperty

    init {
        // It reads other tasks, which is exactly what the configuration cache forbids.
        notCompatibleWithConfigurationCache("Reads the configuration of the ModDevGradle run tasks")

        // The plan is derived from the run tasks, and Gradle cannot see those as inputs. Left to
        // its own judgement this task goes up to date after the runs have changed underneath it,
        // and hands the orchestrator a classpath describing a build that no longer exists.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun harvest() {
        val server = spec("server", serverRunTask.get())
        val clients = clientRunTasks.get().mapIndexed { index, name -> spec("client$index", name) }

        val out = planFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                append("{")
                append("\"server\":").append(server).append(",")
                append("\"clients\":[").append(clients.joinToString(",")).append("],")
                append("\"indexFiles\":[")
                append(indexFiles.files.filter { it.isFile }.joinToString(",") { quote(it.absolutePath) })
                append("],")
                append("\"reportDir\":").append(quote(reportDir.get().asFile.absolutePath)).append(",")
                append("\"serverAddress\":").append(quote(serverAddress.get())).append(",")
                append("\"startupTimeoutSeconds\":").append(startupTimeoutSeconds.get()).append(",")
                append("\"testTimeoutSeconds\":").append(testTimeoutSeconds.get())
                append("}")
            }
        )
        logger.lifecycle("e2e: launch plan written to ${out.absolutePath}")
    }

    private fun spec(name: String, taskName: String): String {
        val task = project.tasks.findByName(taskName) as? JavaExec
            ?: error(
                "No JavaExec task named '$taskName'. Declare a ModDevGradle run of that name, " +
                    "or point the e2e extension at the right one."
            )

        // RunGameTask only assembles its classpath inside exec(), from this provider.
        val classpath = runCatching {
            @Suppress("UNCHECKED_CAST")
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
