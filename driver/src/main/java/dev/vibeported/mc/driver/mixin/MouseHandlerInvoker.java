package dev.vibeported.mc.driver.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The way in, for the mouse. @see KeyboardHandlerInvoker */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {

    @Invoker("onButton")
    void invokeOnButton(long handle, MouseButtonInfo info, int action);

    @Invoker("onMove")
    void invokeOnMove(long handle, double x, double y);

    @Invoker("onScroll")
    void invokeOnScroll(long handle, double dx, double dy);
}
