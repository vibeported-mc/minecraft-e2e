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

    fun write(plans: List<FilePlan>, serializers: Map<String, String> = emptyMap()) {
        val entries = plans.flatMap { plan ->
            plan.procedures.map { procedure ->
                Entry(procedure.id, plan.tableClass(procedure.role), procedure.role)
            }
        }
        if (entries.isEmpty() && serializers.isEmpty()) return

        val root = outputDir?.let(::File) ?: run {
            messages.report(
                CompilerMessageSeverity.WARNING,
                "rpc: no manifestDir was configured, so no manifest was written. Apply " +
                    "the dev.vibeported.rpc Gradle plugin, or pass " +
                    "-P plugin:dev.vibeported.rpc:manifestDir=<dir>.",
            )
            return
        }

        if (entries.isNotEmpty()) write(root, PROCEDURES, renderProcedures(entries))
        if (serializers.isNotEmpty()) write(root, SERIALIZERS, renderSerializers(serializers))
    }

    private fun write(root: File, resource: String, text: String) {
        val target = File(root, resource)
        target.parentFile.mkdirs()
        target.writeText(text, Charsets.UTF_8)
    }

    private fun renderProcedures(entries: List<Entry>): String = render(entries) { entry ->
        append("\"id\": ").append(quote(entry.id))
        append(", \"table\": ").append(quote(entry.table))
        if (entry.role != null) append(", \"role\": ").append(quote(entry.role))
    }

    /**
     * What this module wrote a serializer for.
     *
     * Read back by two different things, which is the point of putting it on the classpath rather
     * than passing it around: this same plugin compiling a module downstream, so a dependency's
     * serializers need no configuring; and every node at startup, assembling the wire format. They
     * cannot disagree about what is sendable, because they read the same file.
     */
    private fun renderSerializers(serializers: Map<String, String>): String =
        render(serializers.entries.toList()) { (type, serializer) ->
            append("\"type\": ").append(quote(type))
            append(", \"serializer\": ").append(quote(serializer))
        }

    private fun <T> render(entries: List<T>, fields: StringBuilder.(T) -> Unit): String = buildString {
        appendLine("{")
        appendLine("  \"entries\": [")
        entries.forEachIndexed { index, entry ->
            append("    { ")
            fields(entry)
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
        const val PROCEDURES: String = "META-INF/rpc/procedures.json"
        const val SERIALIZERS: String = "META-INF/rpc/serializers.json"
    }
}
