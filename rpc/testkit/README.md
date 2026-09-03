# :rpc:testkit

A whole cluster in one JVM, over the in-memory fabric.

The same star the sockets form, minus the sockets: join some nodes, kill one the way a crash does,
and wait until they all see each other. Behaviour that holds here holds there, with the exception of
anything about framing or connections -- and those are tested against real sockets in
`:rpc:transport`, and against real processes in `rpc/e2e`.

## Two decisions that are load-bearing

**Joining takes a table registry, not a list of tables.** A registry is what a node outside a test
holds: one built by reading the manifests on its own classpath and resolving only what its roles
permit. A cluster that assembled the registry itself would be exercising a shape nothing else uses,
and would quietly hide the step where a role decides what a node can load.

**Waiting for the roster is offered here rather than left to each test.** Membership arrives
asynchronously, so a fan-out issued immediately after joining races it and sometimes matches too few
nodes. One helper is cheaper than that failure appearing, once, intermittently, in somebody else's
test three months from now.
