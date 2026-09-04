package dev.vibeported.mc.driver.junit

/**
 * Gives this class a server and clients of its own.
 *
 * Without it a class shares the run's cluster, which is what makes a suite affordable: a client
 * takes the better part of a minute to reach a world, so fifty tests over one set of games cost one
 * boot and fifty over their own cost fifty.
 *
 * Worth the boot when a class cannot leave the world as it found it -- one that stops the server,
 * fills the spawn chunks, or needs a player who has never moved. Everything else should share, and
 * keep out of its neighbours' way by building its fixtures somewhere else in the world.
 *
 * The games close when the class does. @see DriverExtension
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class OwnCluster
