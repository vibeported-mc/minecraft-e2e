# rpc/e2e

Three processes with three different classpaths, and a runnable node to put in them.

Five directories, one experiment, so one README. Everything else in this repository runs its nodes in
a single JVM -- which is the one arrangement in which the problem these roles exist for *cannot
occur*. A dedicated server is dist-cleaned: a body touching client classes is not slow there, the
class holding it is not on the machine. The only honest way to test that is separate processes whose
classpaths genuinely differ.

## The arrangement

| | |
|---|---|
| `part-a` | Stands in for the common half of a game. On every node. Owns `Ident` and the `@RpcSerializer` for it |
| `part-b` | Stands in for the half only some nodes have. `Beta.callB()` calls into `part-a`. Owns `Tag`, and its serializer |
| `layer` | The mod jar: writes `rpcCall(...) { Alpha.callA() }` and `rpcCall(...) @RpcRole("B") { ... Beta.callB() }`. Depends on both halves `compileOnly`, and bundles neither -- which is what a mod jar does with a game |
| `node` | A runnable node, over [`:rpc:host`](../host/README.md). Told `-Drpc.node`, `-Drpc.roles` and `-Drpc.hub=host:port`; prints `rpc.ready` when it has joined |
| `driver` | The supervisor and the assertions: hosts the hub, forks the nodes, and calls |

The same `layer` jar goes to both nodes. Node `a` gets `part-a`; node `b` gets both. The driver
holds both halves and **resolves no tables at all** -- it has to encode what it sends, and a
serializer ships in the jar declaring the type, but it runs none of it.

```
node a    node + layer + part-a              roles: {}     resolves 2 procedures
node b    node + layer + part-a + part-b     roles: {B}    resolves 4
driver    layer + both halves                roles: {}     serves nothing; calls both
```

## What the four tests pin

**A node loads only the tables its roles allow, out of a jar holding both.** The class file for the
`B` table is in node `a`'s jar the entire time. Nothing hides it; node `a` simply never resolves it,
and the procedure count each node reports on startup is the split made visible from outside.

**A call routed to the wrong node names the role it needed.** Refused on the caller, before anything
reaches the wire -- the roster says which roles node `a` holds and the body's own role says what it
needs. That is stronger than the far node refusing it, and much stronger than "no such procedure",
which would send whoever read it looking for a module that is not missing.

**A node claiming a role its jars cannot support starts anyway, and fails on the call.** This one
documents a limit rather than a feature, and it corrected the design. Loading a table does *not*
resolve the classes named inside its method bodies -- the JVM defers that until the method runs -- so
the `B` table instantiates perfectly happily with `Beta` absent. `TableRegistry` claimed otherwise
until this test was written. A role is therefore an assertion the deployment makes and the runtime
cannot check; what protects a dist-cleaned node is never claiming the role in the first place.

**A value crosses because the module owning its type said how.** Neither `Ident` nor `Tag` is
`@Serializable`, and no build script anywhere names either. Each crosses because the module that
owns it declared a serializer beside it; the layer inherited both off its compile classpath, and
three separately-built processes each assembled the same answer out of the manifests in their own
jars. The split holds here too: node `a`, having never seen `part-b`, ends up with a wire format
that has never heard of `Tag`.

## Running it by hand

`gradlew :rpc:e2e:driver:writeNodeClasspaths` writes both classpaths to
`rpc/e2e/driver/build/rpc-e2e/`, so a node can be started outside Gradle:

```
java -Drpc.node=b -Drpc.roles=B -Drpc.hub=127.0.0.1:5000 -cp "$(cat classpath-b.txt)" \
    dev.vibeported.rpc.e2e.node.MainKt
```

A three-process test that can only be debugged from inside Gradle is a test nobody debugs.
