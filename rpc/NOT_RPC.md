# Why this is not an RPC framework

The directory is called `rpc` because that is the phrase people search for. The name is wrong, and
the way in which it is wrong is the whole design.

## The difference

**RPC starts from an interface.** You declare a service, generate stubs from it, and call a method
that somebody implemented somewhere else. The unit is a *signature*, the two sides are written
separately, and the interface is the contract between them.

**This starts from a program.** One file, read top to bottom, in which some expressions are marked to
run somewhere else. There is no service, no stub and no interface -- the body of a remote call is
written inline, in the middle of the function that needs its result, and it can be read in the order
it happens:

```kotlin
suspend fun doWork(): Int {
    val fromA = rpcCall(node("a")) { 2 }
    val fromB = rpcCall(node("b"), fromA) { a -> a * 3 }
    return fromA + fromB
}
```

Three machines in nine lines, and nothing was declared anywhere. Writing that with RPC means two
service definitions, two implementations and a build step, in three files none of which reads like
the sequence above.

## What it is called

This is not a novel idea, and knowing the words is worth more than any amount of describing the
parts. There are forty years of prior art with known trade-offs.

| Term | From | Here |
|---|---|---|
| multitier / tierless programming | Links, Hop, Ur/Web, Eliom, [ScalaLoci](https://scala-loci.github.io/) | one file, parts placed on different nodes |
| tier splitting | Eliom | what the K2 plugin does: bodies become per-role tables |
| placement | ScalaLoci's `on[Client]` | `@RpcRole("B")`, plus the `RpcScope` subtype a body sees |
| remote evaluation (REV) | Fuggetta, Picco & Vigna, *Understanding Code Mobility*, 1998 | the caller supplies the code; the node supplies what could not travel |
| static closure | GHC `StaticPointers`; Cloud Haskell's `Closure` | `LiftedBody` -- a name and its serializers. Code never crosses the wire |
| spore | Miller, Haller & Odersky | the no-capture rule `RpcCallChecker` enforces |

**The programming model is multitier programming**, and the compile step is **tier splitting**: one
program whose fragments are statically assigned to locations, split by the compiler into a separate
artifact per location.

**The execution model is remote evaluation.** That taxonomy has four corners -- client-server, remote
evaluation, code-on-demand, mobile agent -- and RPC is the one *opposite* this. In client-server the
code already lives at the far end and a call names it. In remote evaluation the caller supplies the
code, because the far node has something that cannot be sent. A game client's `minecraft.player`
exists on one machine only, and there is no bringing it here, so the body goes there instead.

**The mechanism is a static closure.** Code is never serialized. Every node's jar already contains
every body, compiled into a table, so what crosses the wire is a name and some arguments. That is
the same trick as GHC's `StaticPointers` and Cloud Haskell's `Closure`, and it is why the no-capture
rule exists: a name can be resolved on the far side, a captured local cannot.

**If you already know React Server Actions**, you already know the shape. `"use server"` inside a
client file; the bundler lifts the function out; the call site keeps a reference carrying an id;
calls dispatch over the wire. Same split, different vocabulary -- and the same failure modes, right
down to arguments having to be serializable.

## Why it matters in practice

The framing is not academic tidiness. Three things follow from it that would not follow from RPC:

- **There is no interface to keep in sync**, so there is no version skew between a declaration and
  its implementation. The body *is* the declaration.
- **A body may be written anywhere a lambda may be**, including inside a loop, a helper, or another
  body. An id describes where it was written, not how many times it runs.
- **The compiler can check the whole thing**, because both sides are in front of it at once. A
  captured local and an unserializable argument are errors under the cursor, not incidents in a log.

And one cost, which is the price of the same property: a body is not ordinary Kotlin. It cannot see
its enclosing scope, and everything it touches must cross a wire. See
[What this does not do](README.md#what-this-does-not-do).
