package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.ProcedureManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * What the plugin records for a node to read at startup.
 *
 * Parsed back through the very model the runtime uses, because the plugin assembles that JSON by
 * hand -- reading it with `ProcedureManifest` is what keeps the writer and the reader from drifting.
 *
 * This also supersedes the earlier probe. A role appearing here proves the whole chain: the
 * frontend saw a SOURCE-retained annotation, recorded it, and the backend recovered it against a key
 * both halves agree on.
 */
class ManifestTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `bodies are grouped into a table per role`() {
        val compilation = RpcCompilation(workingDir)
        val result = compilation.compile(
            "Sample.kt" to """
                @file:RpcRole("worker")

                import dev.vibeported.rpc.RpcRole
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun ordinary() {
                    rpcCall(node("a")) { 1 }
                    rpcCall(node("a")) { 2 }
                }

                suspend fun elsewhere() {
                    rpcCall(node("a")) @RpcRole("driver") { 3 }
                }

                suspend fun everywhere() {
                    rpcCall(node("a")) @RpcRole("") { 4 }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val manifest = ProcedureManifest.parse(
            File(compilation.manifestDir, ProcedureManifest.RESOURCE).readText()
        )

        // Four bodies, and every id distinct -- an id is what a node looks a body up by, so two
        // sharing one would mean a call reaching the wrong body.
        assertEquals(4, manifest.entries.size, manifest.toString())
        assertEquals(4, manifest.entries.map { it.id }.distinct().size, "ids must be unique")

        // Ordinals count call sites within their enclosing declaration, so they survive a body being
        // added to a different function.
        assertTrue(manifest.entries.any { it.id == "SampleKt.ordinary/0" }, manifest.toString())
        assertTrue(manifest.entries.any { it.id == "SampleKt.ordinary/1" }, manifest.toString())

        val byRole = manifest.entries.groupBy { it.role }
        assertEquals(setOf("worker", "driver", ""), byRole.keys, "roles: ${byRole.keys}")

        // Two bodies sharing a role share a class: the class is the unit a node can or cannot load,
        // so one per role per file is the whole point.
        assertEquals(
            listOf("SampleKt_Rpc_worker"),
            byRole.getValue("worker").map { it.table }.distinct(),
        )
        assertEquals(
            listOf("SampleKt_Rpc_driver"),
            byRole.getValue("driver").map { it.table }.distinct(),
        )
    }

    @Test
    fun `a file with no roles puts everything in the table every node loads`() {
        val compilation = RpcCompilation(workingDir)
        val result = compilation.compile(
            "Plain.kt" to """
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun anywhere() {
                    rpcCall(node("a")) { 1 }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val manifest = ProcedureManifest.parse(
            File(compilation.manifestDir, ProcedureManifest.RESOURCE).readText()
        )

        val entry = manifest.entries.single()
        assertEquals("PlainKt_Rpc", entry.table)
        // Null rather than empty: no role written is genuinely "every node", not a missing answer.
        assertEquals(null, entry.role)
    }
}
