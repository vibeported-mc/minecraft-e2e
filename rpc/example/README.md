# :rpc:example

A consumer. Applies the Gradle plugin by id, writes some calls, and runs them.

Every test in `:rpc:compiler-plugin` hands the compiler a source file and inspects what came out.
This module is compiled by the build itself, so it proves the part those cannot: that applying the
plugin is enough, that the manifest reaches the classpath, and that a node finds its tables by
reading it rather than from a list somebody passed in.

## Three decisions that are load-bearing

**It defines a scope of its own.** A body written at one of its calls sees that scope rather than the
bare one, and nothing in the compiler plugin knows this module exists. If offering a custom receiver
ever needed a plugin change, the whole design would have failed at the one thing it was for.

**Tables are loaded through the manifest, never constructed.** Naming a generated class in a test
would prove the class exists; loading it by manifest proves the whole discovery path, which is the
half a build can silently break.

**It is a consumer, not a fixture.** Its build script says what an outside project would say and
nothing more -- no reaching into the plugin, no test-only wiring. That is the point of it: if this
module needs a special case to work, so does everyone else.
