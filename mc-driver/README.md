# `mc-driver`

Driving a real Minecraft server and client from another process, with four projects and no test
framework anywhere in them.

| | |
|---|---|
| [`driver`](driver/README.md) | The mod. An rpc node inside a running game, ~40 methods for driving it, and `cluster { }` to start the games in the first place |
| `gradle-plugin` | Declares the game runs, records how ModDevGradle would launch them, and points a run at the launcher. An included build |
| `launcher` | One Java class. Starts FancyModLoader, then runs somebody's `main` instead of a game |
| `junit` | Hands a running cluster to a JUnit test. A mod, for a reason worth reading below |
| `smoke` | A real server, a real client, and every verb tried once -- as `gradlew test` |

## How a run happens

```
gradlew :mc-driver:smoke:runDriver
   |
   +-- harvestDriverLaunchPlan   reads the two ModDevGradle run tasks -> launch-plan.json
   +-- seedDriverRunDirs         eula, server.properties, and last run's world deleted
   +-- runDriverMain             a NeoForge run whose main class is `Launch`
          |
          +-- Launch             starts FancyModLoader, hands over to Smoke.main
                 |
                 +-- cluster { } holds the hub, spawns the server and the clients,
                                 tells each one `-Drpc.hub=127.0.0.1:<port>`
```

Three ideas hold it up.

**A Minecraft command line is harvested, never reconstructed.** The classpath alone runs to hundreds
of entries chosen by a dozen artifact transforms, and it is only assembled inside the run task's
`exec()`. So the Gradle plugin reads it off the task ModDevGradle already built and writes it down;
the driver replays it, once per game, with a username and a game directory of its own.

**Anything talking to a modded game has to be inside one.** Not to run a game -- the driver runs
none -- but to *name* its types: to encode a `BlockPos`, resolve a procedure table, hold a serializer
for a game class. FancyModLoader hands mod classes to a transforming loader of its own, and resolving
them through any other gets a second copy of every one; a value handed across then fails to match a
type it plainly is, with an error naming that very type. `launcher` is how a plain `main` gets to run
in there.

**The driver holds the hub.** It listens on a free port, joins its own cluster as `driver` with no
roles at all -- so it resolves no tables and can run none of the bodies it dispatches -- and tells
every game it starts where to dial. Which is what a driver is.

## What a consuming build says

```kotlin
plugins {
    id("net.neoforged.moddev")
    id("dev.vibeported.rpc")
    id("dev.vibeported.mc.driver")
}

dependencies {
    rpcCompilerPlugin(project(":rpc:compiler-plugin"))
    mcDriverLauncher(project(":mc-driver:launcher"))
    implementation(project(":mc-driver:driver"))
}

mcDriver {
    sourceSet = sourceSets.main.get()
    mainClass = "com.example.Smoke"
}
```

The driver plugin *configures* ModDevGradle rather than applying it. Applying it from an included
build would load a second copy of MDG beside the one every other module uses, and two copies both
apply `gradle-idea-ext` to the root project -- colliding on the `settings` extension during an IDE
import, with an error naming neither plugin.

## Writing a test

Ordinary JUnit 5, run by the standard `test` task, clickable in the IDE:

```kotlin
@DrivesMinecraft
class Teleporting {
    @Test
    fun `a player lands where it was sent`(cluster: ClusterScope) = cluster.driving {
        teleport("alex", BlockPos(8, 70, 8), flying = true)
        assertEquals(BlockPos(8, 70, 8), positionOf("alex"))
    }
}
```

`gradlew :mc-driver:smoke:test`.

**Almost none of this is ours.** ModDevGradle already knows how to host JUnit in a modded
environment: `unitTest { }` puts NeoForge's `junit-fml` on the test runtime classpath, and that is a
`LauncherSessionListener` which boots FancyModLoader and swaps the thread context class loader to the
transforming one *before* JUnit discovers anything. NeoForge's own `JUnitMain` then bootstraps the
registries and loads the mods. The driver's Gradle plugin turns that on and adds the three things a
driver needs on top: where the launch plan is, where captures go, and the test output being part of a
mod. A consuming build says nothing about any of it.

**The cluster is shared by the whole run**, because a client takes the better part of a minute to
reach a world. `startServer` and `startClient` are idempotent, so every test asks for what it needs
and only the first one pays. A class that cannot share says `@OwnCluster` and gets games of its own.
The price is the ordinary price of shared state: `ScreenTest` has to close the screen it opened,
because the inventory key toggles and the next test would otherwise wait forever for a screen it had
just closed.

**`runBlocking`, not `runTest`.** Everything here waits on real wall-clock events in other processes.
Virtual time cannot advance a game booting, and it would turn the driver's quarter-second roster poll
into a hot loop.

### Why the annotation, and why `junit` is a mod

Both answers are the same answer, and it is the one this project keeps meeting.

Jupiter can find an extension by service loader, and that was tried first. It cannot work here: under
FancyModLoader the test classes are loaded by the transforming class loader, but the *thread context*
class loader during execution is the plain application one, and service-loader discovery uses the
latter. The extension it finds is an application-loader copy whose `ClusterScope` is a different
class from the `ClusterScope` in the test's own signature -- so Jupiter reports that no resolver
supports the parameter, having registered one that does.

Naming the extension in `@DrivesMinecraft` resolves it through the class that carries the annotation,
which is the test's loader. And `junit` ships a `neoforge.mods.toml` for the same reason the
orchestrator did: being a mod is how a jar gets into that loader at all. With the annotation but
without the mod metadata, the extension is still loaded by the application loader and the failure is
identical.

## The smoke run


The tests above are the smoke run. They prove the parts that install themselves *by side effect* at
startup -- the mod loading, the ten mixins applying, the input gate taking the keyboard, the frame
hook feeding the encoder, a value surviving the trip -- every one of which fails silently. The last
of them ends in a real H.264 recording, which is the only way to find out that the `GameRenderer`
mixin is in place.

`gradlew :mc-driver:smoke:runDriver` is the other entry point: a plain `main` through the launcher,
kept because that path also installs itself silently and would rot unnoticed.

The suite has earned its keep three times over. It found that a driver process must bootstrap the
game registries before it can name anything; that it cannot construct an `ItemStack` at all, which is
why items are text; and that a procedure written inside a test is on no classpath the *games* hold
unless the test output is part of the mod they load.
