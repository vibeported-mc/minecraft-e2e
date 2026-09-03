# :minecraft

The game half. Everything a Minecraft process needs to be a node in a test run, and nothing that
knows what a test is.

Loaded by FancyModLoader on both sides, so it holds what a dedicated server and a client both need:
the `server { }` and `client { }` calls a suite writes, the scopes a lifted body sees, and the three
things only a game can supply -- an event loop to run bodies on, a receiver to run them against, and
serializers for values that are Mojang's rather than ours.

Everything else -- transports, tables, dispatch, the compiler plugin -- is [`rpc/`](../rpc/README.md),
which has never heard of Minecraft. This module is the layer that teaches it.

## Five decisions that are load-bearing

**`server` and `client` are ordinary functions.** They take a body at an `@RpcLift` parameter and
hand it to `rpcCallIn`, and the compiler plugin has never heard of either name. That is what marking
the *parameter* rather than the function bought: this module defines its own vocabulary without the
framework knowing it exists, and anyone else can define theirs.

**The role is declared once, on the parameter.** `@RpcLift("client")` is what puts a client body in a
table a dedicated server never resolves. Thirty call sites say nothing about roles, cannot get it
wrong, and read exactly as they did before there were any.

**A `BlockPos` crosses because the build says so, not because the runtime guesses.** It is not
`@Serializable` and never will be, so the compiler is told its name and this module supplies the
matching serializer over the game's own `Codec`. Both halves are needed: a type named in one and not
the other fails to compile rather than at the first call that sends one. Anything not named still
has to be serializable.

**The event loop is passed in, not reached for.** `RpcHost` takes a dispatcher, so every statement in
every body runs on the game thread with no wrapper to remember, and suspending inside one releases
the loop so the game keeps ticking. The framework underneath needed no hook for this: a host wraps
its own handler.

**Test identity is announced, not carried.** Which test is running used to ride in every single
request, beside the procedure and its arguments -- a test framework's vocabulary inside a transport
that has no business knowing what a test is. It is told to every node once per test now, through an
ordinary call, and each node remembers the last thing it was told.
