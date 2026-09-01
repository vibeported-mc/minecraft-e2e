package dev.vibeported.mc.e2e.mixin;

import dev.vibeported.mc.e2e.input.InputGate;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops a test client from capturing the machine's cursor.
 *
 * Entering a world grabs the mouse, which hides the pointer and locks it to that window. One client
 * doing that is Minecraft working correctly; several of them doing it, on a machine somebody is
 * also using, is a fight over the pointer that nobody wins.
 *
 * Only the call to the operating system is skipped. {@code MouseHandler} still records that the
 * mouse is grabbed and still recentres its own coordinates, so the game behaves exactly as it would
 * otherwise -- the difference is that the physical cursor stays where its owner left it.
 */
@Mixin(InputConstants.class)
public class InputConstantsMixin {

    @Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
    private static void e2e$leaveTheCursorAlone(Window window, int mode, double x, double y, CallbackInfo ci) {
        if (InputGate.isInstalled()) {
            ci.cancel();
        }
    }
}
