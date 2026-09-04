package dev.vibeported.mc.driver.mixin;

import dev.vibeported.mc.driver.input.InputGate;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops keyboard events that did not come from a test. @see InputGate */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void mcdriver$dropRealKeyPress(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (InputGate.shouldCancel()) {
            ci.cancel();
        }
    }

    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), cancellable = true)
    private void mcdriver$dropRealCharTyped(long handle, CharacterEvent event, CallbackInfo ci) {
        if (InputGate.shouldCancel()) {
            ci.cancel();
        }
    }
}
