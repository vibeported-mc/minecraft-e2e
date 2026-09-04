package dev.vibeported.mc.driver.junit

import org.junit.jupiter.api.extension.ExtendWith

/**
 * Gives this class a cluster it can drive.
 *
 * The only syntax there is:
 *
 * ```kotlin
 * @DrivesMinecraft
 * class Teleporting {
 *     @Test fun `a player lands where sent`(cluster: ClusterScope) = …
 * }
 * ```
 *
 * ## Why this is not found automatically
 *
 * Jupiter can discover an extension by service loader, and that was tried first. It does not work
 * here, for a reason worth writing down because it will look like a bug to whoever meets it next.
 *
 * Under FancyModLoader the test classes are loaded by a transforming class loader, but the *thread
 * context* class loader during execution is the plain application one. Service-loader discovery uses
 * the latter, so the extension it finds is an application-loader copy -- and the `ClusterScope` that
 * copy names is a different class from the `ClusterScope` in the test's own signature. Jupiter then
 * quite correctly reports that no resolver supports the parameter, having registered one that does.
 *
 * Naming the extension in an annotation resolves it through the class that carries the annotation,
 * which is the test's loader, so both sides agree about what a `ClusterScope` is. One token per test
 * class buys that, and it is the same duplicate-class trap the rest of this project keeps meeting.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(DriverExtension::class)
public annotation class DrivesMinecraft
