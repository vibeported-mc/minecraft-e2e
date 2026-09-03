package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.ProcedureManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * That one compilation cannot see another's roles.
 *
 * The role a body carries has to travel from the frontend to the backend out of band, because the
 * annotation is SOURCE-retained and gone by the time IR runs. That side channel was a singleton at
 * first, which is wrong in a way no single compilation reveals: entries outlive the module that
 * wrote them, and a Gradle daemon compiles many modules in one process.
 *
 * These tests are built to fail against that mistake rather than to pass against the fix.
 */
class CompilationIsolationTest {

    @TempDir
    lateinit var workingDir: File

    /**
     * The two files differ only in their first line, and those lines are the same length.
     *
     * That is the point: every call afterwards sits at an identical offset, so a shared index keyed
     * by position would hand the second compilation the first one's answer. The padding is computed
     * rather than typed so it cannot drift out of alignment unnoticed.
     */
    private val annotation = """@file:RpcRole("alpha")"""
    private val padding = "//" + " ".repeat(annotation.length - 2)

    private fun source(firstLine: String) = """
        $firstLine
        import dev.vibeported.rpc.RpcRole
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall

        suspend fun only() {
            rpcCall(node("a")) { 1 }
        }
    """.trimIndent()

    @Test
    fun `a later compilation does not inherit an earlier one's role`() {
        check(annotation.length == padding.length) { "the two first lines must be the same length" }

        val first = compile("first", source(annotation))
        val second = compile("second", source(padding))

        // The one that declared a role keeps it.
        assertEquals("alpha", first.entries.single().role, first.toString())

        // The one that declared none must not be given the other's. Confirmed to fail against a
        // shared index -- it reported role=alpha and put the body in SampleKt_Rpc_alpha, a table a
        // dist-cleaned node must never load.
        assertEquals(null, second.entries.single().role, second.toString())
    }

    /**
     * A stress rather than a proof.
     *
     * Verified against the shared-index mistake and it still passed, because two compilations
     * that each declare a role write different values and mostly read their own back. The
     * sequential test above is the one with teeth; this one guards the races that timing alone
     * decides.
     */
    @Test
    fun `compiling two modules at once keeps their roles apart`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            val alpha = pool.submit<ProcedureManifest> { compile("alpha", source(annotation)) }
            val beta = pool.submit<ProcedureManifest> {
                compile("beta", source("""@file:RpcRole("beta")"""))
            }

            assertEquals("alpha", alpha.get(2, TimeUnit.MINUTES).entries.single().role)
            assertEquals("beta", beta.get(2, TimeUnit.MINUTES).entries.single().role)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun compile(name: String, code: String): ProcedureManifest {
        val compilation = RpcCompilation(File(workingDir, name).apply { mkdirs() })
        val result = compilation.compile("Sample.kt" to code)
        assertTrue(result.succeeded, result.messages)

        val manifest = File(compilation.manifestDir, ProcedureManifest.RESOURCE)
        assertTrue(manifest.isFile, "no manifest was written for $name")
        return ProcedureManifest.parse(manifest.readText())
    }
}
