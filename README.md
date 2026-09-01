# minecraft-e2e

An end-to-end test framework for Minecraft: an orchestrator drives a real server and one or more
real clients, dispatching test steps to each, capturing their logs, and producing one report.

Today the nodes are coroutines in a single JVM. They still only reach each other through the RPC
layer, so nothing above the transport knows the difference, and making them separate processes is
meant to be a change to one factory.

```kotlin
val movement = suite("movement") {

    e2e("block moved") {
        var pos by shared<BlockPos>()

        server {
            pos = BlockPos(1, 2, 3)
            serverWorld.setBlock(pos, Block.STONE)
            serverWorld.sync()
        }

        client {
            clientWorld.awaitBlock(pos, Block.STONE)
            assertThat("the client should see the stone the server placed") {
                world.getBlock(pos) == Block.STONE
            }
        }
    }
}
```

## The problem this solves

`server { }` and `client { }` run in a different process than the code that lexically contains them.
An ordinary Kotlin lambda cannot go there: it is a closure over locals that do not exist on the
other side.

So a Kotlin compiler plugin lifts every block body out of its closure into a generated, statically
identified dispatch table, rewrites `shared` access into RPC against the orchestrator, and refuses
to compile a block that references anything which could not survive the trip.

### Before

```kotlin
server {
    pos = BlockPos(1, 2, 3)
}
```

### After (in effect)

```kotlin
// at the call site, in the test driver:
scope.dispatch(BlockId("…/block moved/server[0]"), NodeId(SERVER, 0))

// and, in a generated object beside the file facade:
internal object E2eBlocks_MovementKt : E2eBlockTable {
    override suspend fun invoke(id: String, scope: BlockScope): Any? {
        if (id == "…/block moved/server[0]") return b1_server_0_(scope)
        …
        throw NoSuchBlockException(BlockId(id), "…E2eBlocks_MovementKt")
    }

    private suspend fun b1_server_0_(scope: NodeScope) {
        scope.sharedSet(SharedId("…/block moved#pos"), BlockPos::class, BlockPos(1, 2, 3))
    }
}
```

The lambda is never serialized. Only its id travels.

The body is *moved*, not copied: the plugin re-parents the very function the frontend built for the
lambda, so every symbol inside it stays valid and the lambda stops being a closure simply because it
is no longer nested in one.

## Stable ids

Structural, and readable because reports print them:

```
dev.example.MovementKt:movement                                  suite
dev.example.MovementKt:movement/block moved                      test
dev.example.MovementKt:movement/block moved/driver               the e2e body, run by the orchestrator
dev.example.MovementKt:movement/block moved/server[0]
dev.example.MovementKt:movement/block moved/server[0]/client[0]  a client block raised by the server
dev.example.MovementKt:movement/block moved#pos                  a shared value
```

Ordinals come from declaration order within the enclosing block, so reformatting a file, or editing
an unrelated test in it, leaves every id alone. Renaming a suite or test *does* change its ids: that
is the price of ids a person can read, and `server(id = "…")` pins one when it matters.

## The rules, and why

The compiler enforces these, as errors, in the IDE as you type:

| Rule | Why |
| --- | --- |
| A block may not reference an enclosing local unless it is `shared` | That local does not exist in the process the block runs in |
| A block body must be a lambda written in place | A function reference has no stable identity to put in the table |
| Suite and test names must be compile-time constants | They form part of the stable id |
| `shared<T>()` only as `var x by shared<T>()` inside an `e2e` block | One declaring scope, therefore one id |
| No `server`/`client` inside another lambda | Its ordinal would depend on how many times that lambda ran |

Anything top-level or static is fine to reference: every node loads the same jar.

`shared` is a property delegate, so `pos` is honestly typed `BlockPos`. The delegate never runs — the
plugin replaces every read and write of it with a suspending call to the orchestrator, which owns
the only authoritative copy.

## Modules

| Module | What it is |
| --- | --- |
| `e2e-api` | The DSL and the contracts the generated code calls |
| `e2e-compiler-plugin` | The K2 plugin: FIR checkers and the IR transform |
| `e2e-gradle-plugin` | Applies the compiler plugin and wires up the generated index |
| `e2e-runtime` | RPC, orchestrator, node runners, reporting |
| `e2e-mock-world` | A deliberately small stand-in world, for probing the API surface |
| `e2e-samples` | Runnable samples, and the plugin dogfooding itself |

## Running it

```sh
./gradlew build            # everything, with tests
./gradlew :e2e-samples:runE2e
```

`runE2e` prints a report and writes `e2e-samples/build/reports/e2e/report.json`.

To see the transform itself:

```sh
javap -p -c -cp e2e-samples/build/classes/kotlin/main \
    dev.vibeported.mc.e2e.samples.E2eBlocks_MovementKt
```

## How discovery works

The plugin writes `META-INF/e2e/index.json`: every suite, every block, its role, its parent, and the
generated table that owns it. The orchestrator reads it to build a run plan without loading a single
test body; a node handed a bare block id uses it to find the one class that can run it.

Each compiled file also gets a part file under `META-INF/e2e/parts/`, and the index is rebuilt from
those. Incremental compilation only hands the plugin the sources that changed, and rebuilding from
the parts on disk is what stops entries for untouched files vanishing from the index.

## What is not built yet

- **Separate processes.** `Transport` is the only thing that knows nodes are not separate, and
  `InMemoryHub` is its only implementation. It still encodes every envelope to text and back, so a
  payload that could not cross a real wire fails now, in a unit test, rather than later.
- **Minecraft.** `NodeScope.facility<T>()` is the seam: the samples put a mock world behind it, and
  a real deployment would put a `MinecraftServer` or `Minecraft` instance there instead.
- **Codecs for game types.** Shared values go through `ValueCodec`, currently
  `kotlinx.serialization`. Vanilla types will want their own codecs behind that same interface.
- **Block return values.** `dispatch` returns nothing; a block communicates only through `shared`.
