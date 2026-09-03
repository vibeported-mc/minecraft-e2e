# :rpc:core

Identity, targeting, scopes, the calls, the tables and the dispatcher. Everything a node needs to run
a body, and nothing about how bytes reach another machine.

The split with `:rpc:transport` is one interface wide -- send these bytes to that node and bring back
the answer -- and it is what lets a node that never leaves its process depend on no networking at
all. A cluster of one gets an implementation whose every method says why rather than what.

## Six decisions that are load-bearing

**Every call here is an ordinary function.** Nothing in this module is privileged by the compiler
plugin except one annotation on a parameter. A fan-out is a dozen lines over the dispatcher and could
have been written outside this module -- which is the test of whether the design is any good, because
a layer that needs a call of its own must be able to write one without touching the plugin.

**A role is a string, not an enum.** The framework this replaces had a closed
`ORCHESTRATOR`/`SERVER`/`CLIENT` enum, and that single decision is what welded it to one game. Roles
are open here, and a node holds a set of them.

**Both halves of serialization live on the table, not on the node.** The bytes arriving at a node
carry no type information, and only the generated code knows what they were. So a procedure table
decodes its own arguments and encodes its own result, and the server half hands it bytes without
opinions. Anything else would need a reflective codec lookup at run time, which is the failure this
framework was built to move to compile time.

**A local call never serializes.** The dispatcher compares the target to this node's id first and
hands the real objects over. That is what makes it affordable to build a whole gameplay vocabulary
out of these calls -- most of them are not going anywhere, and the ones that are pay alone.

**CBOR is the default wire format.** Arguments are already `ByteArray` by the time they reach an
envelope, and JSON would base64 every one of them. JSON stays available for when a human has to read
a frame.

**The call overloads are generated, not written.** Bodies may not capture, so everything a body needs
arrives as an argument, so every call exists at every arity to five -- twenty-four near-identical
functions, which is exactly where a typo hides. `tools/rpc-overloads.py` emits them and `--check`
fails if anyone edits the output instead of the generator.
