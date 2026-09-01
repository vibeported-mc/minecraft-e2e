package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

/**
 * Writes `META-INF/e2e/index.json`, which is how the orchestrator learns what tests exist and which
 * generated table owns each block, without loading a single test body.
 *
 * JSON is assembled by hand rather than with a serialization library, so the plugin drags no
 * dependencies onto the compiler classpath. The shape is the one `E2eIndex` in e2e-api parses, and a
 * plugin test round-trips the output through it to keep the two honest.
 *
 * Each compiled file also gets its own part file. Incremental compilation only hands the plugin the
 * sources that changed, so rebuilding the whole index from the parts on disk is what keeps entries
 * for untouched files from disappearing out of it.
 */
internal class E2eIndexWriter(
    private val indexDir: String?,
    private val messages: MessageCollector,
) {
    fun write(plans: List<FilePlan>) {
        val root = indexDir?.let(::File) ?: run {
            messages.report(
                CompilerMessageSeverity.WARNING,
                "e2e: no indexDir was configured, so no test index was written. " +
                    "Apply the dev.vibeported.mc.e2e Gradle plugin, or pass -P plugin:dev.vibeported.mc.e2e:indexDir=<dir>.",
            )
            return
        }

        val parts = File(root, "META-INF/e2e/parts")
        parts.mkdirs()
        plans.forEach { plan ->
            File(parts, plan.facadeClass + ".json").writeText(render(plan))
        }

        val all = parts.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.map { it.readText() }
            .orEmpty()

        File(root, "META-INF/e2e").mkdirs()
        File(root, "META-INF/e2e/index.json")
            .writeText(all.joinToString(prefix = "{\"files\":[", separator = ",", postfix = "]}"))
    }

    private fun render(plan: FilePlan): String = buildString {
        append("{")
        append("\"sourceFile\":").append(quote(plan.file.fileEntry.name)).append(",")
        append("\"facadeClass\":").append(quote(plan.facadeClass)).append(",")
        append("\"tableClass\":").append(quote(plan.tableClass)).append(",")

        append("\"suites\":[")
        plan.suites.forEachIndexed { index, suite ->
            if (index > 0) append(",")
            append("{")
            append("\"id\":").append(quote(suite.id)).append(",")
            append("\"name\":").append(quote(suite.name)).append(",")
            append("\"accessor\":").append(quote(suite.accessor)).append(",")
            append("\"tests\":[")
            suite.tests.forEachIndexed { testIndex, test ->
                if (testIndex > 0) append(",")
                append("{")
                append("\"id\":").append(quote(test.id)).append(",")
                append("\"name\":").append(quote(test.name)).append(",")
                append("\"steps\":[")
                append(test.steps.joinToString(",") { quote(it.id) })
                append("]")
                append("}")
            }
            append("]")
            append("}")
        }
        append("],")

        append("\"blocks\":[")
        plan.blocks().forEachIndexed { index, block ->
            if (index > 0) append(",")
            append("{")
            append("\"id\":").append(quote(block.id)).append(",")
            append("\"role\":").append(quote(block.role.name)).append(",")
            append("\"clientIndex\":").append(block.clientIndex).append(",")
            block.parent?.let { append("\"parent\":").append(quote(it.id)).append(",") }
            append("\"test\":").append(quote(block.testId))
            append("}")
        }
        append("]")
        append("}")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
