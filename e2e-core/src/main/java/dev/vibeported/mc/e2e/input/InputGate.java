package dev.vibeported.mc.e2e.input;

/**
 * Whether the input Minecraft is about to handle is the framework's or a person's.
 *
 * An automated client shares a keyboard with whoever is watching it, and a stray keystroke aimed at
 * another window is indistinguishable, by the time it reaches {@code KeyboardHandler}, from
 * something a test meant to do. So real input is dropped at the same instruction the framework
 * enters at: everything is cancelled unless {@link #isDispatching()} says this very call is ours.
 *
 * Nothing here is on by default. The gate is installed only when the process was started as a test
 * node, so the mod sitting in an ordinary development client changes nothing about it.
 *
 * It lives outside the mixin package deliberately: everything in there belongs to Mixin, which
 * refuses to let anything else load a class from it.
 */
public final class InputGate {

    private static volatile boolean installed = false;
    private static volatile boolean blocking = true;

    /**
     * Render-thread only, and deliberately not volatile.
     *
     * It is set and cleared around a single call on the same thread that will read it, so
     * publishing it to other threads would be describing a situation that cannot arise.
     */
    private static boolean dispatching = false;

    private InputGate() {}

    /** Called once, by the mod, when this process turns out to be a test client. */
    public static void install(boolean blockRealInput) {
        installed = true;
        blocking = blockRealInput;
    }

    public static void setBlocking(boolean value) {
        blocking = value;
    }

    public static boolean isInstalled() {
        return installed;
    }

    public static boolean isBlocking() {
        return installed && blocking;
    }

    /** True while an event the framework itself is delivering is in flight. */
    public static boolean isDispatching() {
        return dispatching;
    }

    /** The question every injected handler asks: should this event be dropped? */
    public static boolean shouldCancel() {
        return installed && blocking && !dispatching;
    }

    public static void begin() {
        dispatching = true;
    }

    public static void end() {
        dispatching = false;
    }
}
