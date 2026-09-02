# :codegen

Writes a Kotlin name for every block the game loads, so a fixture is written in code the compiler
checks rather than in a string nobody checks.

```kotlin
build { at(FAR_AWAY) { minecraft.gold_block } }
```

`minecraft.gold_block` is generated. Misspell it and the build fails; the alternative is
`"minecraft:gold_blcok"` and a test that places nothing and says nothing.

## Why it needs a running game

Which blocks exist, and what each will accept, are answers only a loaded registry has. So this boots
FancyModLoader with every mod present, reads the block registry, and writes Kotlin from it.

That is a real cost on a first sync, which is why it is off unless a build asks:

```kotlin
mcE2E { blockDsl { enable() } }
```

## What is in it

| | |
|---|---|
| `BlockDslMain.kt` | Runs inside the booted game: reads the registry, filters to the requested namespaces, hands over the model |
| `Model.kt` | What a generated block looks like -- namespace, path, and the properties it carries |
| `KotlinWriter.kt` | Turns that into source, one object per namespace |
| `launcher/CodegenEntrypoint.java` | The main ModDevGradle launches |

No dependency on `:core`. This module neither speaks the transport nor knows what a procedure is, and
a dependency would only invite one.
