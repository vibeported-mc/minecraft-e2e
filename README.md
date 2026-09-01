# minecraft-e2e

End-to-end tests for NeoForge mods that run against a **real dedicated server and a real client**, in
separate processes, driven by an orchestrator.

```kotlin
val blocks = suite("blocks") {

    e2e("two players fly to a block, watch it, then watch each other") {
        val target = shared<BlockPos>()
        val alexAt = shared<BlockPos>()

        server {
            waitForPlayer("steve")
            waitForPlayer("alex")
            serverLevel.setBlockAndUpdate(FAR_AWAY, Blocks.GOLD_BLOCK.defaultBlockState())
            target.set(FAR_AWAY)
        }

        parallel {
            client("steve") {
                val block = target.get()
                teleport(block.offset(-3, 4, -3), flying = true)
                assertBlock("steve should see the gold block", block, timeoutSec(10)) {
                    it.block == Blocks.GOLD_BLOCK
                }
                lookAt(block)
                delay(5.seconds)

                alexAt.get()            // finishes the moment alex has landed
                lookAtPlayer("alex")
            }

            client("alex") { /* the mirror image */ }
        }
    }
}
```

That test passes today against Minecraft 26.2 / NeoForge 26.2.0.69, with two real client
processes running the two halves of that `parallel` at once:

```
PASS blocks > two players fly to a block, watch it, then watch each other  (10450 ms)
    PASS server        …/server[0]            (98 ms)
    PASS client[steve] …/client[steve][0]  (10316 ms)
    PASS client[alex]  …/client[alex][0]   (10343 ms)
    log:
      [server]        placed a gold block at BlockPos{x=100, y=200, z=200}
      [client[steve]] alex is at BlockPos{x=103, y=204, z=203}
      [client[alex]]  steve is at BlockPos{x=97, y=204, z=197}
```

## Setting it up

The whole of a consuming build:

```kotlin
plugins {
    id("dev.vibeported.mc.e2e") version "0.1.0"
}

mcE2E {
    neoForge {                       // ModDevGradle's real NeoForgeExtension
        version = "26.2.0.69"
        mods { create("mymod") { sourceSet(sourceSets.main.get()) } }
    }
    modId = "mymod"
}
```

Write suites in `src/e2eTest/kotlin`, then:

```sh
./gradlew runE2eTests
```

That launches a dedicated server, launches one client per name the suites mention, runs every suite, prints a report
and writes `build/reports/e2e/report.json`. Each game process's console is captured under
`build/reports/e2e/logs/`, alongside the exact command used to start it.

The plugin applies Kotlin, serialization and ModDevGradle; creates the `e2eTest` source set and gives
it Minecraft; generates that mod's `neoforge.mods.toml`; applies the compiler plugin to it; registers
the two runs and seeds their directories. `neoForge { }` is ModDevGradle's own extension rather than
a wrapper, so anything the plugin has not thought to expose is still reachable, and nothing here has
to be kept in step with ModDevGradle as it changes.

## A test is a plan, not code

A test body may contain **only** shared declarations, `server`/`client` calls, and `parallel { }`
groups of those. Anything else is a compile error, because there is nowhere for it to run:

```kotlin
e2e("...") {
    val target = shared<BlockPos>()    // allowed
    server { … }                       // allowed
    client("steve") { … }              // allowed
    parallel { client("steve") { … }; client("alex") { … } }   // allowed
    println("hello")                   // compile error
}
```

Steps run one after another, which is what makes a test readable. `parallel { }` is the one way to
give that up, so a reader can tell at a glance which parts of a test overlap — and it is what lets
two clients look at each other, since neither is worth looking at until both have arrived.

The compiler reads the blocks out as an ordered list of steps and throws the body away. So the
orchestrator has nothing to execute — it walks the list, telling each node which block to run. That
is what makes "the orchestrator needs no Minecraft on its classpath" true by construction, and it is
why a crashed server cannot take the thing coordinating the test down with it.

## How a block gets to the other process

`server { }` and `client { }` run in different JVMs than the code that lexically contains them. An
ordinary Kotlin lambda cannot go there: it is a closure over locals that do not exist on the other
side.

So a K2 compiler plugin lifts every block body out of its closure into a generated dispatch table
keyed by a stable structural id, and rewrites `shared` reads and writes into RPC:

```kotlin
// at the call site:
scope.dispatch(BlockId("…/server[0]"), NodeId(SERVER, 0))

// and, in a generated object beside the file facade:
internal object E2eBlocks_BlocksKt : E2eBlockTable {
    override suspend fun invoke(id: String, scope: BlockScope): Any? {
        if (id == "…/server[0]") return b0_server_0_(scope)
        …
    }
}
```

The lambda is never serialized; only its id travels. The body is *moved* rather than copied — the
function the frontend already built for the lambda is re-parented into the table — so every symbol
inside it stays valid, and it stops being a closure because it is no longer nested in one.

## Blocks run on the game thread

A block body is dispatched onto its process's event loop, so **every Minecraft call in it is safe
with no wrapper**. Awaiting inside one — a `shared` value, a nested `client { }`, a `delay` — releases
the loop, so the game keeps ticking and the block resumes back on it.

