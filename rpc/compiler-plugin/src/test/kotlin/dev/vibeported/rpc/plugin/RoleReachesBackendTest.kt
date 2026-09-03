package dev.vibeported.rpc.plugin

import dev.vibeported.rpc.plugin.ir.RpcIrGenerationExtension
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Whether the backend can recover a role the frontend saw.
 *
 * Lifting happens in IR and the annotation is gone by then, so the two halves have to agree on a
 * key. The call's own position is the candidate: an annotated lambda begins at the annotation in
 * FIR and at the brace in IR, so the lambda's position is exactly the thing that cannot be trusted.
 */
class RoleReachesBackendTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `the backend finds every call, with the role the frontend recorded`() {
        RoleIndex.reset()
        RpcIrGenerationExtension.Seen.reset()

        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to """
                @file:RpcRole("fromFile")

                import dev.vibeported.rpc.RpcRole
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall

                suspend fun inherits() {
                    rpcCall(node("a")) { 1 }
                }

                suspend fun overrides() {
                    rpcCall(node("a")) @RpcRole("fromLambda") { 2 }
                }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val seen = RpcIrGenerationExtension.Seen.calls
        println("=== what the backend recovered ===")
        seen.forEach { println("    $it") }

        assertEquals(2, seen.size, "the backend should find both calls, saw $seen")
        assertTrue(seen.all { "seen=true" in it }, "every call must be one the frontend recorded: $seen")
        assertTrue(seen.any { "role=fromFile" in it }, "the file default should reach IR: $seen")
        assertTrue(seen.any { "role=fromLambda" in it }, "the override should reach IR: $seen")
    }
}
