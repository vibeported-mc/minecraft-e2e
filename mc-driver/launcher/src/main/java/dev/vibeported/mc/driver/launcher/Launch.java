package dev.vibeported.mc.driver.launcher;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.startup.Entrypoint;
import net.neoforged.fml.startup.FatalErrorReporting;

/**
 * Starts FancyModLoader, then runs somebody's {@code main} instead of a game.
 *
 * FancyModLoader's own {@code Client} and {@code Server} differ from each other in nothing but the
 * class they hand control to once the loader is up. This is that same shape with the class left as a
 * parameter, which is the supported way to get a modded environment with no game in it: mixins are
 * applied, access transformers are applied, the mod classpath is assembled, and no
 * {@code MinecraftServer} is ever constructed because nothing calls {@code net.minecraft.server.Main}.
 *
 * <p>Which matters more than it sounds. Anything wanting to <em>talk</em> to a modded game -- to
 * encode a {@code BlockPos}, to resolve a procedure table, to hold a serializer for a game type --
 * needs the game's classes loaded the way the game loads them. FancyModLoader hands mod classes to a
 * transforming loader of its own; resolve them through any other and you get a second copy of every
 * one, and a value handed across then fails to match a type it plainly is, with an error naming that
 * very type.
 *
 * <p>Java, and not Kotlin, because this runs before the transforming class loader exists. Everything
 * it hands over to is loaded through that loader instead, which is what puts the caller and the game
 * in the same transformed world.
 *
 * <h2>Using it</h2>
 *
 * The class to run is named by {@code -Dmcdriver.launch.main}, and the launcher is the run's main
 * class:
 *
 * <pre>{@code
 * neoForge.runs.create("smoke") { run ->
 *     run.server()
 *     run.mainClass.set("dev.vibeported.mc.driver.launcher.Launch")
 *     run.jvmArgument("-Dmcdriver.launch.main=com.example.Smoke")
 * }
 * }</pre>
 *
 * The driver's Gradle plugin writes that for you; this is what it writes.
 */
public final class Launch extends Entrypoint {

    /** The class whose {@code main(String[])} runs once the loader is up. */
    public static final String MAIN_PROPERTY = "mcdriver.launch.main";

    /**
     * Which dist to prepare: {@code CLIENT} or {@code DEDICATED_SERVER}, the latter by default.
     *
     * <p>A process that runs no game still has a dist, because the dist decides which classes exist.
     * {@code DEDICATED_SERVER} is right for anything driving a cluster from outside: it is the
     * smaller world, it is dist-cleaned exactly as the real server is, and a driver that
     * accidentally depends on a client class finds out here rather than three modules later.
     */
    public static final String DIST_PROPERTY = "mcdriver.launch.dist";

    private Launch() {}

    public static void main(String[] args) {
        var mainClass = System.getProperty(MAIN_PROPERTY);
        if (mainClass == null || mainClass.isBlank()) {
            System.err.println(
                    "mcdriver: -D" + MAIN_PROPERTY + " was not set, so there is nothing to run. "
                            + "It names the class whose main() should run once FancyModLoader is up.");
            System.exit(1);
            return;
        }

        var dist = dist();

        // `cleanDist` is true for the same reason the real launchers set it: a dist that keeps the
        // other side's classes around is not the dist anything will actually run on, and the point
        // of coming through here is to be in the environment the game is in.
        try (var startup = startup(args, true, dist, true)) {
            var main = createMainMethodCallable(startup, mainClass);
            main.invokeExact(startup.loader().getProgramArgs().getArguments());
        } catch (Throwable failure) {
            FatalErrorReporting.reportFatalErrorOnConsole(failure);
            System.exit(1);
        }
    }

    private static Dist dist() {
        var named = System.getProperty(DIST_PROPERTY);
        if (named == null || named.isBlank()) {
            return Dist.DEDICATED_SERVER;
        }
        try {
            return Dist.valueOf(named.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(
                    "mcdriver: -D" + DIST_PROPERTY + "=" + named + " is not a dist. "
                            + "It is CLIENT or DEDICATED_SERVER.",
                    unknown);
        }
    }
}
