package dev.vibeported.rpc.example

import dev.vibeported.rpc.TableRegistry
import dev.vibeported.rpc.node
import dev.vibeported.rpc.rpcCall
import dev.vibeported.rpc.testkit.RpcCluster
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * One function, three machines, read top to bottom.
 *
 * This is the shape the whole design exists for, and it is the example in `rpc/README.md` -- kept
 * here, compiled and run, so the documentation cannot drift away from something that works.
 */
suspend fun doWork(): Int {
    // Runs on node a. Written here.
    val fromA = rpcCall(node("a")) { 2 }

    // Runs on node b. Written here -- and note `fromA` is *passed*, not captured: this body runs in
    // another process, where a local of this function does not exist. The compiler rejects the
    // version that closes over it, rather than leaving it to fail at run time.
    val fromB = rpcCall(node("b"), fromA) { a -> a * 3 }

    // Runs here.
    return fromA + fromB
}

class OneFileTest {

    @Test
    fun `one function runs in three places and adds up`() = runTest {
        val cluster = RpcCluster(backgroundScope)
        val loader = javaClass.classLoader

        val here = cluster.join("here", tables = TableRegistry.load(emptySet(), loader))
        cluster.join("a", tables = TableRegistry.load(emptySet(), loader))
        cluster.join("b", tables = TableRegistry.load(emptySet(), loader))
        cluster.awaitEveryoneSeesEveryone()

        // 2 from a, then 2 * 3 from b, then the addition on this node.
        assertEquals(8, withContext(here) { doWork() })
    }
}
