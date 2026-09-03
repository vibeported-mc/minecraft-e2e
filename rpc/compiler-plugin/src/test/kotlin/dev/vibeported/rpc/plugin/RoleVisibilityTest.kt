package dev.vibeported.rpc.plugin

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import dev.vibeported.rpc.plugin.fir.RoleProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Settles the one question the whole annotation design rests on.
 *
 * `@RpcRole` has to be SOURCE-retained, because Kotlin permits no other retention on an expression
 * target. So it is certainly gone by IR, where the lifting happens. If it does not reach FIR either,
 * the annotation form is impossible and the role has to be an ordinary argument.
 */
class RoleVisibilityTest {

    @TempDir
    lateinit var workingDir: File

    @Test
    fun `where does a role annotation actually survive to`() {
        RoleProbe.Seen.reset()

        val source = SourceFile.kotlin(
            "Sample.kt",
            """
            @file:RpcRole("fileLevel")

            import dev.vibeported.rpc.RpcRole
            import dev.vibeported.rpc.RpcScope
            import dev.vibeported.rpc.node
            import dev.vibeported.rpc.rpcCall

            suspend fun plain() {
                rpcCall(node("a")) { 1 }
            }

            suspend fun annotated() {
                rpcCall(node("a")) @RpcRole("onLambda") { 2 }
            }
            """.trimIndent(),
        )

        val result = KotlinCompilation().apply {
            this.workingDir = this@RoleVisibilityTest.workingDir
            sources = listOf(source)
            compilerPluginRegistrars = listOf(RpcCompilerPluginRegistrar())
            commandLineProcessors = listOf(RpcCommandLineProcessor())
            inheritClassPath = true
            messageOutputStream = System.out
        }.compile()

        assertTrue(
            result.exitCode == KotlinCompilation.ExitCode.OK,
            "the snippet itself must compile: " + result.messages,
        )

        val seen = RoleProbe.Seen.calls
        assertEquals(2, seen.size, "expected both rpcCall sites, saw $seen")

        // The unannotated call inherits the file's role, and nothing sits on its lambda.
        assertEquals("rpcCall expr=- fun=- file=fileLevel", seen[0])

        // The annotated one carries its own, which is the fact the whole design rests on: an
        // expression annotation is SOURCE-retained by necessity, so if it did not reach the frontend
        // it would be unreadable anywhere and the role would have to be an ordinary argument.
        assertEquals("rpcCall expr=onLambda fun=onLambda file=fileLevel", seen[1])
    }
}
