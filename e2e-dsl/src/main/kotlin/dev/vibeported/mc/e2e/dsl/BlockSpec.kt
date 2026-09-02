package dev.vibeported.mc.e2e.dsl

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * A block and the properties it should be placed with.
 *
 * The whole runtime the generated block DSL sits on is this file. Everything specific to a block --
 * which properties it has, which values each of those will take -- is generated, and generated code
 * is worth keeping thin: a rule that lives here is written once, while a rule that lives in the
 * generator is emitted a thousand times.
 */
public class BlockSpec internal constructor(public val state: BlockState) {

    override fun toString(): String = state.toString()
}

/**
 * The block a generated name refers to.
 *
 * By id rather than by a static field, because a modded block has no field anyone outside that mod
 * can name. The lookup happens per use and costs a map read; making it a stored value instead would
 * pin every block in the game at class-initialisation time, before a test has said which it wants.
 */
public fun blockById(id: String): Block =
    BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))

/** A block with nothing to configure. @see BlockSpec */
public fun blockSpec(block: Block): BlockSpec = BlockSpec(block.defaultBlockState())

/**
 * The base of every generated builder.
 *
 * A subclass declares one `var` per property of its block, initialised from the default state, and
 * an [apply] that writes them all back. Writing them all back unconditionally -- rather than
 * tracking which the caller touched -- is deliberate: setting a property to the value it already had
 * is what the default state already says, so the two are indistinguishable and the bookkeeping would
 * buy nothing.
 *
 * The properties are looked up by name off the block's own [net.minecraft.world.level.block.state.StateDefinition]
 * rather than referenced as constants, because a modded block's properties are its own objects and
 * there is no shared table to name them from.
 */
public abstract class BlockBuilder protected constructor(private val block: Block) {

    private var state: BlockState = block.defaultBlockState()

    /** Reads a property of the default state, which is where a generated `var` starts. */
    protected fun <T : Comparable<T>> initial(name: String): T = state.getValue(property<T>(name))

    /** Writes one property. Called by [apply], once per declared `var`. */
    protected fun <T : Comparable<T>> set(name: String, value: T) {
        state = state.setValue(property(name), value)
    }

    /**
     * An integer property refuses values outside its range, and refuses them here rather than
     * several ticks later as a block that is not the one the test asked for.
     */
    protected fun checked(name: String, value: Int): Int {
        val allowed = property<Int>(name).possibleValues
        require(value in allowed) {
            "`$name` of ${block.descriptionId} accepts ${allowed.min()}..${allowed.max()}, not $value"
        }
        return value
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Comparable<T>> property(name: String): Property<T> =
        block.stateDefinition.getProperty(name) as? Property<T>
            ?: error("${block.descriptionId} has no property `$name`; the generated DSL is stale")

    /** Writes every declared property back. Generated. */
    protected abstract fun apply()

    internal fun build(): BlockSpec {
        apply()
        return BlockSpec(state)
    }
}

/**
 * Runs a generated builder and takes the state it produced.
 *
 * Generated code calls this and nothing else, so the `internal` [BlockBuilder.build] stays internal
 * and a suite cannot reach past the DSL into a half-configured builder.
 */
public fun <B : BlockBuilder> buildBlock(builder: B, body: B.() -> Unit): BlockSpec {
    builder.body()
    return builder.build()
}
