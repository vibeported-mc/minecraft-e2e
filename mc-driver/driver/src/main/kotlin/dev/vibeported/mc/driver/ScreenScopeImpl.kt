package dev.vibeported.mc.driver

import dev.vibeported.mc.driver.mixin.ContainerScreenAccessor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.world.item.ItemStack
import kotlin.time.Duration

/**
 * The [ScreenScope] members, written against whatever screen is open right now.
 *
 * Kept out of [GameScope] because they are one subject rather than another accessor apiece, and put
 * behind an interface the node provides so `waitForScreen` needs no per-call state: every member
 * starts by asking the client what screen it has, so there is nothing to hold and nothing to go
 * stale. The cost is the other side of that coin -- a body that keeps a reference across a
 * suspension can find the screen closed underneath it.
 */
internal interface ScreenAccess : ScreenScope {

    /** The container screen this client has open, or a failure saying what it has instead. */
    val screen: AbstractContainerScreen<*>
        get() = minecraft.gui.screen() as? AbstractContainerScreen<*>
            ?: error(
                "mcdriver: `$clientName` has no container screen open" +
                    (currentScreen()?.let { ", it is showing $it" } ?: "")
            )

    override val selectedHotbar: InventorySlot
        get() = InventorySlot.hotbar(
            clientPlayer?.inventory?.selectedSlot
                ?: error("mcdriver: a screen is open but `$clientName` has no player")
        )

    override fun stackAt(slot: InventorySlot): ItemStack = screen.menu.slots[slot.menuIndex].item

    override val carried: ItemStack get() = screen.menu.carried

    /**
     * Which slot the screen believes the pointer is over, if any.
     *
     * The screen's own answer rather than ours, so it is worth asking: when a click is not landing,
     * this is what says whether the pointer is where it was aimed.
     */
    override val hoveredSlot: InventorySlot?
        get() {
            val open = screen
            val slot = open.slotUnderMouse ?: return null
            val index = open.menu.slots.indexOf(slot)
            return InventorySlot.entries.firstOrNull { it.menuIndex == index }
        }

    /** Where the client itself thinks the pointer is, in GUI coordinates. */
    override val pointerInGui: Pair<Double, Double>
        get() = minecraft.mouseHandler.getScaledXPos(minecraft.window) to
            minecraft.mouseHandler.getScaledYPos(minecraft.window)

    override suspend fun moveToSlot(slot: InventorySlot, over: Duration) {
        val (x, y) = slotPosition(slot)
        moveMouseTo(x, y, over)
    }

    /**
     * Takes what is in [slot] onto the cursor.
     *
     * A click rather than a held button, because that is what picking something up is in Minecraft:
     * holding the button and moving is the quick-craft gesture, which spreads a carried stack over
     * the slots it passes and puts a single item back where the drag began.
     */
    override suspend fun pickUp(slot: InventorySlot, over: Duration) {
        moveToSlot(slot, over)
        click()
    }

    /** Puts what is on the cursor into [slot]. @see pickUp */
    override suspend fun dropOn(slot: InventorySlot, over: Duration) {
        moveToSlot(slot, over)
        click()
    }

    /**
     * Carries what is in [from] over to [to], and returns once it has really arrived.
     *
     * Nothing a caller could not have written out of [pickUp] and [dropOn]; what it adds is the
     * wait, so the line after it is not racing the round trip to the server that every slot click is.
     */
    override suspend fun swapSlot(from: InventorySlot, to: InventorySlot, over: Duration) {
        val moved = stackAt(from).copy()
        pickUp(from, over)
        dropOn(to, over)
        awaitUntil { ItemStack.isSameItemSameComponents(stackAt(to), moved) }
    }

    override suspend fun click(button: MouseButton) {
        mouseDown(button)
        awaitTicks()
        mouseUp(button)
    }

    /**
     * Where to point at a slot, in window pixels.
     *
     * A slot knows only where it sits inside the screen, and the screen is centred on a window whose
     * size somebody else chose, so both halves and the GUI scale are needed to land on it.
     */
    private fun slotPosition(slot: InventorySlot): Pair<Double, Double> {
        val open = screen
        val menuSlot = open.menu.slots[slot.menuIndex]
        val origin = open as ContainerScreenAccessor
        val guiX = (origin.leftPos + menuSlot.x + SLOT_CENTRE).toDouble()
        val guiY = (origin.topPos + menuSlot.y + SLOT_CENTRE).toDouble()
        return SyntheticInput.guiToWindowX(minecraft, guiX) to
            SyntheticInput.guiToWindowY(minecraft, guiY)
    }

    private companion object {
        /** A slot is 18 pixels across in GUI space, so its middle is 9 in. */
        const val SLOT_CENTRE = 9
    }
}
