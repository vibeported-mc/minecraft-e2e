# :rpc:transport

Envelopes, framing, and a star: one hub relaying between nodes that each hold one connection to it.

Core defines two interfaces and implements neither -- `Outbound` to send a call, `Membership` to know
who is out there. This module answers both, and nothing in it knows what a procedure is. A hub that
understood calls would be a bottleneck with opinions.

## What is in it

| | |
|---|---|
| `Envelope.kt` | `Hello`, `Roster`, `Request`, `Response`, `Cancel`, `Goodbye`, `Heartbeat`; `RemoteFailure` and the two exceptions a caller sees. Arguments are `List<ByteArray>` -- already encoded, opaque here |
| `EnvelopeCodec.kt`, `Framing.kt` | CBOR for the envelope, and a four-byte big-endian length in front of it, because a stream has no idea where one ends |
| `Transport.kt` | Send an envelope, receive a `Flow` of them, close. The whole SPI |
| `SocketHub.kt` | The middle of the star: holds the roster, pushes it when it changes, relays everything else |
| `SocketTransport.kt` | A node's one connection to the hub. Blocking IO on `Dispatchers.IO` -- one socket per node and a handful of frames per call is not a case for a selector |
| `InMemoryFabric.kt` | The same star with no sockets, so behaviour can be tested without ports |
| `RpcPeer.kt` | The half that pairs requests with responses, and gives up on a node the roster says has gone |
| `LiveMembership.kt` | The local replica the hub feeds |

## Three decisions that are load-bearing

**The roster is pushed, not polled.** Every node holds the same view of who is out there, which is
what lets `RpcTarget.Where` be evaluated on the *caller*, against a local replica, with the predicate
staying an ordinary Kotlin lambda instead of something that has to cross a wire. The price is that a
snapshot can be a moment stale, and the failure policy below is how that is paid rather than
pretended away.

**A node that dies fails its calls rather than hanging them.** A closed socket is not an error here,
it is news: the hub evicts the speaker and republishes, and every peer waiting on a call to that node
is completed with `NodeGoneException` naming it. Without that, a crashed process is a call that waits
forever, which is the worst failure a test harness can have.

**A response nobody is waiting for is dropped, silently and on purpose.** A cancelled caller has
already given up, and the call id is never reissued -- so the late answer has nowhere to go and
nothing to corrupt.
