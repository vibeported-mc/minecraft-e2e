package dev.vibeported.mc.e2e.launcher;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.startup.Entrypoint;
import net.neoforged.fml.startup.FatalErrorReporting;

/**
 * Starts FancyModLoader and then runs the tests instead of a server.
 *
 * Deliberately shaped like FancyModLoader own {@code Server} and {@code GameTestServer}, which
 * differ from each other in nothing but the class they hand control to once the loader is up. That
 * is the supported way to get a modded environment without a game in it: mixins are applied, access
 * transformers are applied, the mod classpath is assembled, and no {@code MinecraftServer} is ever
 * constructed because nothing calls {@code net.minecraft.server.Main}.
 *
 * Java, and not Kotlin, because this runs before the transforming class loader exists. Everything
 * it hands over to is loaded through that loader instead, which is what puts the framework and the
 * suites in the same transformed world as the game.
 */
public final class OrchestratorEntrypoint extends Entrypoint {

    private OrchestratorEntrypoint() {}

    public static void main(String[] args) {
        try (var startupResult = startup(args, true, Dist.DEDICATED_SERVER, true)) {
            var bootstrap = createMainMethodCallable(
                    startupResult, "dev.vibeported.mc.e2e.launcher.OrchestratorBootstrap");
            bootstrap.invokeExact(startupResult.loader().getProgramArgs().getArguments());
        } catch (Throwable failure) {
            FatalErrorReporting.reportFatalErrorOnConsole(failure);
            System.exit(1);
        }
    }
}
