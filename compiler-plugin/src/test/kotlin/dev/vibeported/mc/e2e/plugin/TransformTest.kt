package dev.vibeported.mc.e2e.plugin

import dev.vibeported.mc.e2e.report.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TransformTest {

    @TempDir
    lateinit var workingDir: File

    private val suiteSource = """
        package sample

        import dev.vibeported.mc.e2e.assertThat
        import dev.vibeported.mc.e2e.client
        import dev.vibeported.mc.e2e.server
        import dev.vibeported.mc.e2e.shared
        import dev.vibeported.mc.e2e.suite

        val movement = suite("movement") {
            e2e("block moved") {
                val count = shared<Int>()

                server {
                    count.set(7)
                    client {
                        assertThat("the client should see what the server wrote") { count.get() == 7 }
                    }
                }

                client {
                    assertThat("and still see it afterwards") { count.get() == 7 }
                }
            }
        }
    """.trimIndent()

    @Test
    fun `ids are structural and readable`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to suiteSource)
        assertTrue(result.succeeded, result.messages)

        val file = result.index().files.single()
        assertEquals("sample.SuiteKt", file.facadeClass)
        assertEquals("sample.E2eBlocks_SuiteKt", file.tableClass)
        assertEquals("getMovement", file.suites.single().accessor)

        assertEquals(
            listOf(
                "sample.SuiteKt:movement/block moved/server[0]",
                "sample.SuiteKt:movement/block moved/server[0]/client[default][0]",
                "sample.SuiteKt:movement/block moved/client[default][0]",
            ),
            file.blocks.map { it.id.value },
        )
    }

    /**
     * The test body never runs, so what the orchestrator needs from it is the order of its blocks.
     * Only the top-level ones are steps; the nested client belongs to the server block that raises
     * it, not to the test.
     */
    @Test
    fun `a test is an ordered list of steps, with no driver`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to suiteSource)
        assertTrue(result.succeeded, result.messages)

        val test = result.index().files.single().suites.single().tests.single()
        assertEquals(
            listOf(
                "sample.SuiteKt:movement/block moved/server[0]",
                "sample.SuiteKt:movement/block moved/client[default][0]",
            ),
            test.steps.flatMap { step -> step.blocks.map { it.value } },
        )
    }

    private val namedSource = """
        package sample

        import dev.vibeported.mc.e2e.client
        import dev.vibeported.mc.e2e.parallel
        import dev.vibeported.mc.e2e.server
        import dev.vibeported.mc.e2e.suite
        import dev.vibeported.mc.e2e.waitForPlayer

        val movement = suite("movement") {
            e2e("two clients") {
                server {
                    waitForPlayer("steve")
                    waitForPlayer("alex")
                }

                parallel {
                    client("steve") { }
                    client("alex") { }
                }

                client("steve") { }
            }
        }
    """.trimIndent()

    /**
     * Who takes part is decided at compile time, because the orchestrator has to start those
     * processes before a single block runs.
     */
    @Test
    fun `every client a file mentions reaches the manifest`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to namedSource)
        assertTrue(result.succeeded, result.messages)

        val file = result.index().files.single()
        // "alex" has a block; the server only ever names it. Both have to be started either way.
        assertEquals(listOf("alex", "steve"), file.clients)
        assertEquals(listOf("alex", "steve"), file.suites.single().tests.single().clients)
    }

    /** Ordinals count per name, so adding a client cannot renumber another one's blocks. */
    @Test
    fun `a parallel group is one step, and ordinals are per client`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to namedSource)
        assertTrue(result.succeeded, result.messages)

        val test = result.index().files.single().suites.single().tests.single()
        assertEquals(listOf(false, true, false), test.steps.map { it.parallel })
        assertEquals(
            listOf(
                listOf("sample.SuiteKt:movement/two clients/server[0]"),
                listOf(
                    "sample.SuiteKt:movement/two clients/client[steve][0]",
                    "sample.SuiteKt:movement/two clients/client[alex][0]",
                ),
                listOf("sample.SuiteKt:movement/two clients/client[steve][1]"),
            ),
            test.steps.map { step -> step.blocks.map { it.value } },
        )
    }

    @Test
    fun `a nested client block is addressed to the client, not the server that raised it`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to suiteSource)
        assertTrue(result.succeeded, result.messages)

        val nested = result.index().files.single().blocks
            .single { it.id.value.endsWith("/server[0]/client[default][0]") }

        assertEquals("CLIENT", nested.role.name)
        assertEquals("sample.SuiteKt:movement/block moved/server[0]", nested.parent?.value)
    }

    @Test
    fun `editing one test leaves the ids of its neighbours alone`() {
        val before = E2eCompilation(File(workingDir, "before")).compile("Suite.kt" to suiteSource)
        val after = E2eCompilation(File(workingDir, "after")).compile(
            "Suite.kt" to suiteSource.replace(
                "        client {\n            assertThat(\"and still see it afterwards\") { count.get() == 7 }\n        }",
                "        client {\n            assertThat(\"reworded entirely\") { count.get() == 7 }\n            log(\"and an extra statement\")\n        }",
            )
        )
        assertTrue(before.succeeded, before.messages)
        assertTrue(after.succeeded, after.messages)

        assertEquals(
            before.index().files.single().blocks.map { it.id.value },
            after.index().files.single().blocks.map { it.id.value },
        )
    }

    /**
     * The one that proves the whole scheme: the lifted bodies are loaded from the generated table by
     * id alone and run on three separate nodes, with the shared value crossing between them.
     */
    @Test
    fun `a compiled suite runs through the cluster`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to suiteSource)
        assertTrue(result.succeeded, result.messages)

        val report = runBlocking {
            val cluster = InProcessCluster.start(this, result.index(), result.classLoader)
            try {
                cluster.runAll()
            } finally {
                cluster.close()
            }
        }

        val test = report.tests.single()
        assertEquals(Outcome.PASSED, test.outcome, test.failure?.stack ?: "")
        assertEquals("movement", test.suiteName)
        assertEquals("block moved", test.testName)

        // The orchestrator runs nothing itself: it has no game on its classpath, so even the
        // driver is dispatched to the server.
        assertEquals(
            setOf("server", "client[default]"),
            test.blocks.map { it.node.toString() }.toSet(),
        )
    }

    @Test
    fun `an assertion failure is reported as a failure, not an error`() {
        val result = E2eCompilation(workingDir).compile(
            "Suite.kt" to """
                package sample

                import dev.vibeported.mc.e2e.assertThat
                import dev.vibeported.mc.e2e.client
                import dev.vibeported.mc.e2e.server
                import dev.vibeported.mc.e2e.shared
                import dev.vibeported.mc.e2e.suite

                val movement = suite("movement") {
                    e2e("wrong expectation") {
                        val count = shared<Int>()
                        server { count.set(7) }
                        client {
                            assertThat("count should have been 8") { count.get() == 8 }
                        }
                    }
                }
            """.trimIndent()
        )
        assertTrue(result.succeeded, result.messages)

        val report = runBlocking {
            val cluster = InProcessCluster.start(this, result.index(), result.classLoader)
            try {
                cluster.runAll()
            } finally {
                cluster.close()
            }
        }

        val test = report.tests.single()
        assertEquals(Outcome.FAILED, test.outcome)
        assertEquals("count should have been 8", test.failure?.message)
        // The failure keeps the node it actually happened on, even though it reached the
        // orchestrator by way of a response.
        assertEquals("client[default]", test.failure?.node.toString())
    }
}
