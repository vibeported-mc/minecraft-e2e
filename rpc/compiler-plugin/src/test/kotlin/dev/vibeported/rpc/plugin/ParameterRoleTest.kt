package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.ProcedureManifest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * A call that is *about* a kind of node, saying so once.
 *
 * `@RpcRole` on a lambda is the exception written at one call site. This is the rule written at the
 * declaration: a `client { }` whose bodies only a game client can run declares that on its body
 * parameter, and thirty call sites say nothing at all. Without it, every one of them would have to
 * repeat an annotation that is never anything else -- and could get it wrong.
 *
 * The role lives on `@RpcLift` rather than in a `@RpcRole` of its own because `@RpcRole` targets an
 * expression, and Kotlin forces source retention there: the annotation would be gone by the time
 * another module compiled the call.
 */
class ParameterRoleTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `a body takes the role its parameter declares`() {
        val compilation = RpcCompilation(workingDir)
        val result = compilation.compile(
            "Sample.kt" to """
                import dev.vibeported.rpc.RpcBody0
                import dev.vibeported.rpc.RpcLift
                import dev.vibeported.rpc.RpcRole
                import dev.vibeported.rpc.RpcScope
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCallIn

                suspend fun <R> onClient(name: String, @RpcLift("client") body: RpcBody0<RpcScope, R>): R =
                    rpcCallIn(node(name), body)

                suspend fun <R> anywhere(name: String, @RpcLift body: RpcBody0<RpcScope, R>): R =
                    rpcCallIn(node(name), body)

                suspend fun caller() {
                    onClient("alex") { 1 }
                    anywhere("alex") { 2 }

                    // The lambda still wins, for the one body that is an exception.
                    onClient("alex") @RpcRole("server") { 3 }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val manifest = ProcedureManifest.parse(
            File(compilation.manifestDir, ProcedureManifest.RESOURCE).readText()
        )
        val roles = manifest.entries.associate { it.id.substringAfterLast('/') to it.role }

        assertEquals(3, manifest.entries.size, manifest.toString())
        assertEquals(setOf("client", null, "server"), roles.values.toSet(), roles.toString())

        // And the split lands in classes, which is the part a dist-cleaned node depends on.
        val tables = manifest.entries.map { it.table }.toSet()
        assertTrue(tables.any { it.endsWith("SampleKt_Rpc_client") }, tables.toString())
        assertTrue(tables.any { it.endsWith("SampleKt_Rpc_server") }, tables.toString())
        assertTrue(tables.any { it.endsWith("SampleKt_Rpc") }, tables.toString())
    }
}
