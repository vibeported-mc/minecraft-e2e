package dev.vibeported.rpc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue

class ManifestTest {

    @Test
    fun `round trips`() {
        val manifest = ProcedureManifest(
            listOf(
                ProcedureManifest.Entry("a", "TableA", role = null, module = "one"),
                ProcedureManifest.Entry("b", "TableB", role = "client", module = "two"),
            )
        )
        assertEquals(manifest, ProcedureManifest.parse(ProcedureManifest.render(manifest)))
    }

    @Test
    fun `two modules claiming one id names both`() {
        val loader = manifestsOf(
            """{"entries":[{"id":"clash","table":"T1","module":"first"}]}""",
            """{"entries":[{"id":"clash","table":"T2","module":"second"}]}""",
        )

        val failure = assertThrows<IllegalStateException> { ProcedureManifest.load(loader) }

        // Naming one of them would leave the reader to find the other.
        assertTrue("first" in failure.message!! && "second" in failure.message!!, failure.message!!)
    }

    /** A loader serving several copies of the manifest resource, as several jars would. */
    private fun manifestsOf(vararg bodies: String): ClassLoader {
        val urls = bodies.map { body ->
            java.net.URL("string", "", -1, "manifest", object : java.net.URLStreamHandler() {
                override fun openConnection(u: java.net.URL) = object : java.net.URLConnection(u) {
                    override fun connect() = Unit
                    override fun getInputStream() = body.byteInputStream()
                }
            })
        }
        return object : ClassLoader(null) {
            override fun getResources(name: String) =
                if (name == ProcedureManifest.RESOURCE) java.util.Collections.enumeration(urls)
                else java.util.Collections.emptyEnumeration()
        }
    }
}
