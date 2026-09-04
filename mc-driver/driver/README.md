# `driver` — driving Minecraft from another process

One NeoForge mod, `mcdriver`. It puts an [rpc](../../rpc/README.md) node inside a running game and
exposes methods for driving it: move a player, build some world, open a screen and drag a stack,
take a picture, record a video. It also **starts the games**: `cluster { }` holds the hub, spawns the
server and as many clients as asked for, and tells each one where to dial.

It knows nothing about tests. No assertions, no reports, no logging, no notion of a run or a suite.
Whatever is driving supplies all of that; this supplies the verbs.

```kotlin
cluster {
    startServer()
    startClient("alex")

    withTimeout(10.seconds) { teleport("alex", BlockPos(94, 203, 200), flying = true) }

    worldBuild {
        at(94, 200, 200) { "minecraft:stairs[facing=north]" }
        fill(90..98, 199..199, 196..204) { "minecraft:stone" }
    }

    giveItem("alex", InventorySlot.HOTBAR_1, "minecraft:diamond_sword")

    record("alex", "drag.mp4") {
        waitForScreen("alex", "InventoryScreen") {
            pickUp(InventorySlot.HOTBAR_1)
            dropOn(InventorySlot.HELMET)
        }
    }

    client("alex") {
        press(Key.W, ticks = 20)
        breakBlock(BlockPos(94, 200, 200))
    }
}
```

## The shape

**Everything is a free method.** `teleport(...)` is a sentence on its own; which side of the game
carries it out is this module's business, not the caller's. Each one opens a `server { }` or a
`client { }` of its own.

**A scope exists only as the receiver of a body.** `server { }` and `client { }` push one, and so do
`worldBuild { }` and `waitForScreen { }` — those two exist to offer a richer receiver, and they are
built out of exactly the same `@RpcLift` parameter anybody else would use. A lambda that is *not* a
lifted body — `record(…) { }`, `enableUiLayer(…) { }` — runs on the caller and takes no receiver at
all, so what is written inside it reads no differently from what surrounds it.

**Input lives on `ClientScope` rather than being free.** Every free method is a round trip, and input
comes in sequences — press, wait, release, look, click. Written inside one `client { }` they cost
one. A single keystroke is still `client("alex") { press(Key.W) }`, which says the same thing.

**Nothing has a deadline.** Every wait is unbounded and the caller says how long it will stand for
with `withTimeout`. A driver that invented a timeout vocabulary of its own would be a driver with an
opinion about failure, which is exactly the thing this module is not supposed to have.

**Blocks, items and screens are text.** `"minecraft:stairs[facing=north]"` goes through the game's
own `BlockStateParser` and `"minecraft:diamond_sword"` through its `ItemParser`, so anything
`/setblock` and `/give` accept works and a bad one fails with Mojang's own message. A screen is named
by its simple class name. All three are looser than a type, and all three are the only thing a caller
in another process can say at all.

For items that is forced rather than chosen, and it is worth knowing why: a stack's components are
bound while a **server loads its resources**, and a driver process runs no game. Constructing
`ItemStack(Items.DIAMOND_SWORD)` there throws `NullPointerException: Components not bound yet` from
inside `Item.components()`, long before anything could be encoded. So an item is built where the
registries exist, which is the arrangement blocks were already in.

## What crosses the wire

Everything a body takes or returns, and the compiler checks it. `BlockPos` is not `@Serializable`
and never will be — it is Mojang's class — so this module writes a `KSerializer` for it over the
game's own `Codec` and marks it `@RpcSerializer`. That is the whole registration: nothing in a build
script, nothing to remember at startup, and every node holding this jar assembles it into its wire
format as it starts.

It is the only one, and the missing `ItemStack` serializer is a decision rather than an oversight
— see the note on text above.

Two more things follow from values having to cross:

- **A method cannot return a live Minecraft object.** `waitForPlayer` returns nothing rather than a
  `ServerPlayer`; screens are named by string; a stack is read inside a `waitForScreen { }` body,
  where it never leaves the client.
- **`positionOf` and `isAlive` answer from the server**, which is the authority. A client's own view
  of another player lags behind, and is one field access away inside a `client { }`.

