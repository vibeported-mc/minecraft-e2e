# :gradle-plugin

An included build. Applies and configures everything a consuming project needs, and adds
`runE2eTests`.

It is an included build rather than a module because a build cannot apply a plugin it is also
compiling. The rest of the tree consumes it by id, exactly as an outside project would -- which is
the point: this repo dogfoods its own plugin.

## What a consumer writes

```kotlin
plugins {
    id("net.neoforged.moddev")
    id("dev.vibeported.mc.e2e")
}

mcE2E {
    neoForge { version = "26.2.0.69" }
    modId = "mymod"
    orchestratorMain = "com.example.tests.MainKt"
}
```

## What is in it

| | |
|---|---|
| `McE2eExtension.kt` | The whole surface: the mod under test, timeouts, window size, the orchestrator main, and a `blockDsl { }` block |
| `E2eGradlePlugin.kt` | Wires it: the suites source set, the compiler plugin, the generated mod metadata, three ModDevGradle runs, and `runE2eTests` |
| `HarvestLaunchPlanTask.kt` | Records how ModDevGradle *would* launch the client and server, into `build/e2e/launch-plan.json` |

## Two decisions that are load-bearing

**ModDevGradle is `compileOnly` here.** Anything on this plugin's runtime classpath is exported onto
the consuming script's plugin classpath -- a second copy of ModDevGradle beside the one the consumer
applies. MDG applies `gradle-idea-ext` to the *root* project and Gradle's already-applied check is
per `Class` object, so the second copy does not recognise the first one's work and collides. The
failure names neither plugin and only bites during an IDE import.

**The launch plan is harvested, not written.** A Minecraft command line is long, version-specific and
full of paths only MDG knows. Reconstructing one by hand is a thing that works until it does not; so
the run task's own configuration is read and recorded instead.
