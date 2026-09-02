package dev.vibeported.mc.e2e.dsl.mixin;

import dev.vibeported.mc.e2e.dsl.input.InputGate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells a test client it has focus, because it never will.
 *
 * {@code isWindowActive} is the operating system's answer, and it gates mouse movement, the drag a
 * container screen sees, and grabbing the mouse at all. A run has several clients and a person
 * looking at one of them, so at most one window is focused and usually none is, which would leave
 * the rest quietly ignoring every mouse event a test sent them.
 *
 * Overriding it is honest rather than a trick: this process really is the one being driven. Real
 * input is still dropped by {@link InputGate}, so being "active" grants a person nothing.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
    private void e2e$alwaysActive(CallbackInfoReturnable<Boolean> cir) {
        if (InputGate.isInstalled()) {
            cir.setReturnValue(true);
        }
    }
}
