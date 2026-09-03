package dev.vibeported.rpc.plugin

import java.io.File
import java.util.zip.ZipFile

/**
 * Which types this compilation may send, because something wrote a serializer for them.
 *
 * Two sources, and both are needed. Types come from the classpath, so a module depending on one that
 * declares serializers inherits them with nothing to configure; and from this compilation's own
 * `@RpcSerializer` objects, so a module can declare one and use it in the same breath.
 *
 * One index per compilation, created by the registrar and given to both halves of the plugin -- the
 * frontend to stop refusing these types, the backend to emit a lookup against the wire format's
 * module instead of against the class. A shared object would outlive every compilation in a Gradle
 * daemon and let one module send a type another one made serializable.
 */
internal class SerializerIndex(classpath: List<File> = emptyList()) {

    /** Type fully-qualified name -> the object serializing it. */
    private val known = LinkedHashMap<String, String>()

    /** Declared in this compilation, so the backend can write them into this module's manifest. */
    private val declaredHere = LinkedHashMap<String, String>()

    init {
        classpath.forEach { root -> readFrom(root)?.let(::merge) }
    }

    private var discovered = false

    /** True the first time it is called, so a lazy discovery pass runs exactly once. */
    fun markDiscovered(): Boolean {
        if (discovered) return false
        discovered = true
        return true
    }

    /** Records an `@RpcSerializer` object found in the sources being compiled. */
    fun declare(type: String, serializer: String) {
        known[type] = serializer
        declaredHere[type] = serializer
    }

    fun covers(type: String): Boolean = type in known

    fun declarations(): Map<String, String> = declaredHere

    private fun merge(entries: Map<String, String>) {
        entries.forEach { (type, serializer) -> known.putIfAbsent(type, serializer) }
    }

    /**
     * Reads one classpath entry's manifest, whether it is a jar or a directory of classes.
     *
     * Parsed by hand for the same reason it is written by hand: the compiler's classpath is not the
     * place to insist on a serialization library, and the shape is fixed because this plugin is the
     * only thing that writes it.
     */
    private fun readFrom(root: File): Map<String, String>? = try {
        when {
            root.isDirectory -> File(root, RESOURCE).takeIf { it.isFile }?.readText()
            root.isFile && root.name.endsWith(".jar") -> ZipFile(root).use { jar ->
                jar.getEntry(RESOURCE)?.let { entry -> jar.getInputStream(entry).reader().readText() }
            }

            else -> null
        }?.let(::parse)
    } catch (unreadable: Exception) {
        // A classpath entry that cannot be read is not this plugin's problem to report: the
        // compiler will say so far better when it fails to resolve something out of it.
        null
    }

    private companion object {
        const val RESOURCE = "META-INF/rpc/serializers.json"

        /** `"type": "…", "serializer": "…"` pairs, in the order written. */
        private val PAIR = Regex(""""type"\s*:\s*"([^"]+)"\s*,\s*"serializer"\s*:\s*"([^"]+)"""")

        fun parse(text: String): Map<String, String> =
            PAIR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }
}
