package dev.vibeported.mc.driver

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.item.ItemParser
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack

/**
 * A stack written the way the `/give` command writes one.
 *
 * `"minecraft:diamond_sword"`, or `"minecraft:diamond_sword[minecraft:damage=10]"`, parsed by the
 * game's own parser -- so every form that command accepts works here and a name or a component that
 * does not exist fails with Mojang's message.
 *
 * Text rather than an `ItemStack`, and that is forced rather than chosen. A driver runs no game, so
 * nothing has loaded a datapack in that process; item components are bound while a server loads its
 * resources, and until they are, merely *constructing* `ItemStack(Items.DIAMOND_SWORD)` throws
 * `NullPointerException: Components not bound yet` from inside `Item.components()`. Building the
 * stack where the registries actually exist is the only arrangement that works, and it is the same
 * one blocks already use.
 *
 * Parsed against the server's registry access rather than the built-in one, so a modded item, and a
 * component from a datapack, are both reachable.
 */
internal fun MinecraftServer.parseItem(text: String, count: Int): ItemStack = try {
    ItemParser(registryAccess()).parse(com.mojang.brigadier.StringReader(text)).createItemStack(count)
} catch (invalid: CommandSyntaxException) {
    throw IllegalArgumentException("mcdriver: `$text` is not an item: ${invalid.message}", invalid)
}