Only block bodies go on the loop. Sockets, the RPC peer and the log pump stay on IO and Default, so a
slow tick cannot delay the machinery that is measuring it.

Two consequences worth knowing:

- **Every suspension point costs up to a tick**, since resuming means queueing a task the loop picks
  up on its next pass. A block doing many round trips is slower than one that batches its work.
- **Blocking the thread blocks the game.** `Thread.sleep` or a busy-wait inside a block stalls the
  server or freezes the client. `delay` is the one to reach for.

## Driving the client

A client is an instrument, not just a viewer. Input enters at the private methods the GLFW callbacks
call, so nothing downstream can tell the difference: key mappings are clicked, `handleKeybinds` runs,
screens get their events, and a mod listening on the way is listening on the same way.

```kotlin
client("steve") {
    press(Key.SPACE)                  // and keyDown / keyUp / type
    click(MouseButton.RIGHT)          // and mouseDown / mouseUp / scroll
    moveMouseTo(x, y, over = 400.milliseconds)   // or speed = pixelsPerSecond(500.0)

    breakBlock(pos)                   // holds attack until the block is gone
    useBlock(pos)
    attack()                          // one swing, so a test can space them itself
    chat("Alex! Leave the gold alone.")
}
```

Calling `MultiPlayerGameMode` directly would send the same packets while skipping every one of those
hooks, which is the part a test of a mod wants exercised.

**Real input is blocked** on an automated client, because it shares a keyboard with whoever is
watching it. `blockInput(false)` hands the window back when you want to drive it yourself.

Mouse movement takes time on purpose: a move is spread over ticks along an eased path, so a drag is
something you can watch and anything sampling the mouse per frame sees a plausible track.

### Screens and the inventory

```kotlin
client("steve") {
    playerInventory {                        // presses the inventory key; waits for the screen
        moveToSlot(InventorySlot.INV_1_1, pixelsPerSecond(500.0))
        click()                              // the sword is on the cursor
        moveToSlot(selectedHotbar, pixelsPerSecond(500.0))
        click()                              // and it lands

        swapSlot(INV_1_2, OFFHAND)           // those four calls, plus the wait
        assertSlot("the shield is in the offhand", OFFHAND) { it.item == Items.SHIELD }
    }
}
```

`awaitScreen<T>()` waits for a screen by class and says what was open instead when it times out.
Moving an item is **click, move, click**: holding the button is Minecraft's quick-craft gesture,
which spreads a carried stack over the slots it crosses and puts a single item back where it began.

Slots are named, never numbered, and carry both index spaces -- a menu counts from its crafting grid
and an `Inventory` counts from the hotbar -- so `INV_3_9` means the same square in a `server { }`
block handing out gear as in the `client { }` block that drags it.

### What is drawn over a client

A client has a stack of layers the framework owns, over Minecraft's own interface:

```kotlin
client("steve") {
    ui = false                       // just the world: no hotbar, hearts, chat or crosshair

    enableUiLayer(UiLayer.GUI) {     // and back, for one screenshot
        makeScreenshot("the hotbar")
    }
}
```

`UiLayer.GUI` is everything Minecraft draws for itself; `UiLayer.DEBUG` is reserved for debug
instruments and draws nothing yet. **An open screen is drawn regardless of the switch** -- dragging
across an invisible inventory would prove nothing and show less -- so opening one brings the
interface back for as long as it is open, with nothing to call.

**The cursor is not a layer.** It is not chrome a test chooses to show but the picture of what the
mouse is doing, so it is always drawn last and cannot be turned off:

- It appears **whenever a screen is open**, which is the only place a pointer means anything: in the
  world the mouse is grabbed and movement is camera rotation.
- The sprite is whichever cursor the interface asked for. Minecraft records a request as it draws --
  a pointing hand over a link, an I-beam over a text field -- and there is a counterpart for each of
  its nine cursors, drawn from its own hotspot so the picture sits where the click actually lands.
- Beside it a small mouse shows the buttons, **composed rather than pre-rendered**: two held at once
  fills both, a scroll flashes an arrow the way the wheel turned, and carrying a stack tints the
  whole glyph. Carrying is deliberately not drawn as a held button, because Minecraft moves an item
  with a click, a move and another click -- holding the button is the quick-craft gesture instead.

The sprites are generated by `tools/cursor-sprites.py`, so the art stays editable rather than being
fifteen binaries.

### Screenshots

```kotlin
client("alex") { makeScreenshot("I was wrong") }
```

lands in `build/reports/e2e/screenshots/<client>/<test>/<n>-<name>.jpg`, numbered in the order the
test took them and escaped on the way to the file system. It returns only once the file exists: the
capture is a GPU read-back that lands a few frames later, and a test that carried on regardless
would be photographing a scene it had already changed.

**A failing client block is photographed automatically**, and the report names the file under the
failure. A message saying a slot was empty is a puzzle; the same message beside a picture of the
inventory usually is not.

### Windows

