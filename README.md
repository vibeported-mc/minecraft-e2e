# minecraft-e2e

End-to-end tests for NeoForge mods that run against a **real dedicated server and real clients**, in
separate processes, driven by an orchestrator.

```kotlin
val blocks = suite("blocks") {

    e2e("two players fly to a block, watch it, then watch each other") {
        // A block returns a value to the caller, so a fixture needs no shared state.
        val target = server {
            waitForPlayer("steve")
            waitForPlayer("alex")
            build { at(FAR_AWAY) { minecraft.gold_block } }
            FAR_AWAY
        }

        // Both clients at once: ordinary structured concurrency, because a test body is
        // ordinary code.
        coroutineScope {
            launch { watchTheBlock("steve", "alex", target, target.offset(-3, 4, -3)) }
            launch { watchTheBlock("alex", "steve", target, target.offset(3, 4, 3)) }
        }

        server(target) { pos ->
            assertBlock("the server should still have the block it placed", pos) {
                it.block == Blocks.GOLD_BLOCK
            }
        }
    }
}

private suspend fun watchTheBlock(who: String, other: String, block: BlockPos, from: BlockPos) {
    teleport(who, from, flying = true)
    client(who, block) { pos ->
        assertBlock("$who should see the gold block", pos, timeoutSec(10)) {
            it.block == Blocks.GOLD_BLOCK
        }
    }
    lookAt(who, block)
    delay(5.seconds)
    lookAtPlayer(who, other)
}
```

That test passes today against Minecraft 26.2 / NeoForge 26.2.0.69, with two real client processes
running the two halves of that `coroutineScope` at once:

```
PASS blocks > two players fly to a block, watch it, then watch each other  (10450 ms)
    log:
      [server]        placed a gold block at BlockPos{x=100, y=200, z=200}
      [client[steve]] alex is at BlockPos{x=103, y=204, z=203}
      [client[alex]]  steve is at BlockPos{x=97, y=204, z=197}
```

