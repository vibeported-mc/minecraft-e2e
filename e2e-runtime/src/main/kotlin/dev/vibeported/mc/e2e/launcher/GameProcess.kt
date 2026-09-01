package dev.vibeported.mc.e2e.launcher

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A launched game process, with its console piped somewhere useful.
 *
 * Minecraft prints a great deal, and when a run goes wrong the reason is almost always in there, so
 * the output is prefixed with the node it came from and echoed rather than swallowed.
 */
public class GameProcess(
    public val name: String,
    private val process: Process,
    private val logFile: File,
) {
    public val isAlive: Boolean get() = process.isAlive

    public fun stop() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }

    public fun exitCode(): Int? = if (process.isAlive) null else process.exitValue()

    public companion object {

        public fun start(
            spec: LaunchSpec,
            extraJvmArgs: List<String>,
            logDir: File,
            echo: (String) -> Unit,
        ): GameProcess {
            val workingDir = File(spec.workingDir).apply { mkdirs() }
            logDir.mkdirs()
            val logFile = File(logDir, "${spec.name}.log")

            // A Minecraft classpath runs to hundreds of entries, well past the Windows command line
            // limit, so it goes into an argument file. Only it, though: ModDevGradle passes its own
            // vm args as an @file too, and the JVM will not expand one argument file from inside
            // another, so that reference has to stay on the command line proper.
            val classpathFile = File(logDir, "${spec.name}.classpath.txt").apply {
                writeText(
                    if (spec.classpath.isEmpty()) {
                        ""
                    } else {
                        "-cp " + quoteForArgFile(spec.classpath.joinToString(File.pathSeparator))
                    }
                )
            }

            val command = buildList {
                add(spec.javaExecutable)
                addAll(spec.jvmArgs.map(::normalizeArgFileReference))
                addAll(extraJvmArgs)
                if (spec.classpath.isNotEmpty()) add("@" + classpathFile.absolutePath)
                add(spec.mainClass)
                addAll(spec.programArgs.map(::normalizeArgFileReference))
            }

            File(logDir, "${spec.name}.command.txt").writeText(command.joinToString("\n"))

            val builder = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
            builder.environment().putAll(spec.environment)

            val process = builder.start()

            thread(isDaemon = true, name = "e2e-${spec.name}-log") {
                logFile.bufferedWriter().use { writer ->
                    process.inputStream.bufferedReader().forEachLine { line ->
                        writer.appendLine(line)
                        writer.flush()
                        echo("[${spec.name}] $line")
                    }
                }
            }

            return GameProcess(spec.name, process, logFile)
        }

        /**
         * Java argument files treat backslashes as escapes, which matters on Windows where every
         * path is full of them.
         */
        private fun quoteForArgFile(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        /**
         * Undoes the escaping ModDevGradle applies to its `@argfile` references.
         *
         * Gradle writes long commands into an argument file of its own, and backslashes have to be
         * doubled to survive that. We hand the arguments straight to the process instead, where the
         * doubled path is simply wrong: the JVM cannot open it, quietly gives up on expanding the
         * file, and the leftover token ends up being read as the main class.
         */
        private fun normalizeArgFileReference(argument: String): String =
            if (argument.startsWith("@")) "@" + argument.substring(1).replace("\\\\", "\\") else argument
    }
}
