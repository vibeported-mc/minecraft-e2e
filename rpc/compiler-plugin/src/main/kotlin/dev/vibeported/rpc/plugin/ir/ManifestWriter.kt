package dev.vibeported.rpc.plugin.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import java.io.File

/**
 * Writes the manifest a node reads to find out what exists and whose role owns it.
 *
 * JSON assembled by hand, deliberately: a compiler plugin runs on the compiler's own classpath, and
 * dragging a serialization library onto it to write forty lines of text would be a poor trade. The
 * shape is round-tripped through the real `ProcedureManifest` in the tests, which is what keeps the
 * writer and the reader from drifting apart.
 */
internal class ManifestWriter(
    private val outputDir: String?,
    private val moduleName: String,
    private val messages: MessageCollector,
) {

    fun write(plans: List<FilePlan>) {
        val entries = plans.flatMap { plan ->
            plan.procedures.map { procedure ->
                Entry(procedure.id, plan.tableClass(procedure.role), procedure.role)
            }
        }
        if (entries.isEmpty()) return

        val root = outputDir?.let(::File) ?: run {
            messages.report(
                CompilerMessageSeverity.WARNING,
                "rpc: no manifestDir was configured, so no procedure manifest was written. Apply " +
                    "the dev.vibeported.rpc Gradle plugin, or pass " +
                    "-P plugin:dev.vibeported.rpc:manifestDir=<dir>.",
            )
            return
        }

        val target = File(root, RESOURCE)
        target.parentFile.mkdirs()
        target.writeText(render(entries), Charsets.UTF_8)
    }

    private fun render(entries: List<Entry>): String = buildString {
        appendLine("{")
        appendLine("  \"entries\": [")
        entries.forEachIndexed { index, entry ->
            append("    { \"id\": ").append(quote(entry.id))
            append(", \"table\": ").append(quote(entry.table))
            if (entry.role != null) append(", \"role\": ").append(quote(entry.role))
            append(", \"module\": ").append(quote(moduleName))
            appendLine(if (index == entries.lastIndex) " }" else " },")
        }
        appendLine("  ]")
        appendLine("}")
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

    private class Entry(val id: String, val table: String, val role: String?)

    companion object {
        const val RESOURCE: String = "META-INF/rpc/procedures.json"
    }
}
