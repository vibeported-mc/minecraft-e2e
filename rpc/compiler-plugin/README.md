# :rpc:compiler-plugin

The K2 plugin. One FIR checker enforcing four rules under the cursor, and an IR pass that lifts each
body into a table and writes the manifest naming it.

Two halves because they answer different questions. The frontend decides whether a call is written
legally, and must do so in the editor as you type; the backend does the tier splitting, and needs
types and bodies the frontend has not finished with. They share exactly one object, for the one fact
that cannot cross on its own.

## Six decisions that are load-bearing

**The body is moved, not copied.** The function the frontend already built for the lambda is
re-parented into the table, so every symbol inside it stays valid with nothing remapped -- and it
stops being a closure simply because it is no longer nested in one. Lifting and rewriting the call
site have to happen in one pass; a re-parented lambda whose call site still points at it is an orphan
the backend rejects with `No dispatch receiver allowed in wrappers`.

**Roles cross from frontend to backend in a side table, because they cannot cross in the IR.** An
expression-target annotation is forced to `SOURCE` retention, so the role written on a lambda reaches
FIR and is gone by the time the backend runs. The index that carries it is created per compilation
and handed to both halves.

**That index is never a singleton.** A shared object would outlive every compilation in a Gradle
daemon and let one module read another's roles -- a bug that appears only under a warm daemon
building more than one module, which is to say only on someone else's machine. There is a test that
compiles two modules at once and fails against the singleton version.

**The index is keyed by the call, not the lambda.** FIR and IR agree on where a call starts and
disagree about where an annotated lambda does: one counts from the annotation, the other from the
brace. The call site is the only thing spelled the same on both sides.

**Serializability is decided in the frontend.** The types are still in view there, so an argument
nothing can encode becomes a message on the declaration that named it. Deciding it in the backend
would have been easier and would have produced a line in a build log; deciding it at run time, as the
framework this replaces did, produced a test that failed halfway through for returning a
`java.io.File`.

**The plugin knows no function by name.** It looks for an annotation on a *parameter*, which is what
lets anyone write a call of their own -- one that shuffles targets, retries, or fixes the scope to
something a layer defines -- and hand a body onward without the plugin ever learning that function
exists. Marking the functions instead would have been a shorter implementation and a closed set.

## Ids

An id is lexical: the facade class, the declaration the call sits in, then a per-role ordinal in
source order. It says where a body was *written*, so a body inside a loop or a helper has exactly one
id however many times it runs. One process compiles the call site and another looks it up, so an id
has to survive a rebuild on another machine.
