# :orchestrator

The process that starts the games, relays between them, and gets out of the way.

It has **no Minecraft on its classpath** and no idea what a test is. It brings a cluster up, wires
the transport, calls a `main`, and routes whatever that main asks for to whichever node owns it.
That separation is what keeps a crashed server from taking down the thing measuring it.

## What is in it

| | |
|---|---|
| `launcher/LaunchPlan.kt` | How to start one game process: java binary, JVM args, classpath, working directory. Written by [`:gradle-plugin`](../gradle-plugin/README.md), never reconstructed here |
| `launcher/GameProcess.kt` | Starting one, pumping its output to a log, stopping it |
| `launcher/Cluster.kt` | Bringing the set up: the server, then a client per name, each with its own username and game directory. Clients start on demand, so a name nobody could work out ahead of time still works |
| `launcher/OrchestratorBootstrap.kt` | The run itself: wait for every node to connect, then call the configured main |
| `orchestrator/Orchestrator.kt` | The relay. A call from any node to any other goes through here |

## Why the plan is harvested rather than written

A Minecraft launch command is long, version-specific and full of paths only ModDevGradle knows. So
the Gradle plugin runs ModDevGradle's own run task configuration and *records* what it would have
executed, into `build/e2e/launch-plan.json`. The orchestrator reads that.

One harvested client command is enough for any number of clients: each one is that same command with
a different username and game directory.

## Nodes are started, not assumed

A client is launched the first time something addresses it. The names a suite writes as literals are
collected at compile time and started up front, because starting them in parallel is faster than
starting them one at a time on first use -- but neither the plan nor this module requires the list to
be complete.
