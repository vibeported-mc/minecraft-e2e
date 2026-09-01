package dev.vibeported.mc.e2e.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.vibeported.mc.e2e.mc.UiLayers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The top of the frame.
 *
 * This one method is where Minecraft extracts the HUD, then any open screen, then toasts and the
 * debug overlay, and finally tells the window which cursor to wear. Injecting at its tail is what
 * puts the framework's own layers above every one of those -- a registered GUI layer would sit under
 * the screen, and a screen render hook would sit under the toasts.
 */
@Mixin(Gui.class)
public class GuiOverlayMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void e2e$drawOverlays(
        DeltaTracker deltaTracker,
        boolean shouldRenderLevel,
        boolean resourcesLoaded,
        CallbackInfo ci,
        @Local GuiGraphicsExtractor graphics
    ) {
        UiLayers.render(graphics);
    }
}
