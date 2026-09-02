package dev.vibeported.mc.e2e.codegen.launcher;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.startup.Entrypoint;
import net.neoforged.fml.startup.FatalErrorReporting;

/**
 * Starts FancyModLoader and then writes source instead of running a game.
 *
 * The same trick the orchestrator uses, for a different program: {@code startup} and
 * {@code createMainMethodCallable} are {@code protected static} on {@code Entrypoint}, so any
 * subclass can boot the loader and hand control wherever it likes. Two subclasses in two modules
 * need nothing in common, which is why the generator is its own module rather than a second job for
 * the orchestrator.
 *
 * Java, and not Kotlin, because this runs before the transforming class loader exists. Everything it
 * hands over to is loaded through that loader, which is what lets the generator see Minecraft's
 * classes at all.
 */
public final class CodegenEntrypoint extends Entrypoint {

    private CodegenEntrypoint() {}

    public static void main(String[] args) {
        try (var startupResult = startup(args, true, Dist.DEDICATED_SERVER, true)) {
            var generator = createMainMethodCallable(
                    startupResult, "dev.vibeported.mc.e2e.codegen.BlockDslMain");
            generator.invokeExact(startupResult.loader().getProgramArgs().getArguments());
        } catch (Throwable failure) {
            FatalErrorReporting.reportFatalErrorOnConsole(failure);
            System.exit(1);
        }
    }
}
