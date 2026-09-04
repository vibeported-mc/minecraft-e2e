# minecraft-e2e

Driving a **real dedicated server and real game clients**, in separate processes, from ordinary
JUnit tests.

```kotlin
@DrivesMinecraft
class Teleporting {

    @Test
    fun `a player lands where it was sent`(cluster: ClusterScope) = cluster.driving {
        worldBuild { at(94, 200, 200) { "minecraft:stairs[facing=north]" } }

        teleport("alex", BlockPos(94, 203, 200), flying = true)
        assertEquals(BlockPos(94, 203, 200), positionOf("alex"))

        client("alex") {
            press(Key.W, ticks = 20)
            breakBlock(BlockPos(94, 200, 200))
        }
    }
}
```

`gradlew :mc-driver:smoke:test`. That boots a server and a client, runs the tests inside a prepared
NeoForge environment, and takes screenshots and H.264 recordings along the way.

## The three parts

| | |
|---|---|
| [`rpc`](rpc/README.md) | Running code on another machine by **writing it where it is called**. A compiler plugin lifts each `server { }` or `client { }` body into a dispatch table; the call site keeps a name. Knows nothing about Minecraft — a build task fails if it can even resolve the game |
| [`mc-driver`](mc-driver/README.md) | A NeoForge mod that puts an rpc node in a running game, ~40 methods for driving one, and `cluster { }` to start the games in the first place. Plus the Gradle plugin and JUnit integration that make `gradlew test` a modded environment |
| `capture` | FFmpeg cross-built for Windows, Panama bindings generated from the headers it was built with, and an object layer over them. What records a client's screen through NVENC without the frames ever reaching the CPU |

## What makes it different

**A test body is written once and runs somewhere else.** Not a message, not a proxy — the compiler
lifts the lambda into a class on the node that will run it, and what crosses the wire is a name and
its arguments. So `server { }` and `client { }` read as ordinary Kotlin, and a captured local is a
compile error rather than a puzzle at run time. [`rpc/NOT_RPC.md`](rpc/NOT_RPC.md) says what this is
and is not.

**Everything a body sends is checked at compile time.** A type nothing can encode is an error on the
line that named it. A serializer is declared beside its type with `@RpcSerializer` and travels with
the jar, so a node's classpath decides what it can be sent.

**Roles are the dist split.** A dedicated server is dist-cleaned, so a body touching client classes
is not slow there, it is *unloadable*. Bodies land in different generated classes by role, and a
server never resolves the client's.

**The games are started by the driver, not by Gradle.** A Minecraft command line cannot be
reconstructed by hand, so the Gradle plugin declares two runs and reads them; the driver replays that
per game, with a username and directory of its own.

## Layout

```
rpc/          core, transport, host, testkit, the K2 compiler plugin, its Gradle plugin,
              an example consumer, and e2e/ — three processes with three classpaths, the
              only way to test a node that cannot load half the jar it is holding
mc-driver/    driver (the mod), gradle-plugin, junit, launcher, smoke (the tests)
capture/      libav-gen (Panama bindings), libav (the object layer)
tools/        code generators: rpc call overloads, driver call overloads, cursor sprites
```
