package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.model.Origin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The repository's source files, build modules and build-level facts, per {@code schemas/entrypoint-rules.md}. */
public final class Workspace {

    public record SourceEntry(Path path, String relPath, String module, Origin origin) {
    }

    /** Hidden directories (.git, .idea, .claude worktrees, ...) are skipped as well. */
    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "buildSrc");
    private static final Set<String> BUILD_OUTPUT_DIRS = Set.of("target", "build", "out");
    private static final Set<String> GENERATED_DIRS = Set.of("generated-sources", "generated", "generated-src");
    private static final Set<String> BUILD_FILES = Set.of("pom.xml", "build.gradle", "build.gradle.kts");
    private static final Set<String> BUILD_SETTINGS = Set.of("libs.versions.toml", "gradle.properties");

    /** Build-file substrings that matter to extraction rules or warnings. */
    private static final Map<String, String> DECLARATIONS = new LinkedHashMap<>();

    static {
        DECLARATIONS.put("spring-cloud-stream", "spring-cloud-stream");
        DECLARATIONS.put("spring-cloud-starter-stream", "spring-cloud-stream");
        DECLARATIONS.put("stream-binder", "spring-cloud-stream");
        DECLARATIONS.put("rsocket", "rsocket");
        DECLARATIONS.put("spring-boot-starter-websocket", "websocket");
        DECLARATIONS.put("graphql", "graphql");
        DECLARATIONS.put("grpc", "grpc");
        DECLARATIONS.put("spring-integration", "spring-integration");
        DECLARATIONS.put("camel", "camel");
        DECLARATIONS.put("shedlock", "shedlock");
        DECLARATIONS.put("openapi-generator", "openapi-generator");
        DECLARATIONS.put("swagger-codegen", "swagger-codegen");
        DECLARATIONS.put("spring-kafka", "spring-kafka");
        DECLARATIONS.put("kafka-clients", "kafka-clients");
        DECLARATIONS.put("spring-cloud-aws", "spring-cloud-aws");
        DECLARATIONS.put("spring-cloud-aws-starter-sqs", "sqs");
        DECLARATIONS.put("spring-cloud-aws-sqs", "sqs");
        DECLARATIONS.put("spring-cloud-aws-messaging", "sqs");
        DECLARATIONS.put("awssdk:sqs", "sqs");
        DECLARATIONS.put("<artifactid>sqs</artifactid>", "sqs");
        DECLARATIONS.put("spring-rabbit", "spring-rabbit");
        DECLARATIONS.put("spring-boot-starter-amqp", "spring-amqp");
        DECLARATIONS.put("spring-jms", "spring-jms");
        DECLARATIONS.put("spring-boot-starter-artemis", "spring-jms");
        DECLARATIONS.put("spring-webflux", "spring-webflux");
        DECLARATIONS.put("spring-ws-core", "spring-ws");
        DECLARATIONS.put("cxf-", "cxf");
        DECLARATIONS.put("quartz", "quartz");
        DECLARATIONS.put("spring-batch", "spring-batch");
        DECLARATIONS.put("flyway", "flyway");
        DECLARATIONS.put("liquibase", "liquibase");
        DECLARATIONS.put("spring-modulith", "spring-modulith");
        DECLARATIONS.put("spring-cloud-starter-gateway", "spring-cloud-gateway");
        DECLARATIONS.put("spring-cloud-gateway", "spring-cloud-gateway");
        DECLARATIONS.put("spring-session", "spring-session");
    }

    public final Path root;
    public final List<SourceEntry> sources = new ArrayList<>();
    public final Set<String> modules = new TreeSet<>();
    public final Set<String> declares = new TreeSet<>();
    private final Map<String, Set<String>> declaresByModule = new LinkedHashMap<>();
    public String buildTool = "none";
    public String springBootVersion;
    public String javaVersion;
    public int xmlJaxWsEndpoints;
    public int xmlUrlHandlerMappings;
    public int xmlScheduledTasks;
    public int xmlCamelRoutes;
    /** {@code <task:annotation-driven/>} enables both {@code @Async} and {@code @Scheduled}. */
    public boolean xmlTaskAnnotationDriven;
    public int webXmlServlets;
    public int webXmlFilters;
    public int webXmlListeners;
    /** {@code url-pattern}s of {@code servlet-mapping}s in {@code web.xml}: controller paths are relative to them. */
    public final Set<String> servletMappings = new TreeSet<>();
    /** Base-path keys from main application config, values verbatim. */
    public final Map<String, String> basePaths = new java.util.TreeMap<>();
    private final Set<Path> outputNamedModules = new java.util.HashSet<>();
    private final List<Path> mainResourceDirs = new ArrayList<>();

    private Workspace(Path root) {
        this.root = root;
    }

    public static Workspace load(Path root) throws IOException {
        Workspace ws = new Workspace(root.toAbsolutePath().normalize());
        ws.walk();
        return ws;
    }

    public boolean declares(String feature) {
        return declares.contains(feature);
    }

    /**
     * Whether the module's build file, or one of its parent directories' build files (a parent
     * POM, the root build), declares the feature. One module's dependency says nothing about a
     * sibling deployed separately.
     */
    public boolean declaresFor(String module, String feature) {
        String m = module == null || module.isEmpty() ? "." : module;
        while (true) {
            if (declaresByModule.getOrDefault(m, Set.of()).contains(feature)) {
                return true;
            }
            if (m.equals(".")) {
                return false;
            }
            int slash = m.lastIndexOf('/');
            m = slash < 0 ? "." : m.substring(0, slash);
        }
    }

    private void walk() throws IOException {
        List<Path> buildFiles = new ArrayList<>();
        List<Path> javaOrKotlin = new ArrayList<>();
        List<Path> xmlFiles = new ArrayList<>();
        List<Path> buildSettings = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                return skipDirectory(dir, root.relativize(dir)) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (BUILD_FILES.contains(name)) {
                    buildFiles.add(file);
                } else if (BUILD_SETTINGS.contains(name)) {
                    buildSettings.add(file);
                } else if ((name.endsWith(".java") && !name.equals("package-info.java") && !name.equals("module-info.java"))
                        || name.endsWith(".kt")) {
                    javaOrKotlin.add(file);
                } else if (name.endsWith(".xml") && !isUnderBuildOutput(root.relativize(file))) {
                    xmlFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        for (Path b : buildFiles) {
            modules.add(rel(b.getParent()));
        }
        List<Path> buildTexts = new ArrayList<>(buildFiles);
        buildTexts.addAll(buildSettings);
        readBuildFiles(buildTexts);
        for (Path dir : mainResourceDirs) {
            // Spring and Camel XML usually lives in resources, which the source walk skips.
            try (java.util.stream.Stream<Path> files = Files.walk(dir, 6)) {
                files.filter(f -> f.getFileName().toString().endsWith(".xml")).forEach(xmlFiles::add);
            } catch (IOException | RuntimeException ignored) {
                // An unreadable resource directory only loses XML warnings.
            }
        }
        countXmlEndpoints(xmlFiles);
        readBasePaths();

        javaOrKotlin.sort(Comparator.comparing(p -> rel(p)));
        for (Path p : javaOrKotlin) {
            String relPath = rel(p);
            Origin origin = p.getFileName().toString().endsWith(".kt")
                    ? Origin.KOTLIN
                    : isUnderBuildOutput(root.relativize(p)) ? Origin.GENERATED : Origin.SOURCE;
            sources.add(new SourceEntry(p, relPath, moduleOf(relPath), origin));
        }
    }

    private boolean skipDirectory(Path dir, Path relative) {
        int n = relative.getNameCount();
        String name = relative.getName(n - 1).toString();
        if (SKIPPED_DIRS.contains(name) || name.startsWith(".")) {
            return true;
        }
        if (n >= 2 && relative.getName(n - 2).toString().equals("src") && !isMainLikeSourceSet(name)) {
            return true;
        }
        if (n >= 3 && relative.getName(n - 3).toString().equals("src") && name.equals("resources")) {
            if (relative.getName(n - 2).toString().equals("main")) {
                mainResourceDirs.add(dir);
            }
            return true;
        }
        if (BUILD_OUTPUT_DIRS.contains(name) && !insideSrc(relative) && containsBuildFile(dir)) {
            outputNamedModules.add(relative);
        }
        int buildOutput = buildOutputIndex(relative);
        if (buildOutput < 0 || buildOutput == n - 1) {
            return false;
        }
        String first = relative.getName(buildOutput + 1).toString();
        if (!GENERATED_DIRS.contains(first)) {
            return true;
        }
        for (int i = buildOutput + 2; i < n; i++) {
            String seg = relative.getName(i).toString();
            if (seg.equals("test") || seg.startsWith("generated-test")) {
                return true;
            }
        }
        return false;
    }

    /** {@code src/<set>}: main and custom sets count; test-like sets (intTest, it, jmh, e2e…) do not. */
    private static boolean isMainLikeSourceSet(String set) {
        String s = set.toLowerCase(java.util.Locale.ROOT);
        return !(s.contains("test") || s.equals("it") || s.equals("jmh") || s.startsWith("integration") || s.startsWith("e2e"));
    }

    private static boolean insideSrc(Path relative) {
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            if (relative.getName(i).toString().equals("src")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBuildFile(Path dir) {
        for (String b : BUILD_FILES) {
            if (Files.isRegularFile(dir.resolve(b))) {
                return true;
            }
        }
        return false;
    }

    private boolean isUnderBuildOutput(Path relative) {
        return buildOutputIndex(relative) >= 0;
    }

    /**
     * Index of the first {@code target|build|out} segment that is build output: outside a
     * {@code src} tree and not a module directory of that name. -1 when there is none.
     */
    private int buildOutputIndex(Path relative) {
        for (int i = 0; i < relative.getNameCount(); i++) {
            String seg = relative.getName(i).toString();
            if (seg.equals("src")) {
                return -1;
            }
            if (BUILD_OUTPUT_DIRS.contains(seg) && !outputNamedModules.contains(relative.subpath(0, i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private static final Set<String> BASE_PATH_KEYS =
            Set.of("server.servlet.context-path", "spring.mvc.servlet.path", "spring.webflux.base-path");
    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)([\\w.\\-]+)\\s*:\\s*(.*)$");

    /** Reads the base-path keys from main {@code application*} config, base files before profile files. */
    private void readBasePaths() {
        List<Path> configs = new ArrayList<>();
        for (Path dir : mainResourceDirs) {
            for (Path d : List.of(dir, dir.resolve("config"))) {
                try (java.util.stream.Stream<Path> files = Files.list(d)) {
                    files.filter(f -> f.getFileName().toString().matches("application[-\\w]*\\.(yml|yaml|properties)"))
                            .forEach(configs::add);
                } catch (IOException ignored) {
                    // A missing config/ directory is the common case.
                }
            }
        }
        configs.sort(Comparator.comparing((Path f) -> f.getFileName().toString().contains("-"))
                .thenComparing(f -> rel(f)));
        for (Path f : configs) {
            try {
                List<String> lines = Files.readAllLines(f, StandardCharsets.ISO_8859_1);
                Map<String, String> values = f.toString().endsWith(".properties") ? properties(lines) : yaml(lines);
                values.forEach((k, v) -> {
                    if (BASE_PATH_KEYS.contains(k)) {
                        basePaths.putIfAbsent(k, v);
                    }
                });
            } catch (IOException ignored) {
                // Unreadable config only loses base paths.
            }
        }
    }

    private static Map<String, String> properties(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) {
                continue;
            }
            int sep = l.indexOf('=') >= 0 ? l.indexOf('=') : l.indexOf(':');
            if (sep > 0) {
                out.putIfAbsent(l.substring(0, sep).trim(), unquote(l.substring(sep + 1).trim()));
            }
        }
        return out;
    }

    /** A flattening YAML reader for scalar keys; lists and multi-line values are ignored. */
    private static Map<String, String> yaml(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Deque<Map.Entry<Integer, String>> stack = new java.util.ArrayDeque<>();
        for (String line : lines) {
            if (line.startsWith("---")) {
                stack.clear();
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher m = YAML_KEY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!stack.isEmpty() && stack.peekLast().getKey() >= indent) {
                stack.removeLast();
            }
            StringBuilder key = new StringBuilder();
            for (Map.Entry<Integer, String> e : stack) {
                key.append(e.getValue()).append('.');
            }
            key.append(m.group(2));
            String value = m.group(3).replaceAll("\\s+#.*$", "").trim();
            if (value.isEmpty()) {
                stack.addLast(Map.entry(indent, m.group(2)));
            } else {
                out.putIfAbsent(key.toString(), unquote(value));
            }
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private String moduleOf(String relPath) {
        String best = ".";
        for (String m : modules) {
            if (m.equals(".")) {
                continue;
            }
            if (relPath.startsWith(m + "/") && m.length() > best.length()) {
                best = m;
            }
        }
        return best;
    }

    private String rel(Path p) {
        String r = root.relativize(p).toString().replace('\\', '/');
        return r.isEmpty() ? "." : r;
    }

    private static final Pattern BOOT_PARENT =
            Pattern.compile("spring-boot-starter-parent</artifactId>\\s*<version>([^<]+)");
    private static final Pattern BOOT_PROPERTY = Pattern.compile("<spring-boot\\.version>([^<]+)");
    private static final Pattern BOOT_GRADLE =
            Pattern.compile("org\\.springframework\\.boot['\"]?\\)?\\s*version\\s*['\"]([^'\"]+)");
    private static final Pattern BOOT_CATALOG =
            Pattern.compile("(?m)^\\s*spring-?[bB]oot\\s*=\\s*(?:\\{[^}\\n]*version\\s*=\\s*)?\"([^\"]+)\"");
    private static final Pattern JAVA_MAVEN =
            Pattern.compile("<(?:java\\.version|maven\\.compiler\\.release|maven\\.compiler\\.source)>([^<]+)");
    private static final Pattern JAVA_GRADLE =
            Pattern.compile("(?:JavaLanguageVersion\\.of\\(|sourceCompatibility\\s*=\\s*['\"]?(?:JavaVersion\\.VERSION_)?)([\\w.]+)");

    private void readBuildFiles(List<Path> buildFiles) {
        boolean maven = false;
        boolean gradle = false;
        StringBuilder all = new StringBuilder();
        buildFiles.sort(Comparator.comparing(p -> rel(p)));
        for (Path b : buildFiles) {
            String fileName = b.getFileName().toString();
            if (fileName.equals("pom.xml")) {
                maven = true;
            } else if (fileName.startsWith("build.gradle")) {
                gradle = true;
            }
            try {
                String content = Files.readString(b, StandardCharsets.ISO_8859_1);
                all.append(content).append('\n');
                String lowerContent = content.toLowerCase();
                Set<String> own = declaresByModule.computeIfAbsent(rel(b.getParent()), k -> new TreeSet<>());
                DECLARATIONS.forEach((needle, feature) -> {
                    if (lowerContent.contains(needle)) {
                        own.add(feature);
                    }
                });
            } catch (IOException ignored) {
                // An unreadable build file only loses build-level facts, never sources.
            }
        }
        buildTool = maven && gradle ? "maven+gradle" : maven ? "maven" : gradle ? "gradle" : "none";
        String text = all.toString();
        String lower = text.toLowerCase();
        DECLARATIONS.forEach((needle, feature) -> {
            if (lower.contains(needle)) {
                declares.add(feature);
            }
        });
        springBootVersion = first(text, BOOT_PARENT, BOOT_PROPERTY, BOOT_GRADLE, BOOT_CATALOG);
        javaVersion = first(text, JAVA_MAVEN, JAVA_GRADLE);
    }

    private void countXmlEndpoints(List<Path> xmlFiles) {
        for (Path x : xmlFiles) {
            String name = x.getFileName().toString();
            if (name.equals("pom.xml")) {
                continue;
            }
            try {
                if (Files.size(x) > 2_000_000) {
                    continue;
                }
                String s = Files.readString(x, StandardCharsets.ISO_8859_1);
                xmlJaxWsEndpoints += count(s, "<jaxws:endpoint");
                xmlUrlHandlerMappings += count(s, "SimpleUrlHandlerMapping");
                xmlScheduledTasks += count(s, "<task:scheduled ") + count(s, "<task:scheduled>");
                Matcher from = CAMEL_FROM.matcher(s);
                while (from.find()) {
                    if (!CAMEL_INTERNAL.contains(from.group(1))) {
                        xmlCamelRoutes++;
                    }
                }
                xmlTaskAnnotationDriven |= s.contains("<task:annotation-driven");
                if (name.equals("web.xml")) {
                    webXmlServlets += count(s, "<servlet>");
                    webXmlFilters += count(s, "<filter>");
                    webXmlListeners += count(s, "<listener>");
                    Matcher m = SERVLET_MAPPING.matcher(s);
                    while (m.find()) {
                        Matcher u = URL_PATTERN.matcher(m.group(1));
                        while (u.find()) {
                            servletMappings.add(u.group(1).trim());
                        }
                    }
                }
            } catch (IOException ignored) {
                // Unreadable XML only loses a warning.
            }
        }
    }

    private static final Pattern CAMEL_FROM = Pattern.compile("<from\\s+uri=\"([a-z0-9+-]+):");
    /** In-process Camel endpoints: they connect routes to each other and take nothing from outside. */
    private static final Set<String> CAMEL_INTERNAL = Set.of("direct", "direct-vm", "seda", "vm", "disruptor", "disruptor-vm");
    private static final Pattern SERVLET_MAPPING = Pattern.compile("<servlet-mapping>(.*?)</servlet-mapping>", Pattern.DOTALL);
    private static final Pattern URL_PATTERN = Pattern.compile("<url-pattern>([^<]+)</url-pattern>");

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String first(String text, Pattern... patterns) {
        for (Pattern p : patterns) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }
}
