package dev.vibeported.rpc.plugin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.vibeported.rpc.SerializerManifest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * What may cross a wire, beyond the obvious.
 *
 * Three cases that a scheme resting on `@Serializable` gets wrong if nobody checks: a value that may
 * be absent, an enum nobody annotated, and a type from a library that will never carry the
 * annotation at all. Each of these is a signature that exists in the harness this framework was
 * extracted from, so getting them wrong means the framework cannot host the thing it came from.
 */
class WireTypesTest {

    @TempDir
    lateinit var workingDir: File

    private val preamble = """
        import dev.vibeported.rpc.RpcScope
        import dev.vibeported.rpc.node
        import dev.vibeported.rpc.rpcCall
    """.trimIndent()

    /** A serializer for a type nobody owns, written the way a game would write one. */
    private val fileSerializer = """
        import dev.vibeported.rpc.RpcSerializer
        import kotlinx.serialization.KSerializer
        import kotlinx.serialization.descriptors.PrimitiveKind
        import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
        import kotlinx.serialization.encoding.Decoder
        import kotlinx.serialization.encoding.Encoder
        import java.io.File

        @RpcSerializer(File::class)
        object FileSerializer : KSerializer<File> {
            override val descriptor = PrimitiveSerialDescriptor("java.io.File", PrimitiveKind.STRING)
            override fun serialize(encoder: Encoder, value: File) = encoder.encodeString(value.path)
            override fun deserialize(decoder: Decoder) = File(decoder.decodeString())
        }
    """.trimIndent()

    @Test
    fun `a nullable result round trips as null`() {
        val result = RpcCompilation(workingDir).compile(
            "Suite.kt" to """
                import dev.vibeported.rpc.ProcedureTable
                import dev.vibeported.rpc.TableRegistry
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall
                import dev.vibeported.rpc.testkit.RpcCluster
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Job
                import kotlinx.coroutines.cancel
                import kotlinx.coroutines.runBlocking
                import kotlinx.coroutines.withContext

                object Runner {
                    @JvmStatic
                    fun run(table: ProcedureTable): String = runBlocking {
                        val scope = CoroutineScope(Job())
                        val cluster = RpcCluster(scope)
                        val here = cluster.join("here", tables = TableRegistry.of(listOf(table)))
                        cluster.join("there", tables = TableRegistry.of(listOf(table)))
                        cluster.awaitEveryoneSeesEveryone()

                        // The absent one first: a serializer that refuses null fails here.
                        val nothing = withContext(here) { maybe("there", false) }
                        val something = withContext(here) { maybe("there", true) }
                        scope.cancel()
                        "" + nothing + "/" + something
                    }
                }

                suspend fun maybe(who: String, give: Boolean): String? =
                    rpcCall(node(who), give) { yes -> if (yes) "here" else null }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)

        val table = result.classLoader.loadClass("SuiteKt_Rpc").getField("INSTANCE").get(null)
        val answer = try {
            result.classLoader.loadClass("Runner")
                .getMethod("run", result.classLoader.loadClass("dev.vibeported.rpc.ProcedureTable"))
                .invoke(null, table)
        } catch (wrapped: InvocationTargetException) {
            throw wrapped.targetException
        }

        assertEquals("null/here", answer)
    }

    @Test
    fun `an enum needs no annotation`() {
        // kotlinx synthesizes a serializer for one from its entry names, so refusing it would be
        // refusing something that works -- and would push every enum on a wire into a wrapper.
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                enum class Slot { MAIN_HAND, OFF_HAND }

                suspend fun pick(who: String, slot: Slot): Slot =
                    rpcCall(node(who), slot) { chosen -> chosen }
                """.trimIndent()
                )
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `a type with a hand-written serializer is allowed`() {
        // The case a game needs: `java.io.File` stands in for a type from a library that will never
        // be @Serializable. Somebody writes a serializer, marks it, and that is the whole
        // registration -- no build script, and nothing to remember at startup.
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to (preamble + "\n\n" + fileSerializer + "\n\n" + """
                suspend fun where(who: String, file: File): File? =
                    rpcCall(node(who), file) { it }
                """.trimIndent())
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `what a compilation declares is written into its manifest`() {
        // The half the compiler cannot check for itself. This file is what a module downstream
        // reads to inherit the serializer, and what every node reads to assemble its wire format --
        // so a serializer the compiler accepted but never published is a call that fails at run
        // time with nothing having warned about it.
        val compilation = RpcCompilation(workingDir)
        val result = compilation.compile(
            "Sample.kt" to (preamble + "\n\n" + fileSerializer + "\n\n" + """
                suspend fun where(who: String, file: File): File? =
                    rpcCall(node(who), file) { it }
                """.trimIndent())
        )
        assertTrue(result.succeeded, result.messages)

        val manifest = SerializerManifest.parse(
            File(compilation.manifestDir, SerializerManifest.RESOURCE).readText()
        )

        assertEquals(1, manifest.entries.size, manifest.toString())
        assertEquals("java.io.File", manifest.entries.single().type)
        assertEquals("FileSerializer", manifest.entries.single().serializer)
    }

    @Test
    fun `two of them in one compilation are both allowed`() {
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to """
                import dev.vibeported.rpc.RpcSerializer
                import dev.vibeported.rpc.node
                import dev.vibeported.rpc.rpcCall
                import kotlinx.serialization.KSerializer
                import kotlinx.serialization.descriptors.PrimitiveKind
                import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
                import kotlinx.serialization.encoding.Decoder
                import kotlinx.serialization.encoding.Encoder
                import java.io.File
                import java.net.URI

                @RpcSerializer(File::class)
                object FileSerializer : KSerializer<File> {
                    override val descriptor = PrimitiveSerialDescriptor("java.io.File", PrimitiveKind.STRING)
                    override fun serialize(encoder: Encoder, value: File) = encoder.encodeString(value.path)
                    override fun deserialize(decoder: Decoder) = File(decoder.decodeString())
                }

                @RpcSerializer(URI::class)
                object UriSerializer : KSerializer<URI> {
                    override val descriptor = PrimitiveSerialDescriptor("java.net.URI", PrimitiveKind.STRING)
                    override fun serialize(encoder: Encoder, value: URI) = encoder.encodeString(value.toString())
                    override fun deserialize(decoder: Decoder) = URI(decoder.decodeString())
                }

                suspend fun both(who: String, file: File, uri: URI): File =
                    rpcCall(node(who), file, uri) { f, _ -> f }
                """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }

    @Test
    fun `the same type is still refused when nobody wrote a serializer`() {
        // The other half: the annotation is what allows it, so forgetting one is a compile error
        // rather than a serializer that quietly is not there.
        val result = RpcCompilation(workingDir).compile(
            "Sample.kt" to (
                preamble + "\n\n" + """
                import java.io.File

                suspend fun where(who: String, file: File): File =
                    rpcCall(node(who), file) { it }
                """.trimIndent()
                )
        )

        assertFalse(result.succeeded, "a library type nobody serializes must not compile")
        assertTrue("'File'" in result.messages, result.messages)
    }
}
