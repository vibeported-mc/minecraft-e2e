package dev.vibeported.mc.e2e.suite

/**
 * A named group of tests.
 *
 * There is nothing clever left in here. A test body is ordinary suspending code: it may loop, hold
 * locals, start coroutines, call helpers, and do anything else Kotlin allows, because the framework
 * no longer reads it. `server { }` and `client { }` are calls like any other, so composing them is
 * this module's opinion rather than the framework's -- and a JUnit runner would be an equally valid
 * one.
 */
public class Suite internal constructor(
    public val name: String,
    public val tests: List<Test>,
)

public class Test internal constructor(
    public val name: String,
    public val body: suspend () -> Unit,
)

public fun suite(name: String, body: SuiteBuilder.() -> Unit): Suite {
    val builder = SuiteBuilder(name)
    builder.body()
    return builder.build()
}

public class SuiteBuilder internal constructor(private val name: String) {

    private val tests = mutableListOf<Test>()

    /**
     * Declares one test.
     *
     * [body] runs as written. It used to be read at compile time and thrown away, which is why it
     * was once allowed to contain almost nothing; that restriction went with the machinery that
     * needed it.
     */
    public fun e2e(name: String, body: suspend () -> Unit) {
        tests += Test(name, body)
    }

    internal fun build(): Suite = Suite(name, tests.toList())
}
