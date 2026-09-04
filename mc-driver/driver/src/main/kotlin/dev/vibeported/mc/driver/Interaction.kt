package dev.vibeported.mc.driver

import net.minecraft.core.BlockPos

/**
 * Mines the block at [pos] by holding attack, and returns once it is gone.
 *
 * Every part of that is deliberate. The player turns first, because you cannot mine what you are not
 * looking at; the button is held rather than clicked, because that is what breaking a block is; and
 * this does not return until the block has actually disappeared from this client's own level, so the
 * next line is not racing the swing.
 *
 * Unbounded, like everything else here that waits. `withTimeout` around it says how long is too
 * long, and the level is right there to say what was still standing.
 */
public suspend fun ClientScope.breakBlock(pos: BlockPos) {
    faceBlock(pos)
    ensureGrabbed()

    mouseDown(MouseButton.LEFT)
    try {
        awaitUntil { level.getBlockState(pos).isAir }
    } finally {
        mouseUp(MouseButton.LEFT)
    }
}

/**
 * Right-clicks the block at [pos], turning to face it first.
 *
 * What "used" means is up to the block, so unlike [breakBlock] there is nothing general to wait for.
 */
public suspend fun ClientScope.useBlock(pos: BlockPos) {
    faceBlock(pos)
    ensureGrabbed()
    click(MouseButton.RIGHT)
}

/**
 * Swings at whatever the crosshair is on.
 *
 * One swing, not a hold: a weapon has a cooldown, so a caller wanting several hits spaces them
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
 * client's own send path, which is the part worth exercising. Sending the packet directly would
 * prove only that the server accepts packets.
 */
public suspend fun ClientScope.chat(message: String) {
    press(Key(minecraft.options.keyChat.key.value))
    awaitScreen("ChatScreen")
    type(message)
    press(Key.ENTER)
    awaitNoScreen()
}

/**
 * Turns this client's player toward a block, from inside a client body.
 *
 * A nested call, and the only kind in this file: turning is the server's to do, so this reaches back
 * out through the free [lookAt] rather than moving the local player and hoping the server agrees.
 * Internal because a caller outside a body has [lookAt] itself, which says the same thing without
 * the round trip through here.
 */
internal suspend fun ClientScope.faceBlock(pos: BlockPos): Unit = lookAt(clientName, pos)

/**
 * Makes sure the world, not a screen, is what a click will reach.
 *
 * Minecraft spends the first click after a screen closes on grabbing the mouse rather than on the
 * world, so without this the first [breakBlock] would quietly do nothing.
 */
internal suspend fun ClientScope.ensureGrabbed() {
    if (minecraft.gui.screen() != null) return
    if (minecraft.mouseHandler.isMouseGrabbed) return
    minecraft.mouseHandler.grabMouse()
    awaitTicks()
}
