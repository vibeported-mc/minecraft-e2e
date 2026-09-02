# :core

The framework mod. Everything a game process needs to be a node in a test run, and nothing that
knows what a test is.

Loaded by FancyModLoader on both sides, so it holds the parts a dedicated server and a client both
need: the calls a suite writes, the scopes a lifted block sees, the transport underneath, and the
runner that answers when the orchestrator asks for a block.

## What is in it

| | |
|---|---|
| `Calls.kt` | `server { }` and `client { }`. **Every one throws.** They exist to be rewritten by the compiler plugin; a call that reaches the body means the plugin was not applied |
| `Dispatch.kt` | `invokeProcedure` -- what those calls become. Decides between a direct call and a round trip, which is what lets one source line mean "just run it" on the node that owns it |
| `Scopes.kt` | What a block body can reach: `ProcedureScope`, `NodeScope`, `ServerScope`, `ClientScope`. The Minecraft-typed accessors live here |
| `ProcedureTable.kt` | The interface the generated per-file tables implement |
| `Node.kt`, `node/` | The runner: takes a request, finds the table, decodes the arguments, runs the body on the game thread, encodes the result |
| `protocol/` | Ids, the generated index, assertion failures. No Minecraft |
| `rpc/` | Envelopes, the peer, socket and in-memory transports, the value codec |
| `mc/` | The bits that need a game: the mod entry point, the game-thread dispatcher, the tick clock, a codec that can encode Minecraft types |

## Two things worth knowing

**The table is split by role.** The plugin emits a server table and a client table per file, not one
of each. A dedicated server is dist-cleaned, so client classes are not on its classpath at all, and a
single table naming both would be a class it could not verify.

**Arguments are decoded by the generated code, not by the node.** Only the generated table knows what
each parameter was declared as; the node has a list of JSON values and no idea what any of them mean.
That is why `ProcedureTable` carries `decodeArgs` and `encodeResult` rather than the runner doing it.

## Package layout

`core` and the `protocol` package deliberately do not share a package name with anything else in the
tree. FancyModLoader builds a real module graph, and two jars cannot both export one package to it.
