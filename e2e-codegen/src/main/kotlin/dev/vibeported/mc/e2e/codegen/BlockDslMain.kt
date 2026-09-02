package dev.vibeported.mc.e2e.codegen

import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import net.neoforged.neoforge.server.loading.ServerModLoader
import java.io.File

/**
 * Reads every registered block and writes the Kotlin that names it.
 *
 * Runs inside a booted FancyModLoader, which is the only place the answer exists: which blocks there
 * are, which properties each has, and which values each property will take are all decided by code
 * running -- a modded block registers itself, and a property like a hopper's `facing` excludes `up`
 * through a predicate. None of it can be read off a jar.
 */
public object BlockDslMain {

    /** Where to write. Handed over as a system property, the way the launch plan already is. */
    private const val OUT = "e2e.blockdsl.out"

    /** Optional comma-separated allow-list of namespaces; empty means every one. */
    private const val NAMESPACES = "e2e.blockdsl.namespaces"

    @JvmStatic
    public fun main(args: Array<String>) {
        val out = File(System.getProperty(OUT) ?: error("Set -D$OUT to the directory to write into"))
        val wanted = System.getProperty(NAMESPACES)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        bootstrapGame()

        val blocks = collect(wanted)
        if (blocks.isEmpty()) {
            error("No blocks matched ${if (wanted.isEmpty()) "any namespace" else wanted.toString()}")
        }

        write(out, blocks)
        println("[e2e-codegen] wrote ${blocks.size} blocks in ${blocks.map { it.namespace }.distinct().size} namespaces to $out")
    }

    /**
     * The three lines that turn a loaded mod set into a populated registry.
     *
     * Copied from NeoForge's own `net.neoforged.neoforge.junit.JUnitMain`, which is the supported
     * "bring the game up but do not start it" sequence. No `MinecraftServer` is constructed.
     */
    private fun bootstrapGame() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
        ServerModLoader.load(false)
    }

    private fun collect(wanted: Set<String>): List<BlockModel> =
        BuiltInRegistries.BLOCK.mapNotNull { block ->
            val id = BuiltInRegistries.BLOCK.getKey(block) ?: return@mapNotNull null
            if (wanted.isNotEmpty() && id.namespace !in wanted) return@mapNotNull null
            BlockModel(
                namespace = id.namespace,
                path = id.path,
                id = id.toString(),
                signature = signatureOf(block),
            )
        }

    private fun signatureOf(block: Block): Signature =
        Signature(block.stateDefinition.properties.sortedBy { it.name }.map(::modelOf))

    private fun modelOf(property: Property<*>): PropertyModel = when (property) {
        is BooleanProperty -> PropertyModel(property.name, Kind.BOOLEAN)

        is IntegerProperty -> {
            val values = property.possibleValues
            PropertyModel(property.name, Kind.INTEGER, range = values.min()..values.max())
        }

        else -> PropertyModel(
            name = property.name,
            kind = Kind.ENUM,
            // The declared class, not the value's own: an enum constant with a body is its own
            // anonymous subclass, and naming that would emit a type nothing can reference.
            valueType = property.valueClass.name.replace('$', '.'),
            values = valuesOf(property),
        )
    }

    /**
     * The legal values, named as the game names them.
     *
     * Through `getAllValues` rather than `getPossibleValues` because `Property.Value` carries the
     * serialized name alongside the value, which sidesteps calling the generic `getName(T)` from a
     * star-projected `Property<*>` -- a cast Kotlin cannot be talked into.
     */
    private fun valuesOf(property: Property<*>): List<ValueModel> {
        val values = mutableListOf<ValueModel>()
        property.allValues.forEach { value ->
            val constant = value.value() as? Enum<*>
                ?: error("`${property.name}` has a non-enum value ${value.value()}, which is not modelled")
            values += ValueModel(dslName = value.valueName(), constant = constant.name)
        }
        return values
    }

    private fun write(out: File, blocks: List<BlockModel>) {
        // Cleared rather than merged: a block that a mod stopped registering has to stop being
        // nameable, or the next compile succeeds against a world that cannot exist.
        out.deleteRecursively()
        out.mkdirs()

        // Named once, for every signature at once, because a name can only be known to be free
        // when the whole set is in view.
        val names = nameSignatures(blocks.map { it.signature }.distinct())

        File(out, "Builders.kt").writeText(KotlinWriter.builders(names), Charsets.UTF_8)

        blocks.groupBy { it.namespace }.forEach { (namespace, inNamespace) ->
            File(out, "${namespace.asObjectName()}.kt")
                .writeText(KotlinWriter.namespace(namespace, inNamespace, names), Charsets.UTF_8)
        }
    }
}
