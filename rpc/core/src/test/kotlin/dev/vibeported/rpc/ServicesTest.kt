package dev.vibeported.rpc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue

class ServicesTest {

    class Greeter(val prefix: String)

    @Test
    fun `the same instance reaches every caller`() {
        val services = Services()
        var built = 0
        services.provide { built++; Greeter("hi") }

        val first = services.resolve<Greeter>()
        val second = services.resolve<Greeter>()

        // The whole point of the registry: a node wrapped around one game client hands that same
        // client to every procedure routed to it.
        assertSame(first, second)
        assertTrue(built == 1, "the factory ran $built times, not once")
    }

    @Test
    fun `a factory is not run until something asks`() {
        val services = Services()
        services.provide<Greeter> { error("built too early") }
        // Registering must not build: a node is assembled before the thing it wraps exists.
    }

    @Test
    fun `an unknown receiver names what the node does have`() {
        val services = Services()
        services.provide(Greeter("hi"))

        val failure = assertThrows<IllegalStateException> { services.resolve<StringBuilder>() }

        assertTrue("StringBuilder" in failure.message!!, failure.message!!)
        assertTrue("Greeter" in failure.message!!, "should list what it offers: ${failure.message}")
    }
}
