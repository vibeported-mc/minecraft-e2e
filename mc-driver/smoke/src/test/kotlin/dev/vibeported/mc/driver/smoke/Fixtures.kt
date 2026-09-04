package dev.vibeported.mc.driver.smoke

import dev.vibeported.mc.driver.ClusterScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.minecraft.core.BlockPos
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What every test in this module needs before it can do anything.
 *
 * The games are shared by the whole run, so this is called by each test and does nothing after the
 * first: `startServer` and `startClient` both return immediately once the node is on the roster. A
 * suite of thirty tests therefore boots one server and one client, not thirty of each.
 *
 * **`runBlocking`, not `runTest`.** `runTest` runs its body on a `TestScope` with virtual time,
 * which is exactly the wrong instrument for this: everything here waits on real wall-clock events in
 * *other processes* -- a game booting, a chunk ticking, a packet arriving. Virtual time cannot
 * advance any of that, and it would skip the `delay(250)` inside the driver's own roster poll, so a
 * polite check every quarter second becomes a hot loop for the minute a client takes to reach a
 * world. `runTest` also expects the scheduler to go idle and complains about coroutines outliving
 * the test, and the cluster's scope deliberately outlives every one of them.
 *
 * The one thing `runTest` would have given for free is a deadline, so that is supplied here instead.
 * Without it a driver call that never returns hangs the whole build rather than failing one test.
 */
internal fun ClusterScope.driving(
    within: Duration = 60.seconds,
    body: suspend () -> Unit,
): Unit = runBlocking {
    // Outside the timeout: booting a client is a minute of honest work, not the thing under test,
    // and it happens once for the whole run rather than once per test.
    startServer()
    startClient(ALEX)

    withTimeout(within) { body() }
}

/** The one client this module drives. */
internal const val ALEX: String = "alex"

/**
 * Somewhere on the flat world's surface with room around it.
 *
 * Spaced apart per subject on purpose. The world is shared, so two tests building fixtures on top of
 * each other would pass alone and fail together -- which is the one failure a shared cluster can
 * cause and the one thing a caller has to think about in exchange for the boot it saves.
 */
internal object Where {
    val GROUND: BlockPos = BlockPos(8, 65, 8)
    val PERCH: BlockPos = BlockPos(8, 70, 8)
    val BUILDING: BlockPos = BlockPos(40, 65, 8)
    val REJECT: BlockPos = BlockPos(72, 65, 8)
}
