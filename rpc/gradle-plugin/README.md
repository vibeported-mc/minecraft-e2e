# `dev.vibeported.rpc`

Wires the RPC compiler plugin into a build, and packages what it emits.

```kotlin
plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("dev.vibeported.rpc")
}

dependencies {
    rpcCompilerPlugin("dev.vibeported.rpc:compiler-plugin")
    implementation("dev.vibeported.rpc:core")
}
```

## What it does

Per source set, not per project, because a manifest describes a compilation and tests are
compilations too:

- adds `rpcCompilerPlugin` to `kotlinCompilerPluginClasspath<SourceSet>`, which is what makes the
  checkers run **in the editor** rather than only in a build — a `-Xplugin=` string in
  `freeCompilerArgs` reaches the compiler just as well, but an IDE never parses it into a plugin;
- points the compiler at `build/generated/rpc/<sourceSet>` for its manifest, and registers that
  directory as both a resource root and a task output, so the manifest lands in the jar and a stale
  one gets rebuilt;
- makes `processResources` wait for the compilation that writes it;
- publishes that same directory into the `classes` secondary variant of `apiElements`.

Without the middle two, a module compiles into tables that nothing can look up, and the failure
arrives much later as a procedure that does not exist.

The last one is subtler and was found the hard way. A resource directory is enough to get the
manifest into the jar — but a consumer compiling against a project is often handed its *class
directories* instead, which is what Gradle's compile avoidance does and what a ModDevGradle project
gets. Those hold no resources, so the compiler plugin downstream read a dependency that appeared to
have declared nothing, and refused a type it had a serializer for all along. Publishing the manifest
as one more artifact of that variant puts it back. Consumers taking the jar never notice.

## There is no configuration

The extension this plugin used to have is gone. What a build once had to name — the types whose
serializers do not come from the class itself — is now declared in Kotlin with `@RpcSerializer`, on
the serializer, in the module owning the type. It travels on the classpath from there, so a
consuming build says nothing at all.

## Why it asks for kotlinx.serialization instead of applying it

Applying it would mean `kotlin-serialization` on this plugin's runtime classpath. This is an
included build, so that classpath is exported onto the consuming script's plugin classpath — a
second copy of a Kotlin subplugin, which is the exact shape of the collision that broke IDE import
for the harness's own Gradle plugin. One line in a build script is the cheaper end of that trade.

## It is an included build

A Gradle plugin cannot be applied by the build that declares it. Under `pluginManagement`:

```kotlin
includeBuild("rpc/gradle-plugin") { name = "rpc-gradle-plugin" }
```

The name is not optional here: Gradle takes an included build's path from its directory name, and
`gradle-plugin` is already taken.
