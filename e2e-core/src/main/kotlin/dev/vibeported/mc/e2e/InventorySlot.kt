package dev.vibeported.mc.e2e

import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.InventoryMenu

/**
 * A slot that exists only while a menu is open, so nothing in the player holds it.
 *
 * A top-level constant rather than a companion one: an enum constant is built before its
 * own companion object is.
 */
private const val NOT_IN_PLAYER: Int = -1

/**
 * A slot of the player inventory, named rather than numbered.
 *
 * The two sides count slots differently -- a container menu has its crafting grid and armour first,
 * an `Inventory` has the hotbar first -- and a test should not have to hold both layouts in its
 * head. So a slot carries both indices and the same name means the same square everywhere: the
 * server puts an item in `INV_1_1` and the client drags it out of `INV_1_1`.
 *
 * Every index is derived from Minecraft's own constants, so a renumbering upstream is a compile
 * error here rather than a test that quietly drags the wrong square.
 */
public enum class InventorySlot(
    /** Where this slot sits in the open [InventoryMenu], which is what a click addresses. */
    public val menuIndex: Int,
    /**
     * Where it sits in the player's [Inventory], which is what server-side code addresses.
     *
     * [NOT_IN_INVENTORY] for the crafting grid, which belongs to the menu and not to the player.
     */
    public val inventoryIndex: Int,
) {

    /** Hotbar slot 1. */
    HOTBAR_1(InventoryMenu.USE_ROW_SLOT_START + 0, 0),
    /** Hotbar slot 2. */
    HOTBAR_2(InventoryMenu.USE_ROW_SLOT_START + 1, 1),
    /** Hotbar slot 3. */
    HOTBAR_3(InventoryMenu.USE_ROW_SLOT_START + 2, 2),
    /** Hotbar slot 4. */
    HOTBAR_4(InventoryMenu.USE_ROW_SLOT_START + 3, 3),
    /** Hotbar slot 5. */
    HOTBAR_5(InventoryMenu.USE_ROW_SLOT_START + 4, 4),
    /** Hotbar slot 6. */
    HOTBAR_6(InventoryMenu.USE_ROW_SLOT_START + 5, 5),
    /** Hotbar slot 7. */
    HOTBAR_7(InventoryMenu.USE_ROW_SLOT_START + 6, 6),
    /** Hotbar slot 8. */
    HOTBAR_8(InventoryMenu.USE_ROW_SLOT_START + 7, 7),
    /** Hotbar slot 9. */
    HOTBAR_9(InventoryMenu.USE_ROW_SLOT_START + 8, 8),
    /** Row 1, slot 1 of the main inventory. */
    INV_1_1(InventoryMenu.INV_SLOT_START + 0, 9),
    /** Row 1, slot 2 of the main inventory. */
    INV_1_2(InventoryMenu.INV_SLOT_START + 1, 10),
    /** Row 1, slot 3 of the main inventory. */
    INV_1_3(InventoryMenu.INV_SLOT_START + 2, 11),
    /** Row 1, slot 4 of the main inventory. */
    INV_1_4(InventoryMenu.INV_SLOT_START + 3, 12),
    /** Row 1, slot 5 of the main inventory. */
    INV_1_5(InventoryMenu.INV_SLOT_START + 4, 13),
    /** Row 1, slot 6 of the main inventory. */
    INV_1_6(InventoryMenu.INV_SLOT_START + 5, 14),
    /** Row 1, slot 7 of the main inventory. */
    INV_1_7(InventoryMenu.INV_SLOT_START + 6, 15),
    /** Row 1, slot 8 of the main inventory. */
    INV_1_8(InventoryMenu.INV_SLOT_START + 7, 16),
    /** Row 1, slot 9 of the main inventory. */
    INV_1_9(InventoryMenu.INV_SLOT_START + 8, 17),
    /** Row 2, slot 1 of the main inventory. */
    INV_2_1(InventoryMenu.INV_SLOT_START + 9, 18),
    /** Row 2, slot 2 of the main inventory. */
    INV_2_2(InventoryMenu.INV_SLOT_START + 10, 19),
    /** Row 2, slot 3 of the main inventory. */
    INV_2_3(InventoryMenu.INV_SLOT_START + 11, 20),
    /** Row 2, slot 4 of the main inventory. */
    INV_2_4(InventoryMenu.INV_SLOT_START + 12, 21),
    /** Row 2, slot 5 of the main inventory. */
    INV_2_5(InventoryMenu.INV_SLOT_START + 13, 22),
    /** Row 2, slot 6 of the main inventory. */
    INV_2_6(InventoryMenu.INV_SLOT_START + 14, 23),
    /** Row 2, slot 7 of the main inventory. */
    INV_2_7(InventoryMenu.INV_SLOT_START + 15, 24),
    /** Row 2, slot 8 of the main inventory. */
    INV_2_8(InventoryMenu.INV_SLOT_START + 16, 25),
    /** Row 2, slot 9 of the main inventory. */
    INV_2_9(InventoryMenu.INV_SLOT_START + 17, 26),
    /** Row 3, slot 1 of the main inventory. */
    INV_3_1(InventoryMenu.INV_SLOT_START + 18, 27),
    /** Row 3, slot 2 of the main inventory. */
    INV_3_2(InventoryMenu.INV_SLOT_START + 19, 28),
    /** Row 3, slot 3 of the main inventory. */
    INV_3_3(InventoryMenu.INV_SLOT_START + 20, 29),
    /** Row 3, slot 4 of the main inventory. */
    INV_3_4(InventoryMenu.INV_SLOT_START + 21, 30),
    /** Row 3, slot 5 of the main inventory. */
    INV_3_5(InventoryMenu.INV_SLOT_START + 22, 31),
    /** Row 3, slot 6 of the main inventory. */
    INV_3_6(InventoryMenu.INV_SLOT_START + 23, 32),
    /** Row 3, slot 7 of the main inventory. */
    INV_3_7(InventoryMenu.INV_SLOT_START + 24, 33),
    /** Row 3, slot 8 of the main inventory. */
    INV_3_8(InventoryMenu.INV_SLOT_START + 25, 34),
    /** Row 3, slot 9 of the main inventory. */
    INV_3_9(InventoryMenu.INV_SLOT_START + 26, 35),
    HELMET(InventoryMenu.ARMOR_SLOT_START + 0, 39),
    CHESTPLATE(InventoryMenu.ARMOR_SLOT_START + 1, 38),
    LEGGINGS(InventoryMenu.ARMOR_SLOT_START + 2, 37),
    BOOTS(InventoryMenu.ARMOR_SLOT_START + 3, 36),
    OFFHAND(InventoryMenu.SHIELD_SLOT, Inventory.SLOT_OFFHAND),
    CRAFT_1(InventoryMenu.CRAFT_SLOT_START + 0, NOT_IN_PLAYER),
    CRAFT_2(InventoryMenu.CRAFT_SLOT_START + 1, NOT_IN_PLAYER),
    CRAFT_3(InventoryMenu.CRAFT_SLOT_START + 2, NOT_IN_PLAYER),
    CRAFT_4(InventoryMenu.CRAFT_SLOT_START + 3, NOT_IN_PLAYER),
    CRAFT_RESULT(InventoryMenu.RESULT_SLOT, NOT_IN_PLAYER),
    ;

    public val isInPlayerInventory: Boolean get() = inventoryIndex != NOT_IN_INVENTORY

    public companion object {
        /** A slot that exists only while a menu is open, so nothing in the player holds it. */
        public const val NOT_IN_INVENTORY: Int = NOT_IN_PLAYER

        /** The nine hotbar slots in order, so `hotbar(inventory.selectedSlot)` is expressible. */
        public fun hotbar(index: Int): InventorySlot = entries[index]
    }
}
