# `mc-driver:junit`

Hands a running cluster to a JUnit 5 test.

```kotlin
@DrivesMinecraft
class Teleporting {
    @Test
    fun `a player lands where it was sent`(cluster: ClusterScope) = runBlocking {
        cluster.startServer()
        cluster.startClient("alex")

        teleport("alex", BlockPos(8, 70, 8), flying = true)
        assertEquals(BlockPos(8, 70, 8), positionOf("alex"))
    }
}
```

A module of its own, and that is the point. The [driver](../driver/README.md) knows nothing about
tests — no assertions, no reports, no runner — and a dependency on `junit-jupiter-api` over there
would end that. So the integration lives here, depends on the driver, and nothing depends on it but
a test.

## What it is

Two declarations and a mod metadata file.

`DriverExtension` is a `ParameterResolver`: a test declares a `ClusterScope` parameter and gets one.
`@DrivesMinecraft` puts that extension on a class.

**One cluster for the whole run, by default.** A client takes the better part of a minute to reach a
world, so a suite booting one per class would spend its life booting games. The cluster is kept in
JUnit's root store and closed when the run ends; `startServer` and `startClient` are idempotent, so
every test asks for what it needs and only the first pays. `@OwnCluster` on a class gives it games of
its own, closed with the class, for the rare test that cannot leave the world as it found it.

The price of sharing is the ordinary price of shared state, and it is worth knowing before it bites:
a test that opens a screen has to close it, because the inventory key toggles and the next test would
otherwise wait forever for a screen it had just closed.

## Why the annotation, and why this is a mod

Both are the same answer, and it is the duplicate-class trap this project keeps meeting.

Jupiter can find an extension by service loader, and that was tried first. It cannot work here. Under
FancyModLoader the test classes are loaded by the transforming class loader, but the **thread context
class loader during execution is the plain application one**, and service-loader discovery uses that.
The extension it finds is an application-loader copy whose `ClusterScope` is a *different class* from
the `ClusterScope` in the test's own signature — so Jupiter reports that no resolver supports the
parameter, having registered one that does.

Naming the extension in an annotation resolves it through the class carrying the annotation, which is
the test's loader. And this module ships a `neoforge.mods.toml` for the reason the orchestrator did
before it: being a mod is how a jar gets into that loader at all. With the annotation but without the
mod metadata, the extension is still application-loaded and the failure is identical.

Both halves were established by measurement rather than reasoning — the annotation alone left all
sixteen tests failing exactly as before.
