package dev.vibeported.mc.e2e

/**
 * Marks a parameter that names a client.
 *
 * One annotation rather than a list of functions the compiler plugin knows about: anything that
 * later needs a client name gets the same treatment by annotating its parameter. The argument must
 * be a string literal, so every name a suite mentions can be collected at compile time -- which is
 * what lets the orchestrator start exactly the clients the tests ask for.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class MinecraftClientName

/** The client a test gets when it does not ask for one by name. */
public const val DEFAULT_CLIENT: String = "default"
