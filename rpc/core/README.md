# :rpc:core

Identity, targeting, scopes, the calls, the tables and the dispatcher. Everything a node needs to run
a body, and nothing about how bytes reach another machine.

The split with `:rpc:transport` is one interface wide -- `Outbound.call(target, procedure, args)` --
and it is what lets a node that never leaves its process depend on no networking at all. A cluster of
one is `Outbound.Isolated`, whose every method says why rather than what.

## What is in it

| | |
|---|---|
| `Identity.kt` | `NodeId`, `Role`, `NodeInfo`. Value classes, and `Role` is open -- a closed enum of `SERVER`/`CLIENT` is the mistake this framework exists to undo |
| `RpcTarget.kt` | `Exactly` a node, or `Where` a predicate matches. Named `RpcTarget` because `Target` shadows `kotlin.annotation.Target` |
| `Services.kt` | The node-local registry. A body's receiver is resolved from it on the node that runs the body; an unknown type lists what the node *does* have |
| `RpcScope.kt` | What a body sees where it lands, and the interface a layer subtypes to offer its own |
| `RpcBody.kt`, `Calls.kt` | The body shapes at each arity, and `rpcCall` / `rpcCallIn` / `forEachRpcCall` / `forEachRpcCallCatching` over them. **Generated** -- edit `tools/rpc-overloads.py` |
| `Annotations.kt`, `LiftedBody.kt` | `@RpcRole`, `@RpcLift`, and what a lambda becomes: an id, a role and its serializers |
| `ProcedureTable.kt` | The interface the generated tables implement: `procedures()`, `invoke`, `decodeArgs`, `encodeResult` |
| `Manifest.kt`, `TableRegistry.kt` | Reading every `META-INF/rpc/procedures.json` on the classpath, and resolving only the tables this node's roles allow |
| `Dispatch.kt` | What a call becomes: local or remote, with the role narrowing the target set on the way |
| `Wire.kt` | `WireFormat`. CBOR by default, because arguments are `ByteArray` and JSON would base64 them |
| `RpcNode.kt` | The node itself, and `ProcedureServer` -- the inbound mirror of `Dispatch` |
| `Membership.kt`, `Outbound.kt` | The two things core needs from a transport, as interfaces it does not implement |

## Three decisions that are load-bearing

**Every call here is an ordinary function.** Nothing in this module is privileged by the compiler
plugin except the `@RpcLift` annotation on a parameter. `forEachRpcCall` is a dozen lines over
`dispatchEach` and could have been written outside this module -- which is the test of whether the
design is any good, because a layer that needs a call of its own must be able to write one.

**Both halves of serialization live on the table, not on the node.** The bytes arriving at a node
carry no type information, and only the generated code knows what they were. So `ProcedureTable`
carries `decodeArgs` and `encodeResult`, and `ProcedureServer` hands them the bytes without opinions.

**A local call never serializes.** `Dispatch` compares the target to this node's id first and hands
the real objects over. That is what makes it affordable to build a whole gameplay vocabulary out of
these calls -- most of them are not going anywhere.
