package dev.vibeported.mc.e2e.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The way in.
 *
 * These are the very methods the GLFW callbacks call, so entering here is indistinguishable from a
 * real keypress for everything downstream: key mappings, the screen stack, and any mod listening.
 */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {

    @Invoker("keyPress")
    void invokeKeyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void invokeCharTyped(long handle, CharacterEvent event);
}
