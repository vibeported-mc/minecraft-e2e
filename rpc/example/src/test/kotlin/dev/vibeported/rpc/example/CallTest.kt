package dev.vibeported.rpc.example

import dev.vibeported.rpc.NodeInfo
import dev.vibeported.rpc.ProcedureManifest
import dev.vibeported.rpc.NodeId
import dev.vibeported.rpc.Role
import dev.vibeported.rpc.RpcBody0
import dev.vibeported.rpc.RpcLift
import dev.vibeported.rpc.RpcScope
import dev.vibeported.rpc.Services
import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.forEachRpcCall
import dev.vibeported.rpc.node
import dev.vibeported.rpc.rpcCall
import dev.vibeported.rpc.rpcCallIn
import dev.vibeported.rpc.testkit.RpcCluster
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Serializable
class Greeting(val subject: String, val times: Int)

/**
 * What a node offers a body that lands on it, when the layer wants to offer something of its own.
 *
 * This is the shape the whole `@RpcLift` design exists for: `greeter` below is an ordinary function
 * that the compiler plugin has never heard of, and a body written at it sees this scope rather than
 * the bare one.
 */
class GreeterScope(
    override val node: NodeInfo,
    override val services: Services,
    val salutation: String,
) : RpcScope

suspend fun <R> greeter(name: String, @RpcLift body: RpcBody0<GreeterScope, R>): R =
    rpcCallIn(node(name), body)

/**
 * The framework as a consuming build gets it.
 *
 * Every other test in this repository hands the compiler plugin a source file and inspects what
 * came out. This one is compiled by the build itself, so what it proves is the part none of those
 * can: that applying `dev.vibeported.rpc` is enough, that the manifest reaches the classpath, and
 * that a node loads its tables from that manifest rather than from a list someone passed in.
 */
class CallTest {

    @Test
    fun `a node finds its procedures through the manifest on its classpath`() {
        val manifest = ProcedureManifest.load(javaClass.classLoader)

        // Every body written in this file, found by name alone -- nothing here registered anything.
        assertTrue(manifest.entries.isNotEmpty(), "the compiler plugin wrote no manifest")
        assertTrue(
            manifest.entries.any { it.table.startsWith("dev.vibeported.rpc.example.CallTestKt_Rpc") },
            manifest.entries.joinToString { it.table },
        )
    }

    @Test
    fun `a call round trips between two nodes that loaded their own tables`() = runTest {
        val cluster = RpcCluster(backgroundScope)
        val loader = javaClass.classLoader
        val here = cluster.join("here", tables = TableRegistry.load(emptySet(), loader))
        cluster.join("there", tables = TableRegistry.load(emptySet(), loader))
        cluster.awaitEveryoneSeesEveryone()

        val answer = withContext(here) {
            rpcCall(node("there"), Greeting("world", 2)) { greeting ->
                List(greeting.times) { "hello " + greeting.subject }.joinToString(", ")
            }
        }

        assertEquals("hello world, hello world", answer)
    }

    @Test
    fun `a call of one's own carries its own scope to the far node`() = runTest {
        val cluster = RpcCluster(backgroundScope)
        val loader = javaClass.classLoader
        val here = cluster.join("here", tables = TableRegistry.load(emptySet(), loader))
        cluster.join(
            "there",
            tables = TableRegistry.load(emptySet(), loader),
            services = Services().apply {
                provide(GreeterScope::class) {
                    GreeterScope(NodeInfo(NodeId("there"), emptySet()), this, "hi")
                }
            },
        )
        cluster.awaitEveryoneSeesEveryone()

        val answer = withContext(here) { greeter("there") { salutation } }

        assertEquals("hi", answer)
    }

    @Test
    fun `a fan-out reaches every node that matched`() = runTest {
        val cluster = RpcCluster(backgroundScope)
        val loader = javaClass.classLoader
        val here = cluster.join("here", roles = setOf("worker"), tables = TableRegistry.load(emptySet(), loader))
        cluster.join("a", roles = setOf("worker"), tables = TableRegistry.load(emptySet(), loader))
        cluster.join("b", roles = setOf("worker"), tables = TableRegistry.load(emptySet(), loader))
        cluster.awaitEveryoneSeesEveryone()

        val answers = withContext(here) {
            forEachRpcCall({ Role("worker") in it.roles }) { node.id.value }
        }

        assertEquals(setOf("here", "a", "b"), answers.values.toSet())
    }
}
