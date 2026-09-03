# rpc

Run code on another machine, written where it is used.

This is **not an RPC framework** -- there is no service to declare and no interface to keep in sync.
[Here is what it is instead](NOT_RPC.md), and what the model is called.

```kotlin
suspend fun doWork(): Int {
    // Runs on node a. Written here.
    val fromA = rpcCall(node("a")) { 2 }

    // Runs on node b. Written here -- and note `fromA` is *passed*, not captured: this body runs in
    // another process, where a local of this function does not exist. The compiler rejects the
    // version that closes over it, rather than leaving it to fail at run time.
    val fromB = rpcCall(node("b"), fromA) { a -> a * 3 }

    // Runs here.
    return fromA + fromB
}
```

One function, three machines, read top to bottom.

A body can also say *which kind of node* it needs, which is what makes a dist-cleaned game server
survivable:

```kotlin
suspend fun anywhere(target: String): String = rpcCall(node(target)) { Alpha.callA() }

suspend fun onlyOnB(target: String): String =
    rpcCall(node(target)) @RpcRole("B") { Alpha.callA() + "/" + Beta.callB() }
```

The rest of this document is what all that costs. A body may not capture anything around it, and
every value it needs must serialize -- both enforced by the compiler rather than discovered in a log.

## What the compiler does with it

A Kotlin lambda cannot cross a socket -- it closes over locals that do not exist on the other side.
So each body written at an `@RpcLift` parameter is **moved** out of its closure into a generated
table, and the call site keeps a `LiftedBody`: an id, a role, and the serializers for its arguments
and result. Moved rather than copied -- the function the frontend already built is re-parented -- so
every symbol inside it stays valid, and it stops being a closure because it is no longer nested in
one.

One table per (file, role). The `anywhere` and `onlyOnB` pair above compiles to:

| class | references |
|---|---|
| `CallsKt` -- the call sites | *neither* `Alpha` nor `Beta` |
| `CallsKt_Rpc` -- every node loads it | `Alpha` |
| `CallsKt_Rpc_B` -- only a node holding `B` | `Alpha`, `Beta` |

Read off the jar with `javap`, not asserted. The first row is the surprising one and it is the whole
point: after lifting, the file that *writes* the calls names nothing either body touches. A process
can dispatch procedures it could never run, which is exactly what an orchestrator is.

Beside the classes goes a names-only manifest, `META-INF/rpc/procedures.json`:

```json
{ "id": "CallsKt.onlyOnB/0", "table": "dev.vibeported.rpc.e2e.layer.CallsKt_Rpc_B", "role": "B", "module": "..." }
```

A node reads every copy of it on the classpath -- names cost nothing -- and resolves only the table
classes its own roles permit.

## Scopes: what a body sees where it lands

A body runs against a receiver the *target* node provides, so a layer can offer its own vocabulary.
Nothing below needs the compiler plugin:

```kotlin
class GreeterScope(
    override val node: NodeInfo,
    override val services: Services,
    val salutation: String,
) : RpcScope

suspend fun <R> greeter(name: String, @RpcLift body: RpcBody0<GreeterScope, R>): R =
    rpcCallIn(node(name), body)

greeter("there") { salutation }
```

`greeter` is an ordinary function. The scope reaches the body because the node it lands on provides
one; the body is dispatched because `greeter` handed it to a call. That is how a game client's
`client("alex") { minecraft.player }` is built, and why it needs no support in the plugin.

## Why the marker is on the parameter

`@RpcLift` marks a *parameter*, never a function. The plugin therefore knows no function by name,
and anyone can write a call of their own -- one that shuffles its targets, retries, or fixes the
scope to something a layer defines -- take a body at an `@RpcLift` parameter and hand it on. A body
passed along a chain of such functions is lifted where it was written and dispatched by whichever
link finally passes it to a call.

The one thing a link may not do is run it. `RpcBodyN.run` exists so a lambda has something to
convert to; a table invokes the lifted function directly, so calling `run` by hand is a compile
error.

## Roles, and the dist split they exist for

A dedicated Minecraft server is dist-cleaned: a body touching client classes is not slow there, it
is *unloadable*. Nothing in the source says which classes exist where, so it is an assertion the
call site makes -- `@RpcRole("client")` on the lambda, or `@file:RpcRole` for a default.

**`ServiceLoader` cannot be used for this**, which is worth saying loudly because it is the obvious
reach: iterating a service instantiates every registered implementation, so a server would construct
the client table and die. Hence the names-only manifest.

The guarantee that holds is narrower than "the classpath will catch it", and stronger:

- A node that does not hold a role **never resolves that role's table**. The class file sits in its
  jar, untouched.
- A call whose body needs a role the target lacks is refused **on the caller**, before anything
  reaches the wire:

  ```
  `CallsKt.onlyOnB/0` needs role `B`, and a holds []. It cannot run this.
  ```

## What this does not do

| | Pinned by |
|---|---|
| **No distributed state, and no consensus anywhere.** A node has a local `Services` registry; that is all. Injecting a `Minecraft` once makes it the receiver of every body routed there | `ServicesTest` |
| **A role is an assertion the runtime cannot verify.** Loading a table does *not* resolve the classes its method bodies name -- the JVM defers that until the method runs. A node claiming a role its jars cannot support starts cleanly and fails on the first call | `DistTest`, third case |
| **No node discovery.** A node is *told* where the hub is, `-Drpc.hub=host:port`, and announces itself. A beacon belongs behind an SPI, once something needs it | `rpc/e2e/host` |
| **Generic argument types are refused.** Only the class survives to the serializer lookup, so `List<Int>` would encode the wrong thing. Wrap it in a `@Serializable` class | `SerializationTest` |
| **Bodies may not capture.** Everything a body needs arrives as an argument, which is why the calls come at every arity to five | `CaptureTest` |
| **Star topology only.** One hub relays; `Transport` is an interface, so a mesh can arrive later without the calls changing | `SocketClusterTest` |

## Modules

| Module | What it is |
|---|---|
| [`:rpc:core`](core/README.md) | Identity, targeting, scopes, the calls, the tables, the manifest, the dispatcher |
| [`:rpc:transport`](transport/README.md) | Envelopes, framing, the star hub, in-memory and TCP, the membership replica |
| [`:rpc:compiler-plugin`](compiler-plugin/README.md) | The K2 plugin: the frontend checker, and the IR pass that lifts bodies into tables |
| [`:rpc:gradle-plugin`](gradle-plugin/README.md) | An included build. Wires the compiler plugin in and packages the manifest |
| [`:rpc:host`](host/README.md) | Puts a node in a process: connect, resolve tables, announce, serve |
| [`:rpc:testkit`](testkit/README.md) | A whole cluster in one JVM |
| [`:rpc:example`](example/README.md) | A consumer: applying the plugin is enough |
| [`rpc/e2e`](e2e/README.md) | Three processes with three different classpaths, which is the only way to test the dist split |

No module here names a Minecraft type, and that is checked rather than trusted: `gradlew
:rpc:core:checkNoGame` and its siblings fail if any `rpc` module can resolve a `net.minecraft` or
`net.neoforged` artifact.
