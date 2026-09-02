package dev.vibeported.mc.e2e.codegen

/**
 * What the generator learned from the registry, in the shape the emitter needs.
 *
 * Deliberately free of Minecraft types. Everything the game can tell us is read once, in
 * [BlockDslMain], and from here on it is names and strings -- so the emitter can be reasoned about,
 * and one day tested, without a booted game behind it.
 */
internal data class BlockModel(
    val namespace: String,
    /** The path of the id, which is also the member name: `bamboo_mosaic_stairs`. */
    val path: String,
    /** The whole id, as the generated code will look it up: `minecraft:bamboo_mosaic_stairs`. */
    val id: String,
    val signature: Signature,
)

/**
 * The properties of a block, and what each will accept.
 *
 * This is the grouping key, and it includes the *values* rather than only the names: a `facing` that
 * takes six directions and a `facing` that takes four are different things to a caller, and giving
 * them one builder would be the one way this DSL could still let an illegal state compile.
 */
internal data class Signature(val properties: List<PropertyModel>) {

    /** Stable across runs, and depends on nothing but this signature. @see nameSignatures */
    val fingerprint: String =
        Integer.toHexString(properties.joinToString(";") { it.fingerprint }.hashCode())

    /**
     * What the builder would be called if no other signature wanted the same name.
     *
     * Named after the properties, because that is what a reader of a compiler error needs. It is not
     * enough on its own: `age` over 0..7 and `age` over 0..15 are different signatures with the same
     * properties, so [nameSignatures] settles the ties.
     */
    val baseName: String = buildString {
        append("Block")
        properties.forEach { append(it.name.pascal()) }
    }.let { if (it.length <= MAX_CLASS_NAME) it else "Block$fingerprint" }

    private companion object {
        /** Long enough for the compound blocks, short enough to read in a compiler error. */
        const val MAX_CLASS_NAME = 90
    }
}

/**
 * A class name for each signature, unique across the lot.
 *
 * A signature keeps its plain name when nothing else wants it, and takes a fingerprint when
 * something does. The fingerprint depends only on the signature itself, so a mod that adds a block
 * can only ever disturb the names in its own collision group -- everything else is spelled the same
 * as it was before.
 */
internal fun nameSignatures(signatures: List<Signature>): Map<Signature, String> {
    val contested = signatures.groupBy { it.baseName }.filterValues { it.size > 1 }.keys
    return signatures.associateWith { signature ->
        if (signature.baseName in contested) {
            "${signature.baseName}_${signature.fingerprint}"
        } else {
            signature.baseName
        }
    }
}

internal data class PropertyModel(
    val name: String,
    val kind: Kind,
    /** Enum only: the Minecraft type the values belong to, fully qualified. */
    val valueType: String? = null,
    /** Enum only, in the game's own order. */
    val values: List<ValueModel> = emptyList(),
    /** Integer only. */
    val range: IntRange? = null,
) {
    /** Everything that makes two properties interchangeable to a caller. @see Signature */
    val fingerprint: String = when (kind) {
        Kind.BOOLEAN -> "$name:bool"
        Kind.INTEGER -> "$name:int:${range?.first}..${range?.last}"
        Kind.ENUM -> "$name:$valueType:" + values.joinToString(",") { it.dslName }
    }

    /** The nested type the generated builder declares for this property's values. */
    val typeName: String = name.pascal()
}

internal enum class Kind { BOOLEAN, INTEGER, ENUM }

internal data class ValueModel(
    /** As the game names it, which is what the DSL calls it: `north`, `inner_left`. */
    val dslName: String,
    /** The JVM enum constant, which is not always the upper-cased name: `NORTH`. */
    val constant: String,
)

/** `axis_along_first` -> `AxisAlongFirst`. */
internal fun String.pascal(): String =
    split('_').filter { it.isNotEmpty() }.joinToString("") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

/**
 * Wraps a name in backticks when Kotlin would otherwise read it as syntax.
 *
 * Registry ids are lower-snake and mostly harmless, but nothing stops a mod from registering `object`
 * or `in`, and a generator that emits one broken file breaks the whole build rather than one line.
 */
internal fun String.escaped(): String =
    if (this in KOTLIN_KEYWORDS || !IDENTIFIER.matches(this)) "`$this`" else this

/**
 * A namespace as a Kotlin object name.
 *
 * A mod id may contain characters an identifier may not -- `-` and `.` are both legal in one -- and
 * backticks do not help, because a JVM name cannot hold a dot. The id itself is untouched; only the
 * name we call it by is folded.
 */
internal fun String.asObjectName(): String {
    val folded = replace(Regex("[^A-Za-z0-9_]"), "_")
    return if (folded.firstOrNull()?.isDigit() == true) "_$folded" else folded
}

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/**
 * Hard keywords, and the soft ones too.
 *
 * The soft ones are the reason this list is long. `data` is not reserved, but an enum entry called
 * `data` -- which is exactly what a structure block's mode is called -- reads as a modifier on the
 * entry after it, and the file stops parsing several lines later with an error that names neither.
 * A name backticked without need costs a pair of characters; a name that needed it and did not get
 * it costs the whole generated file.
 */
private val KOTLIN_KEYWORDS = setOf(
    // Hard keywords.
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
    "true", "try", "typealias", "typeof", "val", "var", "when", "while",
    // Modifier keywords, which are read as modifiers wherever a declaration may start.
    "abstract", "actual", "annotation", "companion", "const", "crossinline", "data", "enum",
    "expect", "external", "final", "infix", "init", "inline", "inner", "internal", "lateinit",
    "noinline", "open", "operator", "out", "override", "private", "protected", "public",
    "reified", "sealed", "suspend", "tailrec", "value", "vararg",
    // Soft keywords and names with meaning in the positions this generator writes into.
    "by", "catch", "constructor", "delegate", "dynamic", "field", "file", "finally", "get",
    "import", "it", "param", "property", "receiver", "set", "setparam", "where",
)
