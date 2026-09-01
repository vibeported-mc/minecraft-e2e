package dev.vibeported.mc.e2e.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

/**
 * Writes `META-INF/e2e/procedures.json`, which is how a node finds the table that owns a block it has
 * been asked to run, and how a run learns which clients to start.
 *
 * JSON is assembled by hand rather than with a serialization library, so the plugin drags no
 * dependencies onto the compiler classpath. The shape is the one `ProcedureIndex` parses, and a plugin
 * test round-trips the output through it to keep the two honest.
 *
 * Each compiled file also gets its own part file. Incremental compilation only hands the plugin the
 * sources that changed, so rebuilding the whole index from the parts on disk is what keeps entries
 * for untouched files from disappearing out of it.
 */
internal class ProcedureIndexWriter(
    private val indexDir: String?,
    private val messages: MessageCollector,
) {
    fun write(plans: List<FilePlan>) {
        val root = indexDir?.let(::File) ?: run {
            messages.report(
                CompilerMessageSeverity.WARNING,
                "e2e: no indexDir was configured, so no block index was written. " +
                    "Apply the dev.vibeported.mc.e2e Gradle plugin, or pass " +
                    "-P plugin:dev.vibeported.mc.e2e:indexDir=<dir>.",
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
        File(root, "META-INF/e2e/procedures.json")
            .writeText(all.joinToString(prefix = "{\"files\":[", separator = ",", postfix = "]}"))
    }

    private fun render(plan: FilePlan): String = buildString {
        append("{")
        append("\"sourceFile\":").append(quote(plan.file.fileEntry.name)).append(",")
        append("\"facadeClass\":").append(quote(plan.facadeClass)).append(",")

        append("\"clients\":[")
        append(plan.clients().joinToString(",") { quote(it) })
        append("],")

        append("\"blocks\":[")
        plan.blocks().forEachIndexed { index, block ->
            if (index > 0) append(",")
            append("{")
            append("\"id\":").append(quote(block.id)).append(",")
            append("\"role\":").append(quote(block.role.name)).append(",")
            append("\"client\":").append(quote(block.client)).append(",")
            append("\"table\":").append(quote(plan.tableClass(block.role)))
            block.parent?.let { append(",\"parent\":").append(quote(it.id)) }
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
