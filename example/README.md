# :example

A consumer, and the only place in this repo that looks like an outside project would.

Its whole build is a plugins block, an `mcE2E { }` block, and dependencies pointed at this repo's own
projects instead of at Maven:

```kotlin
plugins {
    alias(libs.plugins.moddev)
    id("dev.vibeported.mc.e2e")
}

mcE2E {
    neoForge { version = libs.versions.neoforge.get() }
    modId = "example"
    orchestratorMain = "dev.vibeported.mc.e2e.tests.MainKt"
    blockDsl { enable() }
}
```

## Running it

```powershell
.\gradlew :example:runE2eTests
```

Boots a dedicated server and two clients (`alex` and `steve`), runs the suite, and leaves a report,
screenshots and a recording under `build/reports/e2e/`.

## What the suite covers

`src/e2eTest/kotlin/.../Blocks.kt`, four tests:

| | |
|---|---|
| alex flies a circle around steve, filming him | `record { }` and `orbitPlayer` -- a server-driven camera move, recorded off the GPU |
| two players fly to a block, watch it, then watch each other | two clients at once, through ordinary structured concurrency |
| both players equip themselves by dragging, and one mines the block | screens, slot drags, and a fight that ends with one of them dead |
| a ring of stairs keeps the corners it was given | building a shape and reading it back **on the server**, because a client draws what it was sent |

`Main.kt` is an ordinary `main` that calls `Runner.run(blocks)`. Nothing about it is special to the
framework, which is the point -- by the time it runs the transport is already wired.

## Worth knowing when adding a test

The second test kills a player. A test placed after it that expects a live one will time out in
`waitForPlayer`, because a dead player is never "ready". Order matters here in a way it would not in
an isolated harness.