Tests can also **record themselves**: `record("alex", "fight.mp4") { ... }` films a client straight
off the GPU through NVENC, without the frame ever reaching the CPU. See
[Screen recording](#screen-recording).
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

## Diagnostics in the IDE

The rules below are FIR checkers, so the compiler enforces them on every build. Getting them under
the cursor as you type takes one step per IDE, because neither runs a compiler plugin it does not
itself ship: the IDE analyses Kotlin with its own bundled compiler, and a plugin built against a
different one could misbehave inside it. That is a real risk rather than a formality, so treat what
follows as a convenience and the build as the source of truth.

**IntelliJ IDEA.** Turn off the registry key that restricts analysis to bundled plugins:
<kbd>Help</kbd> > <kbd>Find Action</kbd> > <kbd>Registry</kbd>, then clear
`kotlin.k2.only.bundled.compiler.plugins.enabled`. Nothing else is needed.

**VS Code**, with [Java and Kotlin by IntelliJ IDEA](https://marketplace.visualstudio.com/items?itemName=JetBrains.intellij-server).
That extension hardcodes the same flag, so there is no setting to change (JetBrains issue
IDEA-393372). `tools/lsp-fir-agent` is a small java agent that rewrites it; build it once and pass
it to the language server:

```sh
./gradlew -p tools/lsp-fir-agent jar
```

```jsonc
// .code-workspace, or .vscode/settings.json in a single-folder workspace
"intellij.additionalJvmArgs": [
    "-javaagent:/absolute/path/to/minecraft-e2e/tools/lsp-fir-agent/build/libs/lsp-fir-agent.jar"
]
```

The agent announces itself on stderr, which lands in the language server's log; `-De2e.agent.disabled=true`
turns it off again without removing the argument.

Either way it may simply not work, and it may work and then stop working after an IDE update. If the
editor is quiet, compile — a rule that fires in the build and not in the editor is this, not your code:

```sh
./gradlew compileE2eTestKotlin
```

This is also why the Gradle plugin puts the compiler plugin on the `main` compilation as well as on
the suites: the VS Code server reads it only from `main`, and `main` holds no procedures, so it
costs nothing.

## Building a world

A block is an id plus properties whose legal values differ per block, and spelling that as a string
means a typo is discovered as a test failing somewhere else. Turn the block DSL on and the whole of
it is checked by the compiler:

```kotlin
mcE2E {
    blockDsl { enable() }
}
```

```kotlin
server {
    build(FAR_AWAY) {
        at(0, 0, 0) { minecraft.hopper { facing = down } }
        at(0, 1, 0) { minecraft.bamboo_mosaic_stairs { facing = north; half = top } }
        at(1, 0, 0) { minecraft.stone }
        fill(-1..1, -1..-1, -1..1) { minecraft.stone }
    }
}
```

`down`, `north` and `top` are members of that block's own builder, so completion offers exactly the
values that property accepts and nothing else. A hopper's `facing` has no `up`; a stair's has no
`up` or `down` either; `minecraft.stone { }` takes no properties at all. Each of those is a compile
error at the character, not a block quietly missing from the world.

`build` takes an origin and the coordinates inside are offsets from it, so a fixture moves by
changing one line. Left out, the origin is the world origin and the coordinates read as absolute.

Blocks are placed with `UPDATE_KNOWN_SHAPE`, so a neighbour cannot recompute the properties the test
just named -- without it a stair asked for `shape = straight` becomes a corner because of what was
placed beside it.

Nothing here suspends. `build` is an ordinary extension on `ServerScope` that runs inside a
`server { }` you already had, so a hundred-block fixture costs one visit to the game thread rather
than a hundred ticks.

### What generating it costs

Off unless `blockDsl { enable() }` says otherwise, and off costs nothing: no task, no source
directory, no game.

On, a build starts FancyModLoader and loads every mod to read the block registry -- because which
blocks exist, and what each property accepts, are answers only a running game has. It runs when the
IDE syncs, so the names are there when the project opens, and again from `compileE2eTest` for a
headless build. **The first sync after enabling it is noticeably slower**; after that it reruns only
when the mod set or the NeoForge version changes.

`namespaces = listOf("minecraft", "mymod")` narrows what is generated, which is worth reaching for
only if compiling the result starts to cost. Everything is the default, because a name that is never
generated is a name a suite cannot write.

## A test body is ordinary code

It used to be a plan -- a declarative list of steps the compiler read and threw away. It is not any
more. A body is Kotlin, and the blocks inside it are what get lifted:

```kotlin
e2e("two players fly to a block, watch it, then watch each other") {
    val target = server { ... }              // a block, lifted, returns a value

    coroutineScope {                          // ordinary structured concurrency
        launch { watchTheBlock("steve", "alex", target) }
        launch { watchTheBlock("alex", "steve", target) }
    }
}
```

There is no `parallel { }`, because there is nothing left for it to do: two clients that should act
at once are two `launch`es. A block written inside a loop, a helper function or another lambda is
fine -- see [Stable ids](#stable-ids) for why that costs nothing.

## How a block gets to the other process

`server { }` and `client { }` run in different JVMs than the code that lexically contains them. An
ordinary Kotlin lambda cannot go there: it is a closure over locals that do not exist on the other
side.

So a K2 compiler plugin lifts every block body out of its closure into a generated dispatch table
keyed by a stable id, and rewrites the call site into a dispatch:

```kotlin
// at the call site:
invokeProcedure(id = "...BlocksKt.blocks/server[0]", target = NodeId(SERVER), args = listOf(...), ...)

// and, in a generated object beside the file facade:
internal object ServerProcedures_BlocksKt : ProcedureTable {
    override suspend fun invoke(id: String, scope: Any, args: List<Any?>): Any? {
        if (id == "...BlocksKt.blocks/server[0]") return b0_server_0_(scope)
        ...
    }
}
```

The lambda is never serialized; only its id and its arguments travel. The body is *moved* rather than
copied -- the function the frontend already built for the lambda is re-parented into the table -- so
every symbol inside it stays valid, and it stops being a closure because it is no longer nested in
one.

There are two tables per file, a server one and a client one. A dedicated server is dist-cleaned, so
client classes are not on its classpath at all, and one table naming both would be a class it could
not verify.
## Blocks run on the game thread

A block body is dispatched onto its process's event loop, so **every Minecraft call in it is safe
with no wrapper**. Awaiting inside one -- a nested `client { }`, an assertion that waits, a
`delay` -- releases the loop, so the game keeps ticking and the block resumes back on it.

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
- Beside it a small mouse shows the buttons, and it is drawn **whether or not a screen is open** --
  mining holds attack for seconds, and out there the glyph parks by the crosshair.
- What it shows is the **physical state of the input**: what the framework pressed and has not yet
  released, held for exactly as long as it is held. Not Minecraft's opinion of it, which only tracks
  buttons while no screen is open and is an interpretation even then.
- Indicators are **composed rather than pre-rendered**, so two buttons held at once fills both, and
  they **fade out** over about half a second rather than blinking away -- a one-tick click is three
  frames, gone before anyone watching registers it.

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

### Screen recording

```kotlin
e2e("two players fight") {
    record("alex", "fight.mp4", RecordingOptions(fps = 30, codec = VideoCodec.H264)) {
        client("alex") { press(Key.W) }
        server { ... }
    }
}
```

The body is ordinary test DSL and exactly it is what ends up in
`build/reports/e2e/recordings/alex/fight.mp4`. The recording stops before the block returns -- so the
file is closed and complete by the next line -- and it stops even when the body throws, which is the
run most worth having the video of. Recording is per client: name a second one in a second `record`
to film both.

`RecordingOptions` carries `fps`, `codec` (`H264`, `HEVC` or `AV1`, all NVENC), `frameBufferSize`,
`quality` and `preset`. It crosses to the client as a block argument, like any other value a block
is given.

**The frame never reaches the CPU.** Minecraft's main render target is a `GL_RGBA8` texture, whose
bytes are precisely what NVENC accepts as packed 32-bit RGB. Each recorded frame is flipped the right
way up with one `glBlitFramebuffer` (OpenGL's origin is bottom left, video's is top left -- the same
reason Minecraft's screenshot code flips, except this happens on the GPU), copied device to device
into the encoder's own memory, and encoded there. No `glReadPixels`, no colour conversion, no read
back.

**The game is never made to wait for the encoder.** `fps` is the rate of the *recording*, not of the
game: a frame is taken only when the recording's clock says one is due, so a client rendering at 300
frames a second does not encode 300 of them. Rendering slower than that instead makes timestamps
jump, so the player holds the previous frame and a nine second test yields nine seconds of video with
the stall where it happened. Capture and encoding are on separate threads, and if the encoder still
falls behind, frames are dropped and counted in the log rather than queued -- the recording is allowed
to be worse, the test it is recording is not allowed to be slower.

Needs an NVIDIA GPU on the machine running the tests, and the first build after `:capture` joins
the tree cross-compiles FFmpeg in Docker. Without a GPU the recording is refused with the reason in
the client's log and the test carries on.

Recordings are written as fragmented MP4, so a client the orchestrator terminates still leaves a
playable file rather than one with no index.

### Animating over ticks

```kotlin
server {
    var remaining = 40
    serverTickLoop {
        nudgeSomething()
        remaining-- > 0      // false ends the loop and returns
    }
}
```

The unit of animation on a node: the body computes what this tick should look like and says whether
it wants another. It runs on the game thread and the wait between calls hands that thread back, so
the game ticks normally while the loop runs. `maxTicks` is a guard rather than a schedule -- a body
that never returns `false` would otherwise hang the run with nothing to show.

Built on it, and the reason it exists:

```kotlin
record("alex", "circling.mp4") {
    orbitPlayer("alex", around = "steve", overTicks = 200)
}
```

Alex flies one full circle around steve over ten seconds, watching him the whole way. Every tick
computes the point on the circle and the yaw and pitch to look at steve from *that* point -- aiming
from where the eyes are about to be rather than where they are, because a camera that arrives a tick
after the body it is attached to wobbles. Position and rotation go in one movement, with the velocity
zeroed, so nothing the client thinks it is doing fights the path.

The circle starts wherever the orbiting player already stands, so there is no jump into it, and it
keeps whatever distance the two were already at unless told otherwise. Being server-driven, it is the
same path on every machine however fast that client renders -- which is what makes one recording
worth comparing against another.

Motion is at tick rate, twenty new camera positions a second, interpolated by nothing: that is what
server-driven means. Spread over enough ticks each step is too small to read as a step -- the default
200 turns about 1.8 degrees a tick.

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

Two, both FIR checkers, so they appear under the cursor as you type once the IDE is set up -- see
[Diagnostics in the IDE](#diagnostics-in-the-ide).

| Rule | Why |
| --- | --- |
| A block may not capture an enclosing local | That local does not exist in the process the block runs in. Pass it as an argument -- every block takes up to ten -- or declare it inside |
| A block body must be a lambda written in place | A function reference, or a lambda held in a variable, has no stable identity to give the dispatch table |

Anything top-level or static is fine to reference: every node loads the same jars.

That is the whole list. Blocks in loops, in `forEach`, in helper functions and inside other lambdas
are all fine, because an id describes *where a block was written* rather than how many times it runs.

## Values that cross

Arguments in, a return value out:

```kotlin
val target = server {                       // returns a BlockPos to the caller
    build { at(FAR_AWAY) { minecraft.gold_block } }
    FAR_AWAY
}

client("alex", target) { pos ->             // and takes it as an argument
    lookAt(pos)
}
```

Minecraft types have no `@Serializable`, but they do have Mojang codecs. `McValueCodec` encodes them
with the game's own `Codec` into NBT, writes that to bytes, and carries the bytes as a string inside
the same kotlinx-serialized envelope as everything else. `BlockPos`, `BlockState` and `ItemStack` are
registered; anything else falls through to plain kotlinx serialization.

## Stable ids

```
dev.vibeported.mc.e2e.tests.BlocksKt.blocks/server[0]          a block
dev.vibeported.mc.e2e.tests.BlocksKt.blocks/client[alex][0]    a client block
dev.vibeported.mc.e2e.tests.BlocksKt.blocks/client[*][0]       one whose client is not a literal
```

The facade class, then the declaration the call sits in, then a per-role ordinal in source order.
Lexical, not structural: the id says where a block was *written*.

One process compiles the call site and another looks it up, so an id has to survive a rebuild on
another machine. Ordinals count per role and per client name, so adding a client cannot renumber
another one's blocks. Reformatting a file or editing an unrelated test leaves every id alone; moving
a block to a different declaration changes its id. `server(id = "...")` pins one when that matters.

## Modules

Each has a README of its own.

| Module | What it is |
| --- | --- |
| [`:core`](core/README.md) | The framework mod: the calls, the Minecraft-typed scopes, the transport, the node runner |
| [`:dsl`](dsl/README.md) | The verbs -- teleport, click, assert, screenshot, record -- built out of the same calls a suite uses |
| [`:suite`](suite/README.md) | A driver: what a test is, how long it may take, what a report looks like |
| [`:orchestrator`](orchestrator/README.md) | Launches the games, relays between them. No Minecraft on its classpath |
| [`:compiler-plugin`](compiler-plugin/README.md) | The K2 plugin: two FIR checkers and the IR transform that lifts blocks |
| [`:codegen`](codegen/README.md) | A Kotlin name for every block the game loads, generated by starting it |
| [`:gradle-plugin`](gradle-plugin/README.md) | An included build. Applies and configures everything, adds `runE2eTests` |
| [`:capture`](capture/README.md) | FFmpeg cross-built for Windows, its Panama bindings, and the recorder that feeds NVENC |
| [`:example`](example/README.md) | A consumer: a plugins block, an `mcE2E` block, and one suite |

`:capture` carries a Docker step -- it cross-compiles FFmpeg and generates its own bindings -- but
that is a task like any other, with the same inputs and up-to-date checks, so `gradlew build` drives
it along with everything else. Its three modules build on Java 25 rather than the 21 the rest of the
tree uses: the FFM API the bindings are made of only became final in 22, and the root
`subprojects { }` block would otherwise hand them 21.

`:core` keeps its `protocol` package to itself. FancyModLoader builds a real module graph, and two
jars cannot both export one package to it.

## Not built yet

- **Publishing.** The Gradle plugin resolves the compiler plugin and orchestrator from configurations
  that default to published coordinates; nothing is published yet, so the example points them at its
  own projects.
- **Debug instruments.** `UiLayer.DEBUG` exists and is drawn in the right place; nothing draws in it
  yet, and neither does anything identify which window is which.
