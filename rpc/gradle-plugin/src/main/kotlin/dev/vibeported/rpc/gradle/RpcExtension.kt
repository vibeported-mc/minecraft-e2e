package dev.vibeported.rpc.gradle

import org.gradle.api.provider.ListProperty

/**
 * What a build tells the RPC compiler plugin about itself.
 *
 * One thing, so far, and it exists because a procedure body's parameter types are *inferred*:
 * `server(pos) { p -> }` names no type anywhere, so for a type this build does not own -- one from a
 * game, say -- there is nowhere to write `@Contextual`. The build names it here instead, and
 * registers the matching serializer on the wire format at run time.
 *
 * ```kotlin
 * rpc {
 *     contextual.add("net.minecraft.core.BlockPos")
 * }
 * ```
 *
 * Naming a type here is a promise, and it is still checked: the type has to be named to be allowed,
 * so a typo is a compile error rather than a serializer that quietly is not there.
 */
public abstract class RpcExtension {
    public abstract val contextual: ListProperty<String>
}
