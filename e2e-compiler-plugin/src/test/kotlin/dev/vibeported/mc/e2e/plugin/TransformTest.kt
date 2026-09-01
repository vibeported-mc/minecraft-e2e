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
                var count by shared<Int>()

                server {
                    count = 7
                    client {
                        assertThat("the client should see what the server wrote") { count == 7 }
                    }
                }

                client {
                    assertThat("and still see it afterwards") { count == 7 }
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
                "sample.SuiteKt:movement/block moved/server[0]/client[0]",
                "sample.SuiteKt:movement/block moved/client[0]",
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
                "sample.SuiteKt:movement/block moved/client[0]",
            ),
            test.steps.map { it.value },
        )
    }

    @Test
    fun `a nested client block is addressed to the client, not the server that raised it`() {
        val result = E2eCompilation(workingDir).compile("Suite.kt" to suiteSource)
        assertTrue(result.succeeded, result.messages)

        val nested = result.index().files.single().blocks
            .single { it.id.value.endsWith("/server[0]/client[0]") }

        assertEquals("CLIENT", nested.role.name)
        assertEquals("sample.SuiteKt:movement/block moved/server[0]", nested.parent?.value)
    }

    @Test
    fun `editing one test leaves the ids of its neighbours alone`() {
        val before = E2eCompilation(File(workingDir, "before")).compile("Suite.kt" to suiteSource)
        val after = E2eCompilation(File(workingDir, "after")).compile(
            "Suite.kt" to suiteSource.replace(
                "        client {\n            assertThat(\"and still see it afterwards\") { count == 7 }\n        }",
                "        client {\n            assertThat(\"reworded entirely\") { count == 7 }\n            log(\"and an extra statement\")\n        }",
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
            setOf("server", "client[0]"),
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
                        var count by shared<Int>()
                        server { count = 7 }
                        client {
                            assertThat("count should have been 8") { count == 8 }
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
        assertEquals("client[0]", test.failure?.node.toString())
    }
}
