package dev.vibeported.rpc.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Carries what the frontend knows about roles across to the backend.
 *
 * There is no nicer way to do this. `@RpcRole` must be SOURCE-retained, because Kotlin permits no
 * other retention on an expression target, so by the time IR runs the annotation is simply not
 * there -- while IR is exactly where a body gets lifted into a table and therefore where the role
 * decides which table that is. So the frontend writes down what it saw and the backend reads it
 * back.
 *
 * **One of these per compilation, never a singleton.** It is created in `registerExtensions` and
 * handed to both halves, which is what confines it to the module being compiled. A shared object
 * would outlive every compilation in a Gradle daemon: entries would accumulate, and two modules
 * that both contain a `Main.kt` would read each other's roles -- quietly, and only when their
 * offsets happened to coincide.
 */
internal class RoleIndex {

    private val roles = ConcurrentHashMap<Key, String>()

    /** Recorded even when no role applies, so the backend can tell "none" from "never seen". */
    private val seen = ConcurrentHashMap<Key, Boolean>()

    /**
     * Where a call is, said in a way both halves spell identically.
     *
     * The package as well as the file name, because one module may hold several `Main.kt` in
     * different packages, and an offset alone does not tell them apart. The full path would be
     * better still, but the two halves do not agree on one: the frontend reports what the build
     * handed it and the backend what the file entry records, differing by separator and by being
     * absolute.
     */
    private data class Key(val packageName: String, val fileName: String, val offset: Int)

    fun record(packageName: String, filePath: String, offset: Int, role: String?) {
        val key = Key(packageName, fileNameOf(filePath), offset)
        seen[key] = true
        if (role != null) roles[key] = role
    }

    fun roleAt(packageName: String, filePath: String, offset: Int): String? =
        roles[Key(packageName, fileNameOf(filePath), offset)]

    fun wasSeen(packageName: String, filePath: String, offset: Int): Boolean =
        seen[Key(packageName, fileNameOf(filePath), offset)] == true

    private fun fileNameOf(path: String): String =
        path.replace('\\', '/').substringAfterLast('/')
}
