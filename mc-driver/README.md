# `mc-driver`

Driving a real Minecraft server and client from another process, with four projects and no test
framework anywhere in them.

| | |
|---|---|
| [`driver`](driver/README.md) | The mod. An rpc node inside a running game, ~40 methods for driving it, and `cluster { }` to start the games in the first place |
| `gradle-plugin` | Declares the game runs, records how ModDevGradle would launch them, and points a run at the launcher. An included build |
| `launcher` | One Java class. Starts FancyModLoader, then runs somebody's `main` instead of a game |
| `smoke` | A real server, a real client, and every verb tried once |

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

## The smoke run

`gradlew :mc-driver:smoke:runDriver` starts a dedicated server and one client, tries every verb once,
and prints a line per step. It is not a test: no assertions library, no report, no retries. It is the
only thing that can prove the parts that install themselves *by side effect* at startup -- the mod
loading, the ten mixins applying, the input gate taking the keyboard, the frame hook feeding the
encoder, a value surviving the trip -- every one of which fails silently.

Seventeen steps, ending with a real H.264 recording: `recorded 33 frames ... h264_nvenc on NVIDIA
GeForce RTX 5080`, which is the only way to find out that the `GameRenderer` mixin is in place.

It has already earned its keep twice. It is what found that a driver process must bootstrap the game
registries before it can name anything, and then that it cannot construct an `ItemStack` at all,
which is why items are text.
