# minecraft-e2e

End-to-end tests for NeoForge mods that run against a **real dedicated server and a real client**, in
separate processes, driven by an orchestrator.

```kotlin
val blocks = suite("blocks") {

    e2e("a block placed in front of the player shows up on the client") {
        var target by shared<BlockPos>()

        server {
            val placed = onServer {
                val player = playerList.players.first()
                val front = player.blockPosition().relative(player.direction, 2)
                overworld().setBlockAndUpdate(front, Blocks.GOLD_BLOCK.defaultBlockState())
                front
            }
            target = placed
            log("placed a gold block at $placed")
        }

        client {
            val expected = target
            delay(3.seconds)
            val seen = onClient { level?.getBlockState(expected)?.block }
            assertThat("the client should see the gold block") { seen == Blocks.GOLD_BLOCK }
        }
    }
}
```

That test passes today against Minecraft 26.2 / NeoForge 26.2.0.69:

```
PASS blocks > a block placed in front of the player shows up on the client  (8107 ms)
    log:
      [server]    placed a gold block at BlockPos{x=-8, y=-60, z=-5}
      [client[0]] the client sees Block{minecraft:gold_block} at BlockPos{x=-8, y=-60, z=-5}
```

## Running it

```sh
./gradlew :e2e-mc:runE2e
```

That launches a dedicated server, launches a client which joins it, runs every suite, prints a
report and writes `e2e-mc/build/reports/e2e/report.json`. Each game process's console is captured
under `build/reports/e2e/logs/`, alongside the exact command used to start it.

## How a block gets to the other process

`server { }` and `client { }` run in different JVMs than the code that lexically contains them. An
ordinary Kotlin lambda cannot go there: it is a closure over locals that do not exist on the other
side.

So a K2 compiler plugin lifts every block body out of its closure into a generated dispatch table
keyed by a stable structural id, and rewrites `shared` reads and writes into RPC:

```kotlin
// at the call site, in the test driver:
scope.dispatch(BlockId("…/server[0]"), NodeId(SERVER, 0))

// and, in a generated object beside the file facade:
internal object E2eBlocks_BlocksKt : E2eBlockTable {
    override suspend fun invoke(id: String, scope: BlockScope): Any? {
        if (id == "…/server[0]") return b1_server_0_(scope)
        …
    }
}
```

The lambda is never serialized; only its id travels. The body is *moved* rather than copied — the
function the frontend already built for the lambda is re-parented into the table — so every symbol
inside it stays valid, and it stops being a closure because it is no longer nested in one.

## Who talks to whom

```
        orchestrator (plain JVM, no Minecraft)
        ├── TCP ──> dedicated server process   (mod e2e + e2e_tests)
        └── TCP ──> client process             (mod e2e + e2e_tests)
```

The two game processes never open a socket to each other. A `client { }` raised inside a
`server { }` goes server → orchestrator → client and back, which is what gives one report a single
ordering over both, and what makes the relay the only thing that needs replacing to add more clients.

The orchestrator runs no test code: the lifted bodies live in a mod jar compiled against Minecraft
and it has no game on its classpath. Even a test's driver is dispatched to the server node. It plans
the run entirely from `META-INF/e2e/index.json`, which the compiler plugin wrote.

## Keeping the sides apart

`server { }` has a `ServerScope` receiver and `client { }` a `ClientScope`. The Minecraft accessors
hang off those types separately, so a server block cannot *name* a client-side value — the split is
enforced by the type system, not by convention:

| In a `server { }` | In a `client { }` |
| --- | --- |
| `server: MinecraftServer` | `minecraft: Minecraft` |
| `onServer { }`, `overworld()`, `firstPlayer()` | `onClient { }`, `clientLevel()`, `localPlayer()` |

Everything touching the world goes through `onServer`/`onClient`, which hop to the game thread: a
block body runs on a coroutine so it can suspend on the orchestrator, and is therefore never on the
game thread itself.

## The rules the compiler enforces

These are FIR checkers, so they appear under the cursor in the IDE as you type, not in a build log:

| Rule | Why |
| --- | --- |
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
registered; anything else falls through to plain kotlinx serialization, which is what keeps
`shared<Int>()` working.

## Stable ids

```
…BlocksKt:blocks                                    suite
…BlocksKt:blocks/a block moved                      test
…BlocksKt:blocks/a block moved/driver               runs on the server
…BlocksKt:blocks/a block moved/server[0]
…BlocksKt:blocks/a block moved/server[0]/client[0]  a client block raised by the server
…BlocksKt:blocks/a block moved#target               a shared value
```

Ordinals come from declaration order within the enclosing block, so reformatting a file or editing an
unrelated test leaves every id alone. Renaming a suite or test *does* change its ids: the price of
ids a person can read. `server(id = "…")` pins one when that matters.

## Modules

| Module | What it is |
| --- | --- |
| `e2e-api` | The DSL and the contracts the generated code calls. No Minecraft, so its tests stay fast |
| `e2e-compiler-plugin` | The K2 plugin: FIR checkers and the IR transform |
| `e2e-runtime` | RPC, socket transport, orchestrator, node runners, reporting, process launcher |
| `e2e-mc` | The framework mod, the Minecraft accessors and codecs, and `src/tests` as a second mod |
| `e2e-gradle-plugin` | An included build. Harvests launch commands from ModDevGradle and adds `runE2e` |

## How the launch works

The Gradle plugin deliberately does **not** register the Minecraft runs. Those are ordinary
ModDevGradle runs the build declares (`e2eServer`, `e2eClient`), and the plugin only reads the
command lines ModDevGradle already worked out for them — its run task is a plain `JavaExec` whose
`exec()` adds nothing but classpath, working directory and environment. A ModDevGradle version bump
therefore cannot silently change how the game is launched here.

Two details that bite:

- ModDevGradle passes its VM args as an `@argfile` whose path is escaped for Gradle's *own* argfile.
  Handed straight to a process that escaping is wrong, and the JVM quietly stops expanding the file
  and reads the leftover token as the main class. The launcher un-escapes it.
- The classpath runs to ~140 entries, past the Windows command line limit, so it goes into an
  argument file of our own — and only it, because the JVM will not expand one argfile from inside
  another.

## Not built yet

- **More than one client.** The plan carries a list and the ids already have a client index, but the
  build declares a single client run.
- **Publishing.** The Gradle plugin applies the compiler plugin nowhere; `e2e-mc` wires it with
  `-Xplugin` from the project output. An external consumer needs that combined and published.
- **Block return values.** `dispatch` returns nothing; blocks communicate only through `shared`.
- **World setup.** The server run is seeded with a flat-world `server.properties` and an accepted
  EULA; there is no per-suite world fixture yet.
