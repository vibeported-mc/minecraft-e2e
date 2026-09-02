package dev.vibeported.mc.e2e.tests

import dev.vibeported.mc.e2e.suite.Runner

/**
 * What the orchestrator calls once the cluster is up.
 *
 * An ordinary `main`, named in the build. Nothing about it is special to this framework, which is
 * the point: a JUnit console launcher would do here just as well, because by the time this runs the
 * transport is already wired and `server { }` and `client { }` work from anywhere.
 */
fun main() {
    Runner.run(blocks)
}