## The dist split

A dedicated server is dist-cleaned: a body touching client classes there is not slow, it is
*unloadable*. So every body in this module carries a role, and the roles decide which generated table
it lands in — `PlayerKt_Rpc_server` names only `net.minecraft.server.*`, `CapturingKt_Rpc_client`
names `net.minecraft.client.Minecraft`, and a server node never resolves the second. The manifest is
the thing to check when something here changes: every entry must have a role, and there must be no
default-role table.

## Configuration

| Property | What it says |
| --- | --- |
| `rpc.node` | This process's name. For a client it is also its username |
| `rpc.roles` | `server` or `client`. Names neither and the mod does nothing at all |
| `rpc.hub` | `host:port` of the middle of the star. Told, never discovered |
| `mcdriver.capture.dir` | Where screenshots, recordings and game logs go, filed by client |
| `mcdriver.launch.plan` | How to start a game. Written by the Gradle plugin, read by `cluster { }` |
| `mcdriver.window.index` / `.count` | Which of how many clients this is, so two windows do not land on top of each other |

The first three are the rpc framework's own convention, so anything that can launch a node can launch
a game the same way. A process with no `rpc.node` is not part of a cluster and this mod changes
nothing, which is what lets the jar sit in an ordinary development client.

## The methods

**The cluster** — `cluster { }`, whose receiver has `startServer`, `startClient`, `startClients`,
`deadProcess` and `hub`, and `Cluster.open()` for when it has to outlive a lambda. The one scope here
that is not a lifted body's receiver: spawning a JVM is not something a game can be asked to do, so
these run in the calling process.

**Players and movement** — `waitForPlayer`, `teleport`, `lookAt`, `lookAtPlayer`, `allowFlight`,
`awaitDeath`, `positionOf`, `isAlive`, `giveItem`.

**World** — `worldBuild { at(…) { }, fill(…) { } }`.

**Screens** — `waitForScreen(client, screen) { }`, whose receiver has `stackAt`, `carried`,
`selectedHotbar`, `hoveredSlot`, `pointerInGui`, `moveToSlot`, `pickUp`, `dropOn`, `swapSlot`,
`click`.

**Capture and chrome** — `screenshot`, `record`, `startRecording`, `stopRecording`, `setUiLayer`,
`uiVisible`, `enableUiLayer`, `blockInput`, `connectedClients`.

**Inside `client { }`** — `press`, `keyDown`, `keyUp`, `type`, `click`, `mouseDown`, `mouseUp`,
`scroll`, `moveMouseTo`, `moveMouseBy`, `breakBlock`, `useBlock`, `attack`, `chat`, `awaitScreen`,
`awaitNoScreen`, `currentScreen`.

**Inside `server { }`** — `minecraftServer`, `serverLevel`, `serverPlayers`, `playerNamed`,
`playerOrNull`, `worldBuild`.

**On either** — `level`, `currentTick`, `awaitTicks`, `awaitUntil`.

## What it does not do

- **No assertions.** A method that cannot do its job waits, and the caller's `withTimeout` decides
  when that has gone on too long. The reads are all there — `positionOf`, `isAlive`, `stackAt`,
  `currentScreen` — for whoever wants to compose a better message than "time ran out".
- **No logging and no reports.** Nothing here writes a line anywhere on a caller's behalf.
- **It does not start games.** Launching a process belongs to whatever launched the cluster;
  `connectedClients` says who actually turned up.
- **Recording needs an NVIDIA GPU.** Without one it is refused with the reason in that client's log,
  and everything carries on.

## A hazard worth knowing

Procedure ids have no package in them — `PlayerKt.teleport/0` — so two modules that both define a
`PlayerKt.teleport` cannot share a classpath; `ProcedureManifest.load` refuses the duplicate. Nothing
enforces it, and it is the thing to remember before copying this module rather than depending on it.

## Proof that it works

[`:mc-driver:smoke`](../smoke) drives a real server and a real client through every verb above, as
ordinary JUnit tests: `gradlew :mc-driver:smoke:test`. [`:mc-driver:junit`](../junit) is what hands
one of those tests a cluster.
