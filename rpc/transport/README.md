# :rpc:transport

Envelopes, framing, and a star: one hub relaying between nodes that each hold one connection to it.

Core defines two interfaces and implements neither -- one to send a call, one to know who is out
there. This module answers both, and nothing in it knows what a procedure is. It ships twice: over
real sockets, and over an in-memory fabric that is the same star with no ports, so behaviour can be
tested without binding anything.

## Five decisions that are load-bearing

**The hub understands nothing about calls.** It keeps the roster, pushes it when it changes, and
relays everything else to whoever it is addressed to. A hub that understood procedures would be a
bottleneck with opinions, and every routing question would have two places to look.

**The roster is pushed, not polled.** Every node holds the same view of who is out there, which is
what lets a fan-out predicate be evaluated on the *caller*, against a local replica, staying an
ordinary Kotlin lambda instead of something that has to cross a wire. The price is that a snapshot
can be a moment stale, and the failure policy below is how that is paid rather than pretended away.

**A node that dies fails its calls rather than hanging them.** A closed socket is not an error here,
it is news: the hub evicts the speaker and republishes, and every peer waiting on a call to that node
is completed with an exception naming it. Without that, a crashed process is a call that waits
forever -- the worst failure a test harness can have, because it looks like slowness.

**Frames are length-prefixed, not delimited.** Four big-endian bytes, then that many. Unglamorous,
and the alternative means escaping the payload, which is precisely what choosing a binary format was
meant to avoid. The length is bounded so that a corrupt one cannot exhaust memory.

**Blocking IO on the IO dispatcher, not NIO.** There is exactly one socket per node and a handful of
frames per call. A selector is machinery bought for a load that does not exist, and the blocking
version is the one whose failures are legible in a stack trace.
