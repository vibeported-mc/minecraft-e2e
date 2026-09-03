# :rpc:example

A consumer. Applies `dev.vibeported.rpc` by id, writes some calls, and runs them.

Every test in `:rpc:compiler-plugin` hands the compiler a source file and inspects what came out.
This module is compiled by the build itself, so it proves the part those cannot: that applying the
plugin is enough, that the manifest reaches the classpath, and that a node finds its tables by
reading it rather than from a list somebody passed in.

## What is in it

| | |
|---|---|
| `build.gradle.kts` | The whole consumer surface: two plugins, `rpcCompilerPlugin(...)`, and ordinary dependencies |
| `CallTest.kt` | A call that round-trips between two nodes; a fan-out; a scope of the layer's own; and an assertion that the manifest was written at all |

## Two decisions that are load-bearing

**`GreeterScope` is here rather than in `:rpc:core`.** It is the demonstration that a layer can offer
its own receiver -- `greeter("there") { salutation }` -- with no support from the compiler plugin
whatsoever. If that ever needed a plugin change, the `@RpcLift` design would have failed at the one
thing it was for.

**The tables are loaded through `TableRegistry.load`, never constructed.** Naming a generated class
in a test would prove the class exists; loading it by manifest proves the whole discovery path, which
is the half that a build can silently break.
