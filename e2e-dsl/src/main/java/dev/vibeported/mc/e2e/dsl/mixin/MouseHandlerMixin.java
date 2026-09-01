package dev.vibeported.mc.e2e.dsl.mixin;

import dev.vibeported.mc.e2e.dsl.input.InputGate;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drops mouse events that did not come from a test. @see InputGate */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void e2e$dropRealButton(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (InputGate.shouldCancel()) {
            ci.cancel();
        }
    }

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true)
    private void e2e$dropRealMove(long handle, double x, double y, CallbackInfo ci) {
        if (InputGate.shouldCancel()) {
            ci.cancel();
        }
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void e2e$dropRealScroll(long handle, double dx, double dy, CallbackInfo ci) {
        if (InputGate.shouldCancel()) {
            ci.cancel();
        }
    }
}
