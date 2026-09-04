package dev.vibeported.mc.driver.mixin;

import com.mojang.blaze3d.platform.cursor.CursorType;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Which cursor the interface asked for this frame.
 *
 * Minecraft records a request as widgets are extracted -- a pointing hand over a link, an I-beam
 * over a text field -- and hands it to the window at the end of the frame. Reading it is how the
 * drawn cursor becomes whatever the real one would have been, rather than a guess of ours about
 * what is under the pointer.
 */
@Mixin(GuiGraphicsExtractor.class)
public interface CursorRequestAccessor {

    @Accessor("pendingCursor")
    CursorType getPendingCursor();
}
