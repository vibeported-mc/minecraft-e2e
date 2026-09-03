# :rpc:host

Puts a node in a process and connects it to a hub.

The sequence every program otherwise writes for itself: connect, resolve the tables this node's roles
permit, wire the peer, announce, install. Six lines, and the one easiest to forget -- installing the
node process-wide -- fails much later and somewhere else, in code that has no idea a node was needed.

Three programs want one and they differ in every particular, which is why everything is a constructor
parameter rather than a step: a game passes its event loop and the game itself, an orchestrator
passes an empty table registry because it drives a cluster and runs none of its procedures, and a
plain worker passes nothing but what it read from its command line.

## Three decisions that are load-bearing

**The event-loop hook lives here, not in `:rpc:core`.** A host wraps its own request handler, so a
game can have every statement of every body run on the game thread without `ProcedureServer` growing
an interceptor for it. The seam already existed; it only needed to be a parameter.

**An empty table registry is how a caller-only node is written.** Not a special mode: such a process
holds the jar the bodies were written in and none of the jars they need, so resolving its tables
would fail on the very first one. Saying "serve nothing" is the honest spelling of that.

**A failure hook that attaches nothing to the wire.** A node can be told when a body threw, so it can
photograph itself at the moment it failed -- but the failure still travels as its type, message and
stack, exactly as it would have. Evidence goes separately, as an ordinary value through an ordinary
call, which is what keeps the transport from growing a field for whatever the next kind of evidence
turns out to be.
