package dev.vibeported.mc.e2e

import dev.vibeported.mc.e2e.mc.SyntheticInput
import dev.vibeported.mc.e2e.mixin.ContainerScreenAccessor
import dev.vibeported.mc.e2e.protocol.E2eAssertionError
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.item.ItemStack
import kotlin.time.Duration

/**
 * Opens the player inventory, runs [body] inside it, and closes it again.
 *
 * It opens by **pressing the key**, whatever that key is bound to, rather than by handing Minecraft
 * a new screen. A test that called `setScreen` would prove nothing about the client: the keybind,
 * the screen stack and every mod hook along the way are exactly what is under test here.
 */
public suspend fun ClientScope.playerInventory(body: suspend InventoryScope.() -> Unit) {
    press(Key(minecraft.options.keyInventory.key.value))
    val screen = awaitScreen<InventoryScreen>()

    try {
        InventoryScope(this, screen).body()
    } finally {
        press(Key.ESCAPE)
        awaitNoScreen()
    }
}

/**
 * The inside of an open inventory.
 *
 * Slot geometry is public here rather than hidden inside a drag helper, because the interesting
 * tests are the ones that write their own sequence: aim, hold, aim, release, with whatever they
 * want to check in between.
 */
public class InventoryScope internal constructor(
    private val client: ClientScope,
    public val screen: InventoryScreen,
) {

    /** The hotbar slot the player is currently holding, which is what "main hand" means. */
    public val selectedHotbar: InventorySlot
        get() = InventorySlot.hotbar(
            client.clientPlayer?.inventory?.selectedSlot
                ?: error("the inventory is open but this client has no player")
        )

    /** What the client believes is in a slot. */
    public fun stackAt(slot: InventorySlot): ItemStack =
        screen.menu.slots[slot.menuIndex].item

    /**
     * Where to point at a slot, in window pixels.
     *
     * A slot knows only where it sits inside the screen, and the screen is centred on a window
     * whose size the test chose, so both halves and the GUI scale are needed to land on it.
     */
    public fun slotPosition(slot: InventorySlot): Pair<Double, Double> {
        val menuSlot = screen.menu.slots[slot.menuIndex]
        val origin = screen as ContainerScreenAccessor
        val guiX = (origin.leftPos + menuSlot.x + SLOT_CENTRE).toDouble()
        val guiY = (origin.topPos + menuSlot.y + SLOT_CENTRE).toDouble()
        return SyntheticInput.guiToWindowX(client.minecraft, guiX) to
            SyntheticInput.guiToWindowY(client.minecraft, guiY)
    }

    /** Moves the pointer onto a slot. */
    public suspend fun moveToSlot(slot: InventorySlot, over: Duration = DEFAULT_DRAG) {
        val (x, y) = slotPosition(slot)
        client.moveMouseTo(x, y, over)
    }

    /** @see moveToSlot */
    public suspend fun moveToSlot(slot: InventorySlot, speed: Speed) {
        val (x, y) = slotPosition(slot)
        client.moveMouseTo(x, y, speed)
    }

    /**
     * Which slot the screen believes the pointer is over, if any.
     *
     * The screen's own answer rather than ours, so it is worth asking: if a click is not landing,
     * this is what says whether the pointer is where the test thinks it aimed.
     */
    public val hoveredSlot: InventorySlot?
        get() {
            val slot = screen.slotUnderMouse ?: return null
            val index = screen.menu.slots.indexOf(slot)
            return InventorySlot.entries.firstOrNull { it.menuIndex == index }
        }

    /** Where the client itself thinks the pointer is, in GUI coordinates. */
    public val pointerInGui: Pair<Double, Double>
        get() = client.minecraft.mouseHandler.getScaledXPos(client.minecraft.window) to
            client.minecraft.mouseHandler.getScaledYPos(client.minecraft.window)

    /** What this client is logging under, so a scope can say what it saw. */
    public fun log(message: String): Unit = client.log(message)

    /** What is on the cursor between a [pickUp] and a [dropOn]. */
    public val carried: ItemStack get() = screen.menu.carried

    public suspend fun mouseDown(button: MouseButton = MouseButton.LEFT): Unit = client.mouseDown(button)

    public suspend fun mouseUp(button: MouseButton = MouseButton.LEFT): Unit = client.mouseUp(button)

    public suspend fun click(button: MouseButton = MouseButton.LEFT): Unit = client.click(button)

    /**
     * Takes what is in [slot] onto the cursor.
     *
     * A click rather than a held button, because that is what picking something up is in Minecraft:
     * holding the button and moving is the quick-craft gesture, which spreads a carried stack over
     * the slots it passes and puts a single item back where the drag began.
     */
    public suspend fun pickUp(slot: InventorySlot, over: Duration = DEFAULT_DRAG) {
        moveToSlot(slot, over)
        click()
    }

    /** Puts what is on the cursor into [slot]. @see pickUp */
    public suspend fun dropOn(slot: InventorySlot, over: Duration = DEFAULT_DRAG) {
        moveToSlot(slot, over)
        click()
    }

    /**
     * Carries what is in [from] over to [to], and returns once it has really arrived.
     *
     * Nothing here a test could not have written itself out of [pickUp] and [dropOn]; what it adds
     * is the wait, so the line after it is not racing the round trip to the server that every slot
     * click is.
     */
    public suspend fun swapSlot(
        from: InventorySlot,
        to: InventorySlot,
        over: Duration = DEFAULT_DRAG,
        mode: AssertMode = timeoutSec(5),
    ) {
        val moved = stackAt(from).copy()

        pickUp(from, over)
        dropOn(to, over)

        if (client.awaitCondition(mode) { ItemStack.isSameItemSameComponents(stackAt(to), moved) }) return
        throw E2eAssertionError(
            "swapSlot($from -> $to) did not land after $mode\n" +
                "  wanted ${moved.hoverName.string} in $to, found ${stackAt(to).describe()}"
        )
    }

    /** Fails the test unless the stack in [slot] satisfies [predicate]. */
    public suspend fun assertSlot(
        description: String,
        slot: InventorySlot,
        mode: AssertMode = timeoutSec(5),
        predicate: (ItemStack) -> Boolean,
    ) {
        if (client.awaitCondition(mode) { predicate(stackAt(slot)) }) return
        throw E2eAssertionError("$description ($mode)\n  $slot on ${client.self}: ${stackAt(slot).describe()}")
    }

    private fun ItemStack.describe(): String =
        if (isEmpty) "empty" else "${count}x ${hoverName.string}"

    private companion object {
        /** A slot is 18 pixels across in GUI space, so its middle is 9 in. */
        const val SLOT_CENTRE = 9

        /** Slow enough to watch a stack cross the screen. */
        val DEFAULT_DRAG: Duration = DEFAULT_MOVE
    }
}
