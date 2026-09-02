# :compiler-plugin

A K2 compiler plugin, in two halves: checkers that reject what cannot work, and a transform that
makes `server { }` and `client { }` mean something.

## Why a compiler plugin at all

`server { }` and `client { }` run in a different JVM than the code that lexically contains them. An
ordinary Kotlin lambda cannot go there -- it is a closure over locals that do not exist on the other
side, and there is no sending a closure down a socket.

So every block body is *lifted* out of its closure into a generated dispatch table keyed by a stable
id, and the call site is rewritten into `invokeProcedure(id, target, args, ...)`. Only the id and the
arguments travel.

The body is **moved rather than copied** -- the function the frontend already built for the lambda is
re-parented into the table -- so every symbol inside it stays valid, and it stops being a closure
because it is no longer nested in one.

## What is in it

| | |
|---|---|
| `fir/ProcedureCallChecker.kt` | The rules, enforced on every build. What a block may reference, where a block may appear, what a name must be |
| `fir/ProcedureDiagnostics.kt` | The messages those rules produce |
| `ir/ProcedurePlanner.kt` | Walks a file and decides what the blocks are and what each one is called. Ordinals are **per call site**, so a block written inside a loop has one id however often it runs |
| `ir/ProcedureTransformer.kt` | The rewrite: bodies into tables, call sites into `invokeProcedure` |
| `ir/ProcedureIndexWriter.kt` | Writes the index the runtime reads to map an id to the table that owns it |
| `ir/RuntimeSymbols.kt` | Everything from `:core` the rewritten code calls, resolved once per module |

## Ids

An id is structural: suite, test, role and ordinal. It has to survive a rebuild on another machine,
because one process compiles the call site and another looks it up. That is why suite and test names
must be compile-time constants -- they are part of the id.

## Diagnostics in the IDE

The checkers run on every build regardless. Getting them to appear under the cursor as you type needs
the IDE set up to load the plugin; see the main [README](../README.md).
