package dev.vibeported.mc.driver.junit

import dev.vibeported.mc.driver.Cluster
import dev.vibeported.mc.driver.ClusterScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.platform.commons.support.AnnotationSupport

/**
 * Hands a running cluster to any test that asks for one.
 *
 * A test declares a [ClusterScope] parameter and gets games it can drive:
 *
 * ```kotlin
 * class Teleporting {
 *     @Test
 *     fun `a player lands where it was sent`(cluster: ClusterScope) = runBlocking {
 *         cluster.startServer()
 *         cluster.startClient("alex")
 *
 *         teleport("alex", BlockPos(8, 70, 8), flying = true)
 *         assertEquals(BlockPos(8, 70, 8), positionOf("alex"))
 *     }
 * }
 * ```
 *
 * Reached through [DrivesMinecraft] on the class, which is the only syntax there is.
 *
 * **One cluster for the whole run, by default.** A client takes the better part of a minute to reach
 * a world, so a suite that booted one per class would spend its life booting games. The cluster is
 * kept in JUnit's root store and closed when the run ends. `startServer` and `startClient` are
 * idempotent, so every test may ask for what it needs and only the first one pays.
 *
 * A class that genuinely cannot share -- one that stops the server, or wants an untouched world --
 * says [OwnCluster] and gets games of its own for the length of the class.
 */
public class DriverExtension : ParameterResolver {

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == ClusterScope::class.java ||
            parameter.parameter.type == Cluster::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any =
        clusterFor(context)

    private fun clusterFor(context: ExtensionContext): Cluster {
        // The store decides the lifetime, and that is the whole of the difference: the root store
        // outlives every test in the run, a class's store closes with the class. JUnit closes a
        // `CloseableResource` when the store it sits in goes out of scope, so nothing here has to
        // track what to shut down or when.
        val owner = if (wantsItsOwn(context)) enclosingClass(context) else context.root
        return owner.getStore(NAMESPACE)
            .getOrComputeIfAbsent(KEY, { OpenCluster() }, OpenCluster::class.java)
            .cluster
    }

    /**
     * Whether this test's class asked for a cluster of its own.
     *
     * Looked up on the enclosing class rather than the method, because the games are shared by
     * everything in that class and a per-method cluster would be one game boot per test.
     */
    private fun wantsItsOwn(context: ExtensionContext): Boolean =
        AnnotationSupport.findAnnotation(context.testClass, OwnCluster::class.java).isPresent

    /** The class context, so a class-scoped cluster closes with the class rather than the method. */
    private fun enclosingClass(context: ExtensionContext): ExtensionContext =
        generateSequence(context) { it.parent.orElse(null) }
            .firstOrNull { it.testClass.isPresent && it.testMethod.isEmpty }
            ?: context.root

    /**
     * A cluster in a store, closed when that store is.
     *
     * Opened eagerly rather than lazily: the failure to open one is worth having at the first test
     * that wants it, with that test's name on it, rather than at some later point nobody expects.
     */
    private class OpenCluster : ExtensionContext.Store.CloseableResource {
        val cluster: Cluster = runBlocking { Cluster.open() }

        override fun close() {
            cluster.close()
        }
    }

    private companion object {
        val NAMESPACE: ExtensionContext.Namespace = ExtensionContext.Namespace.create("mcdriver")
        const val KEY = "cluster"
    }
}
