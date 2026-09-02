package dev.vibeported.mc.e2e.codegen

/**
 * Turns the model into Kotlin source.
 *
 * String templates and nothing else, the way the compiler plugin's index writer assembles JSON by
 * hand: a generator that runs inside a booted Minecraft should drag no library in beside the game,
 * and the output is small enough in shape that a formatter would be the larger dependency.
 */
internal object KotlinWriter {

    const val PACKAGE: String = "dev.vibeported.mc.e2e.blocks"

    private const val BANNER =
        "// Generated from the block registry by :codegen. Edit the generator, not this.\n" +
            "// Regenerate with `gradlew generateBlockDsl`; it also runs when the IDE syncs.\n"

    /** One object per namespace, whose members are that namespace's blocks. */
    fun namespace(
        namespace: String,
        blocks: List<BlockModel>,
        names: Map<Signature, String>,
    ): String = buildString {
        append(BANNER)
        append("@file:Suppress(\"ObjectPropertyName\", \"FunctionName\", \"ClassName\", \"unused\")\n\n")
        append("package $PACKAGE\n\n")
        append("import dev.vibeported.mc.e2e.dsl.BlockSpec\n")
        append("import dev.vibeported.mc.e2e.dsl.blockById\n")
        append("import dev.vibeported.mc.e2e.dsl.blockSpec\n")
        append("import dev.vibeported.mc.e2e.dsl.buildBlock\n\n")
        append("/** Every block the `$namespace` namespace registered, as this build loaded it. */\n")
        append("public object ${namespace.asObjectName()} {\n")

        blocks.sortedBy { it.path }.forEach { block ->
            append("\n")
            if (block.signature.properties.isEmpty()) {
                // Nothing to configure, so nothing to configure it with: a value, not a call.
                append("    public val ${block.path.escaped()}: BlockSpec\n")
                append("        get() = blockSpec(blockById(\"${block.id}\"))\n")
            } else {
                val builder = names.getValue(block.signature)
                append("    public fun ${block.path.escaped()}(body: $builder.() -> Unit = {}): BlockSpec =\n")
                append("        buildBlock($builder(blockById(\"${block.id}\")), body)\n")
            }
        }

        append("}\n")
    }

    /**
     * The builders, shared across every namespace.
     *
     * One per distinct set of properties rather than one per block, because the game has far more
     * blocks than it has shapes of block: every stair in every mod is the same four properties, and
     * emitting that class once is the difference between a few hundred generated classes and a few
     * thousand.
     */
    fun builders(names: Map<Signature, String>): String = buildString {
        append(BANNER)
        append("@file:Suppress(\"ObjectPropertyName\", \"EnumEntryName\", \"ClassName\", \"unused\")\n\n")
        append("package $PACKAGE\n\n")
        append("import dev.vibeported.mc.e2e.dsl.BlockBuilder\n")
        append("import net.minecraft.world.level.block.Block\n\n")

        names.entries.sortedBy { it.value }.forEach { (signature, name) ->
            append(builder(signature, name))
            append("\n")
        }
    }

    private fun builder(signature: Signature, name: String): String = buildString {
        val properties = signature.properties
        append("/** Blocks whose properties are ${properties.joinToString(", ") { it.name }}. */\n")
        append("public class $name internal constructor(block: Block) : BlockBuilder(block) {\n")

        properties.filter { it.kind == Kind.ENUM }.forEach { append(valueEnum(it)) }
        properties.forEach { append(setter(it)) }
        properties.forEach { append(aliases(it, signature)) }

        append("\n    override fun apply() {\n")
        properties.forEach { append("        ${write(it)}\n") }
        append("    }\n}\n")
    }

    /** The values this property accepts, and only those -- the whole reason for a generated type. */
    private fun valueEnum(property: PropertyModel): String = buildString {
        append("\n    public enum class ${property.typeName}(internal val raw: ${property.valueType}) {\n")
        property.values.forEach { value ->
            append("        ${value.dslName.escaped()}(${property.valueType}.${value.constant}),\n")
        }
        append("        ;\n")
        append("\n        internal companion object {\n")
        append("            fun of(raw: ${property.valueType}): ${property.typeName} =\n")
        append("                entries.first { it.raw == raw }\n")
        append("        }\n")
        append("    }\n")
    }

    private fun setter(property: PropertyModel): String {
        val name = property.name.escaped()
        return when (property.kind) {
            Kind.BOOLEAN ->
                "\n    public var $name: Boolean = initial(\"${property.name}\")\n"

            Kind.INTEGER ->
                "\n    /** ${property.range?.first}..${property.range?.last}. */\n" +
                    "    public var $name: Int = initial(\"${property.name}\")\n"

            Kind.ENUM ->
                "\n    public var $name: ${property.typeName} =\n" +
                    "        ${property.typeName}.of(initial(\"${property.name}\"))\n"
        }
    }

    /**
     * `facing = north` rather than `facing = Facing.north`, by naming every value in the builder's
     * own scope.
     *
     * Two properties can want the same word -- `half` offers `top` and so does `type` -- and where
     * they do the second is spelled `type_top`. The first is decided by property order, which is
     * alphabetical, so the choice does not move when a block is added elsewhere.
     */
    private fun aliases(property: PropertyModel, signature: Signature): String {
        if (property.kind != Kind.ENUM) return ""

        val taken = signature.properties
            .takeWhile { it != property }
            .filter { it.kind == Kind.ENUM }
            .flatMap { other -> other.values.map { it.dslName } }
            .toSet()

        return buildString {
            append("\n")
            property.values.forEach { value ->
                val alias = if (value.dslName in taken) "${property.name}_${value.dslName}" else value.dslName
                append("    public val ${alias.escaped()}: ${property.typeName}\n")
                append("        get() = ${property.typeName}.${value.dslName.escaped()}\n")
            }
        }
    }

    private fun write(property: PropertyModel): String {
        val name = property.name
        return when (property.kind) {
            Kind.BOOLEAN -> "set(\"$name\", ${name.escaped()})"
            Kind.INTEGER -> "set(\"$name\", checked(\"$name\", ${name.escaped()}))"
            Kind.ENUM -> "set(\"$name\", ${name.escaped()}.raw)"
        }
    }
}
