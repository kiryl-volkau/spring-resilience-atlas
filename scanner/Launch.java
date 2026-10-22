import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Runs the scanner from the plugin's own sources, so the plugin ships no compiled code.
 *
 * <p>{@code java <plugin>/scanner/Launch.java [--cache <dir>] <command> [options]} takes the
 * scanner's usual commands and options. The first run compiles the sources with the JDK's own
 * compiler into {@code <dir>/build-<hash>}; later runs reuse that build until a source file
 * changes, and older builds are deleted. Without {@code --cache}, or with an empty or
 * unsubstituted value, builds go to {@code ~/.resilience-atlas}. Needs a JDK 17 or newer.
 */
public final class Launch {

    private static final String MAIN = "studio.volkoff.atlas.Main";
    private static final String JAVA = "java/";
    private static final String RESOURCE = "resource/";

    public static void main(String[] args) throws Exception {
        Path scanner = Path.of(Launch.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        String cache = null;
        if (args.length >= 2 && args[0].equals("--cache")) {
            cache = args[1];
            args = Arrays.copyOfRange(args, 2, args.length);
        }
        Map<String, Path> inputs = inputs(scanner);
        Path build = cacheDir(cache).resolve("build-" + hash(inputs));
        if (!Files.isDirectory(build)) {
            compile(inputs, build);
        }
        run(build, args);
    }

    /** Sources and resources keyed by where they go in the build, mirroring {@code scanner/pom.xml}. */
    private static Map<String, Path> inputs(Path scanner) throws IOException {
        Map<String, Path> inputs = new TreeMap<>();
        add(inputs, scanner.resolve("src/main/java"), JAVA);
        add(inputs, scanner.resolve("src/main/resources"), RESOURCE);
        add(inputs, scanner.resolveSibling("schemas"), RESOURCE + "docs/");
        add(inputs, scanner.resolveSibling("references"), RESOURCE + "docs/");
        return inputs;
    }

    private static void add(Map<String, Path> inputs, Path root, String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile)
                    .forEach(f -> inputs.put(prefix + root.relativize(f).toString().replace('\\', '/'), f));
        }
    }

    private static String hash(Map<String, Path> inputs) throws IOException, NoSuchAlgorithmException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (Map.Entry<String, Path> e : inputs.entrySet()) {
            sha.update(e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(Files.readAllBytes(e.getValue()));
        }
        return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
    }

    private static Path cacheDir(String cache) {
        // Outside an installed plugin, ${CLAUDE_PLUGIN_DATA} arrives empty or as literal text.
        if (cache == null || cache.isBlank() || cache.contains("${")) {
            return Path.of(System.getProperty("user.home"), ".resilience-atlas");
        }
        return Path.of(cache);
    }

    private static void compile(Map<String, Path> inputs, Path build) throws IOException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            fail("resilience-atlas needs a JDK 17 or newer, and this Java runtime has no compiler (javac is not available).");
        }
        Files.createDirectories(build.getParent());
        Path staging = Files.createTempDirectory(build.getParent(), "staging-");
        List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-proc:none", "-nowarn",
                "-d", staging.toString()));
        inputs.forEach((key, file) -> {
            if (key.startsWith(JAVA)) {
                options.add(file.toString());
            }
        });
        System.err.println("# compiling the scanner into " + build.getParent() + " (first run only)");
        if (javac.run(null, null, null, options.toArray(String[]::new)) != 0) {
            delete(staging);
            fail("the scanner did not compile; see the javac errors above.");
        }
        for (Map.Entry<String, Path> e : inputs.entrySet()) {
            if (e.getKey().startsWith(RESOURCE)) {
                Path target = staging.resolve(e.getKey().substring(RESOURCE.length()));
                Files.createDirectories(target.getParent());
                Files.copy(e.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try {
            Files.move(staging, build, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // A concurrent run finished the same build first.
            delete(staging);
            if (!Files.isDirectory(build)) {
                throw e;
            }
        }
        try (Stream<Path> siblings = Files.list(build.getParent())) {
            siblings.filter(p -> p.getFileName().toString().startsWith("build-") && !p.equals(build)).forEach(Launch::delete);
        }
    }

    private static void run(Path build, String[] args) throws Exception {
        URLClassLoader loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, ClassLoader.getSystemClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        Method main = loader.loadClass(MAIN).getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw (Error) e.getCause();
        }
    }

    private static void delete(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // Best effort: a build still in use by another run stays until the next cleanup.
        }
    }

    private static void fail(String message) {
        System.err.println("error: " + message);
        System.exit(2);
    }
}