`mcE2E { clientWidth = 1280; clientHeight = 720 }` -- Minecraft opens at 854x480, which is too small
to watch. `tileWindows = true` additionally has each client place its own window, using the ordinal
the orchestrator passes and the monitor size only the client can know.

## Who talks to whom

```
        orchestrator (plain JVM, no Minecraft)
        ├── TCP ──> dedicated server process    (mods: e2e, <yourmod>_e2e)
        ├── TCP ──> client process "steve"      (mods: e2e, <yourmod>_e2e)
        └── TCP ──> client process "alex"       (…one per name the suites mention)
```

The two game processes never open a socket to each other. A `client { }` raised inside a `server { }`
goes server → orchestrator → client and back, which is what gives one report a single ordering over
both.

If a game process dies mid-run, the orchestrator notices within a fraction of a second rather than
waiting out the call timeout, marks that test ERROR with the exit code and log path, restarts both
processes and carries on. **A restart resets the world**, so a suite whose later tests lean on what
earlier ones built will behave differently after one. Three crashes in a row abandons the run.

## Keeping the sides apart

`server { }` has a `ServerScope` receiver and `client { }` a `ClientScope`. The Minecraft accessors
are members of those types separately, so a server block cannot *name* a client-side value — the
split is enforced by the type system, not by convention:

| In a `server { }` | In a `client { }` |
| --- | --- |
| `minecraftServer`, `serverLevel` | `minecraft`, `clientLevel` |
| `serverPlayer`, `serverPlayers` | `clientPlayer`, `clientName` |

## The rules the compiler enforces

These are FIR checkers, so they appear under the cursor in the IDE as you type, not in a build log:

| Rule | Why |
| --- | --- |
| A test body holds only shared declarations and blocks | The body is never executed, so anything else would silently not run |
| A block may not reference an enclosing local unless it is `shared` | That local does not exist in the process the block runs in |
| A shared value may not be read inside a lambda that is not inlined | Reading one is a suspending call, impossible in a lambda compiled to its own function |
| A block body must be a lambda written in place | A function reference has no stable identity for the table |
| Suite and test names must be compile-time constants | They form part of the stable id |
| `shared<T>()` only as `var x by shared<T>()` inside an `e2e` block | One declaring scope, therefore one id |
| No `server`/`client` inside another lambda | Its ordinal would depend on how many times that lambda ran |
| No duplicate test or shared names in one scope | The two would collide on one id |
| A client name must be a string literal | The run starts the clients a suite names, and reads that list out of the compiled code |

Anything top-level or static is fine to reference: every node loads the same jars.

## Shared values

`shared` is a property delegate, so `target` is honestly typed `BlockPos`. The delegate never runs —
the plugin replaces every read and write with a suspending call to the orchestrator, which holds the
only authoritative copy.

Minecraft types have no `@Serializable`, but they do have Mojang codecs. `McValueCodec` encodes them
with the game's own `Codec` into NBT, writes that to bytes, and carries the bytes as a string inside
the same kotlinx-serialized envelope as everything else. `BlockPos`, `BlockState` and `ItemStack` are
registered; anything else falls through to plain kotlinx serialization, which keeps `shared<Int>()`
working.

## Stable ids

```
…BlocksKt:blocks                                          suite
…BlocksKt:blocks/a block moved                            test
…BlocksKt:blocks/a block moved/server[0]                  a step
…BlocksKt:blocks/a block moved/server[0]/client[alex][0]  a client block raised by the server
…BlocksKt:blocks/a block moved#target                     a shared value
```

Ordinals count per client name, so adding a client to a test cannot renumber another one's blocks,
and a report says who ran a block without anyone having to look it up.

Ordinals come from declaration order, so reformatting a file or editing an unrelated test leaves
every id alone. Renaming a suite or test *does* change its ids: the price of ids a person can read.
`server(id = "…")` pins one when that matters.

## Modules

| Module | What it is |
| --- | --- |
| `e2e-protocol` | The wire: ids, index, envelopes, transport, RPC. No Minecraft, because the orchestrator has none |
| `e2e-core` | The framework mod: the DSL, the Minecraft-typed scopes, codecs, node runner |
| `e2e-orchestrator` | The process that launches the games, relays between them, and reports |
| `e2e-compiler-plugin` | The K2 plugin: FIR checkers and the IR transform |
| `e2e-gradle-plugin` | An included build. Applies and configures everything, adds `runE2eTests` |
| `e2e-example` | A consumer: the plugins block, the `mcE2E` block, and one suite |

`e2e-core` and `e2e-protocol` deliberately do not share a package. FancyModLoader builds a real
module graph, and two jars cannot both export one package to it.

## Not built yet

- **Publishing.** The Gradle plugin resolves the compiler plugin and orchestrator from configurations
  that default to published coordinates; nothing is published yet, so the example points them at its
  own projects.
- **Debug instruments.** `UiLayer.DEBUG` exists and is drawn in the right place; nothing draws in it
  yet, and neither does anything identify which window is which.
- **Block return values.** `dispatch` returns nothing; blocks communicate only through `shared`.
- **World fixtures.** The server run is seeded with a flat world and an accepted EULA, nothing more.
