# minecraft-e2e

End-to-end tests for NeoForge mods that run against a **real dedicated server and a real client**, in
separate processes, driven by an orchestrator.

```kotlin
val blocks = suite("blocks") {

    e2e("a block placed in front of the player shows up on the client") {
        var target by shared<BlockPos>()

        server {
            val player = serverPlayer ?: error("nobody had joined the server")
            val front = player.blockPosition().relative(player.direction, 2)
            serverLevel.setBlockAndUpdate(front, Blocks.GOLD_BLOCK.defaultBlockState())

            target = front
            log("placed a gold block at $front")
        }

        client {
            val expected = target
            delay(3.seconds)
            val seen = clientLevel?.getBlockState(expected)?.block
            assertThat("the client should see the gold block") { seen == Blocks.GOLD_BLOCK }
        }
    }
}
```

That test passes today against Minecraft 26.2 / NeoForge 26.2.0.69:

```
PASS blocks > a block placed in front of the player shows up on the client  (8108 ms)
    log:
      [server]    placed a gold block at BlockPos{x=9, y=-60, z=-1}
      [client[0]] the client sees Block{minecraft:gold_block} at BlockPos{x=9, y=-60, z=-1}
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

That launches a dedicated server, launches a client which joins it, runs every suite, prints a report
and writes `build/reports/e2e/report.json`. Each game process's console is captured under
`build/reports/e2e/logs/`, alongside the exact command used to start it.

The plugin applies Kotlin, serialization and ModDevGradle; creates the `e2eTest` source set and gives
it Minecraft; generates that mod's `neoforge.mods.toml`; applies the compiler plugin to it; registers
the two runs and seeds their directories. `neoForge { }` is ModDevGradle's own extension rather than
a wrapper, so anything the plugin has not thought to expose is still reachable, and nothing here has
to be kept in step with ModDevGradle as it changes.

## A test is a plan, not code

A test body may contain **only** shared declarations and `server`/`client` calls. Anything else is a
compile error, because there is nowhere for it to run:

```kotlin
e2e("...") {
    var target by shared<BlockPos>()   // allowed
    server { … }                       // allowed
    client { … }                       // allowed
    println("hello")                   // compile error
}
```

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

## Who talks to whom

```
        orchestrator (plain JVM, no Minecraft)
        ├── TCP ──> dedicated server process   (mods: e2e, <yourmod>_e2e)
        └── TCP ──> client process             (mods: e2e, <yourmod>_e2e)
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
| `serverPlayer`, `serverPlayers` | `clientPlayer`, `clientIndex` |

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
…BlocksKt:blocks                                    suite
…BlocksKt:blocks/a block moved                      test
…BlocksKt:blocks/a block moved/server[0]            a step
…BlocksKt:blocks/a block moved/server[0]/client[0]  a client block raised by the server
…BlocksKt:blocks/a block moved#target               a shared value
```

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

- **More than one client.** The plan carries a list and the ids already have a client index, but the
  plugin launches a single client.
- **Publishing.** The Gradle plugin resolves the compiler plugin and orchestrator from configurations
  that default to published coordinates; nothing is published yet, so the example points them at its
  own projects.
- **Block return values.** `dispatch` returns nothing; blocks communicate only through `shared`.
- **World fixtures.** The server run is seeded with a flat world and an accepted EULA, nothing more.
