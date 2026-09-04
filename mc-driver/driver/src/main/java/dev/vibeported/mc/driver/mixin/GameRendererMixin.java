package dev.vibeported.mc.driver.mixin;

import dev.vibeported.mc.driver.record.ScreenRecorder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of the frame, which is where a recording takes its picture.
 *
 * By the tail of this method the level, any post-processing chain and the whole GUI have all been
 * drawn into the main render target, and nothing has yet blitted it to the window. That target is
 * exactly what a screenshot reads, so a recording made here shows what the player would have seen
 * and not a frame of it earlier.
 *
 * Injecting rather than listening to {@code RenderFrameEvent.Post}, which fires at nearly the same
 * moment, because that event is about the frame having happened while this is about the target still
 * holding it: the render target is the thing being captured, and this is the method that owns it.
 *
 * Costs nothing when nothing is being recorded -- {@link ScreenRecorder#onFrameRendered} returns on
 * a null field before it looks at anything.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void mcdriver$recordFrame(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        ScreenRecorder.onFrameRendered(Minecraft.getInstance());
    }
}
