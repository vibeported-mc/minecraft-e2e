package dev.vibeported.rpc.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * Carries what the frontend knows about roles across to the backend.
 *
 * There is no nicer way to do this. `@RpcRole` must be SOURCE-retained, because Kotlin permits no
 * other retention on an expression target, so by the time IR runs the annotation is simply not
 * there -- while IR is exactly where a body gets lifted into a table and therefore where the role
 * decides which table that is.
 *
 * So the frontend writes down what it saw and the backend reads it back, keyed by where in the
 * source the call was. Both halves run in one compiler invocation over the same files, which is what
 * makes a position a usable key at all.
 */
internal object RoleIndex {

    private val roles = ConcurrentHashMap<Key, String>()

    /** Also recorded when no role applies, so the backend can tell "none" from "never seen". */
    private val seen = ConcurrentHashMap<Key, Boolean>()

    data class Key(val file: String, val offset: Int)

    fun record(file: String, offset: Int, role: String?) {
        val key = Key(normalise(file), offset)
        seen[key] = true
        if (role != null) roles[key] = role
    }

    fun roleAt(file: String, offset: Int): String? = roles[Key(normalise(file), offset)]

    fun wasSeen(file: String, offset: Int): Boolean = seen[Key(normalise(file), offset)] == true

    fun reset() {
        roles.clear()
        seen.clear()
    }

    /**
     * The frontend and the backend do not spell a path the same way.
     *
     * One reports what the build handed it and the other what the file entry records, which differ
     * by separator and by being absolute or not. The file name alone is enough to disambiguate an
     * offset, since two files are never compiled as one.
     */
    private fun normalise(path: String): String =
        path.replace('\\', '/').substringAfterLast('/')
}
