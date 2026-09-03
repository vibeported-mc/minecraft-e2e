# :rpc:compiler-plugin

The K2 plugin. One FIR checker enforcing four rules under the cursor, and an IR pass that lifts each
body into a table and writes the manifest naming it.

Two halves because they answer different questions. The frontend decides whether a call is written
legally, and must do so in the editor as you type; the backend does the tier splitting, and needs
types and bodies the frontend has not finished with. They share one object, `RoleIndex`, for the one
fact that cannot cross on its own.

## What is in it

| | |
|---|---|
| `RpcCompilerPluginRegistrar.kt` | Creates one `RoleIndex` per compilation and hands it to both halves |
| `RpcCommandLineProcessor.kt` | Two options: `manifestDir`, and `enabled` |
| `RoleIndex.kt` | Where a role written on a lambda is written down for the backend, keyed by package, file and **call** offset |
| `fir/EntryPoints.kt` | Finding the arguments that are procedure bodies: parameters marked `@RpcLift`, seeing past the conversion the frontend inserts |
| `fir/RpcCallChecker.kt` | Every rule: no captures, the body must be written here or forwarded from an `@RpcLift` parameter, it may not be run where it stands, and its types must serialize |
| `fir/Serializability.kt` | Whether a type can cross a wire, decided while it is still under the cursor |
| `fir/RpcDiagnostics.kt` | The four errors, and their messages |
| `ir/RpcPlanner.kt`, `ir/RpcPlan.kt` | What each file contributes: an id, a role, the argument and result types, read off the lambda |
| `ir/RpcTransformer.kt` | Builds the table classes, re-parents the bodies into them, and rewrites the call sites |
| `ir/RpcSymbols.kt` | The runtime declarations the generated code refers to |
| `ir/ManifestWriter.kt` | `META-INF/rpc/procedures.json` |

## Four decisions that are load-bearing

**The body is moved, not copied.** The function the frontend already built for the lambda is
re-parented into the table, so every symbol inside it stays valid with nothing remapped -- and it
stops being a closure simply because it is no longer nested in one. Lifting and rewriting the call
site have to happen in one pass; a re-parented lambda whose call site still points at it is an orphan
the backend rejects with `No dispatch receiver allowed in wrappers`.

**`RoleIndex` is per compilation, never a singleton.** An expression-target annotation is forced to
`SOURCE` retention, so `@RpcRole` reaches FIR and is gone by the time the backend runs -- hence the
index. A shared object would outlive every compilation in a Gradle daemon and let one module read
another's roles. `CompilationIsolationTest` compiles two modules at once and fails against the
singleton version.

**The index is keyed by the call, not the lambda.** FIR and IR agree on where a call starts and
disagree about where an annotated lambda does -- one counts from the annotation, the other from the
brace. Only the call site is spelled the same on both sides.

**Serializability is decided in the frontend.** The types are still in view there, so an argument
nothing can encode is a message on the declaration that named it. Deciding it in the backend would
have been easier and would have produced a build-log line; deciding it at run time, as the framework
this replaces did, produced a test that failed halfway through for returning a `java.io.File`.
