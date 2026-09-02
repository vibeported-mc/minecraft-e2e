package dev.vibeported.capture.libav;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds the FFmpeg DLLs and loads them.
 *
 * <p>They ride inside this jar as resources, the way LWJGL and sqlite-jdbc ship
 * theirs, and are unpacked on first use into a cache directory keyed by the hash
 * of the set. Set {@code -Dlibav.home=<dir>} to bypass all of that and load out
 * of a directory instead -- that is the dev loop against {@code dist/bin}, and
 * the escape hatch for a host application that would rather ship the DLLs
 * beside itself.
 */
public final class NativeBootstrap {

    private static final String RESOURCES = "/dev/vibeported/capture/libav/natives/windows-x64";

    /**
     * Load order is the whole trick. Every library here imports the ones above
     * it, and Windows resolves an import against a module already loaded under
     * that base name -- so loading avutil first means avcodec's reference to
     * {@code avutil-61.dll} is satisfied without touching PATH,
     * AddDllDirectory, or LOAD_WITH_ALTERED_SEARCH_PATH.
     */
    private static final List<String> ORDER =
            List.of("avutil", "swresample", "swscale", "avcodec", "avfilter", "avformat");

    private static boolean loaded;
    private static Path directory;

    private NativeBootstrap() {}

    /** Loads the libraries once. Returns the directory they were loaded from. */
    public static synchronized Path ensureLoaded() {
        if (loaded) return directory;

        String home = System.getProperty("libav.home");
        directory = home != null ? Path.of(home) : extract();

        for (String lib : ORDER) {
            System.load(locate(directory, lib).toString());
        }
        loaded = true;
        return directory;
    }

    /** The directory the natives were loaded from, or null if not loaded yet. */
    public static Path directory() {
        return directory;
    }

    /**
     * The DLLs carry an ABI version in their name (avcodec-63.dll), which is
     * exactly why System.loadLibrary("avcodec") could never find them.
     */
    private static Path locate(Path dir, String lib) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Not a directory: " + dir
                    + " (set -Dlibav.home to a directory holding the FFmpeg DLLs)");
        }
        try (var files = Files.list(dir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.startsWith(lib + "-") && n.endsWith(".dll");
                    })
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "No " + lib + "-<version>.dll in " + dir));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Unpacks the natives into %LOCALAPPDATA%\vibeported\libav\<set-id>.
     *
     * <p>Keyed by content, so two different FFmpeg builds never share a
     * directory, and a second run is a no-op. Files are written under a
     * temporary name and moved into place, so a concurrent JVM can never see a
     * half-written DLL. Nothing is deleted on exit: a loaded DLL cannot be
     * deleted on Windows anyway, and the cache is meant to be reused.
     */
    private static Path extract() {
        List<String> index = readIndex();
        String setId = index.stream()
                .filter(l -> l.startsWith("set "))
                .map(l -> l.substring(4).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("natives.index has no set id"));

        Path base = cacheRoot().resolve("vibeported").resolve("libav").resolve(setId);
        try {
            Files.createDirectories(base);
            for (String line : index) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("set ")) continue;
                String name = line.split("\s+")[0];
                Path target = base.resolve(name);
                if (Files.exists(target)) continue;
                copyResource(name, target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not unpack the FFmpeg natives into " + base, e);
        }
        return base;
    }

    private static void copyResource(String name, Path target) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), name, ".part");
        try (InputStream in = open(name)) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException | AtomicMoveNotSupportedException e) {
            // Another JVM won the race and wrote the same bytes; drop ours.
            Files.deleteIfExists(tmp);
        }
    }

    private static List<String> readIndex() {
        try (InputStream in = open("natives.index")) {
            var out = new ArrayList<String>();
            for (String l : new String(in.readAllBytes()).split("\n")) out.add(l.strip());
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "This jar carries no natives. Either build them (build-windows.ps1) "
                            + "or point -Dlibav.home at a directory of FFmpeg DLLs.", e);
        }
    }

    private static InputStream open(String name) throws IOException {
        InputStream in = NativeBootstrap.class.getResourceAsStream(RESOURCES + "/" + name);
        if (in == null) throw new IOException("Missing resource " + RESOURCES + "/" + name);
        return in;
    }

    private static Path cacheRoot() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) return Path.of(local);
        return Path.of(System.getProperty("user.home"), ".cache");
    }
}
