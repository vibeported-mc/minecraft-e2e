package dev.vibeported.mc.driver.junit

import dev.vibeported.mc.driver.Cluster
import dev.vibeported.mc.driver.ClusterScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver

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
 * **One cluster for the whole run.** A client takes the better part of a minute to reach a world, so
 * a suite booting one per class would spend its life booting games. The cluster is kept in JUnit's
 * root store and closed when the run ends; `startServer` and `startClient` are idempotent, so every
 * test asks for what it needs and only the first one pays.
 *
 * The price is the ordinary price of shared state, and a caller has to think about it: a test that
 * opens a screen closes it, and two tests building fixtures put them somewhere different.
 *
 * There is deliberately no per-class option. One was written and taken out again: the server port
 * and the game directories come fixed from the launch plan and the seeding task, so a second cluster
 * alive beside the first would put two dedicated servers on port 25565. Isolating a class properly
 * means giving a cluster its own port and directories, and that is worth doing when something needs
 * it -- with a test that proves two can coexist, rather than an annotation that quietly cannot.
 */
public class DriverExtension : ParameterResolver {

    override fun supportsParameter(parameter: ParameterContext, context: ExtensionContext): Boolean =
        parameter.parameter.type == ClusterScope::class.java ||
            parameter.parameter.type == Cluster::class.java

    override fun resolveParameter(parameter: ParameterContext, context: ExtensionContext): Any =
        clusterFor(context)

    /**
     * The run's cluster, made on first ask.
     *
     * The root store is what makes it the run's: JUnit closes a `CloseableResource` when the store
     * holding it goes out of scope, and the root store goes out of scope when the run ends. So
     * nothing here tracks what to shut down or when.
     */
    private fun clusterFor(context: ExtensionContext): Cluster =
        context.root.getStore(NAMESPACE)
            .getOrComputeIfAbsent(KEY, { OpenCluster() }, OpenCluster::class.java)
            .cluster

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
