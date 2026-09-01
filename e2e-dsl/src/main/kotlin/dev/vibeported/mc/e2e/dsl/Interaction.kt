package dev.vibeported.mc.e2e.dsl

import dev.vibeported.mc.e2e.ClientScope
import dev.vibeported.mc.e2e.protocol.AssertionFailure
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.core.BlockPos

/**
 * Mines the block at [pos] by holding attack, and returns once it is gone.
 *
 * Every part of that is deliberate. The player turns first, because you cannot mine what you are
 * not looking at; the button is held rather than clicked, because that is what breaking a block
 * is; and the call does not return until the block has actually disappeared from this client's own
 * level, so the next line of a test is not racing the swing.
 *
 * A timeout says what was still standing there, which is the difference between "the test timed
 * out" and "it was mining bedrock".
 */
public suspend fun ClientScope.breakBlock(pos: BlockPos, mode: AssertMode = timeoutSec(10)) {
    lookAt(pos)
    ensureGrabbed()

    mouseDown(MouseButton.LEFT)
    val broken = try {
        awaitCondition(mode) { level.getBlockState(pos).isAir }
    } finally {
        mouseUp(MouseButton.LEFT)
    }

    if (!broken) {
        val seen = level.getBlockState(pos)
        throw AssertionFailure(
            "breakBlock($pos) held attack for $mode and the block is still there\n" +
                "  at $pos on $self: ${seen.block.descriptionId}"
        )
    }
}

/**
 * Right-clicks the block at [pos], turning to face it first.
 *
 * What "used" means is up to the block, so unlike [breakBlock] there is nothing general to wait
 * for: a test says what it expects to have happened, with an assertion of its own.
 */
public suspend fun ClientScope.useBlock(pos: BlockPos) {
    lookAt(pos)
    ensureGrabbed()
    click(MouseButton.RIGHT)
}

/**
 * Makes sure the world, not a screen, is what a click will reach.
 *
 * Minecraft spends the first click after a screen closes on grabbing the mouse rather than on the
 * world, so without this the first `breakBlock` of a test would quietly do nothing.
 */
internal suspend fun ClientScope.ensureGrabbed() {
    if (minecraft.gui.screen() != null) return
    if (minecraft.mouseHandler.isMouseGrabbed) return
    minecraft.mouseHandler.grabMouse()
    awaitTicks()
}

/**
 * Swings at whatever the crosshair is on.
 *
 * One swing, not a hold: a weapon has a cooldown, so a test that wants several hits spaces them
 * itself and can look at the state in between. @see breakBlock for the held-attack form.
 */
public suspend fun ClientScope.attack() {
    ensureGrabbed()
    click(MouseButton.LEFT)
}

/**
 * Says something in chat, by opening the chat screen and typing it.
 *
 * The long way round on purpose: the message goes through the chat keybind, the screen, and the
 * client's own send path, which is the part worth testing. Sending the packet directly would prove
 * only that the server accepts packets.
 */
public suspend fun ClientScope.chat(message: String) {
    press(Key(minecraft.options.keyChat.key.value))
    awaitScreen<ChatScreen>()
    type(message)
    press(Key.ENTER)
    awaitNoScreen()
}
