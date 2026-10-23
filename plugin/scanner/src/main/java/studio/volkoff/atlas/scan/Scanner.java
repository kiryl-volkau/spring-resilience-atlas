package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.effects.EffectAnalyzer;
import studio.volkoff.atlas.extract.AnnotationCensus;
import studio.volkoff.atlas.extract.EntryPoint;
import studio.volkoff.atlas.extract.EntryPointExtractor;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Origin;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.store.Git;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Builds {@code .reliability/atlas/} for a repository: manifest, entry points and types. */
public final class Scanner {

    public static final String VERSION = "0.4.0";

    private static final Set<String> STEREOTYPES = Set.of("Controller", "ControllerAdvice", "RestControllerAdvice",
            "Service", "Component", "Repository", "Configuration", "SpringBootApplication", "Entity", "Table",
            "Embeddable", "MappedSuperclass", "Document", "FeignClient", "HttpExchange", "WebService", "Endpoint",
            "Mapper", "Transactional", "Async", "Validated", "EnableScheduling", "EnableAsync", "EnableKafka",
            "EnableRabbit", "EnableJms", "EnableBatchProcessing");

    public record Summary(Map<String, Object> manifest, int entryPoints) {
    }

    public Summary scan(Path repo, Path out) throws Exception {
        long started = System.nanoTime();
        Workspace ws = Workspace.load(repo);
        List<Workspace.SourceEntry> java = new ArrayList<>();
        List<Workspace.SourceEntry> regexOnly = new ArrayList<>();
        for (Workspace.SourceEntry e : ws.sources) {
            (e.origin() == Origin.KOTLIN || e.origin() == Origin.GROOVY ? regexOnly : java).add(e);
        }
        int kotlinFiles = (int) regexOnly.stream().filter(e -> e.origin() == Origin.KOTLIN).count();
        int groovyFiles = regexOnly.size() - kotlinFiles;

        JavaSourceParser.Result parsed = new JavaSourceParser().parse(java, ws.javaVersion);
        Index index = new Index(parsed.parsed());
        EntryPointExtractor extractor = new EntryPointExtractor(index, ws);
        List<EntryPoint> entryPoints = new ArrayList<>(extractor.extract());
        AnnotationCensus census = new AnnotationCensus(index);
        RegexEntryPoints regex = new RegexEntryPoints();
        for (Workspace.SourceEntry e : regexOnly) {
            regex.collectConstants(JavaSourceParser.readSource(e));
        }
        for (Workspace.SourceEntry e : parsed.failed()) {
            regex.collectConstants(JavaSourceParser.readSource(e));
        }
        for (Workspace.SourceEntry e : regexOnly) {
            entryPoints.addAll(regex.scan(e, JavaSourceParser.readSource(e), e.origin()));
        }
        for (Workspace.SourceEntry e : parsed.failed()) {
            entryPoints.addAll(regex.scan(e, JavaSourceParser.readSource(e), Origin.FALLBACK));
        }
        entryPoints.sort(Comparator.comparing((EntryPoint e) -> e.module)
                .thenComparing(e -> e.kind).thenComparing(e -> e.target).thenComparing(e -> e.cls)
                .thenComparing(e -> e.method).thenComparing(e -> e.subkind).thenComparingInt(e -> e.line));
        assignIds(entryPoints);

        Files.createDirectories(out);
        writeJsonl(out.resolve("entrypoints.jsonl"), entryPoints.stream().map(EntryPoint::toMap).toList());
        writeJsonl(out.resolve("types.jsonl"), typeRecords(index));
        EffectAnalyzer analyzer = new EffectAnalyzer(index, module -> ws.declaresFor(module, "spring-session"),
                ws.declares("spring-session"));
        List<EffectAnalyzer.Result> effects = new ArrayList<>();
        List<Map<String, Object>> effectRows = new ArrayList<>();
        for (EntryPoint e : entryPoints) {
            EffectAnalyzer.Result r = analyzer.analyse(e);
            effects.add(r);
            effectRows.add(r.toMap(e.id));
        }
        writeJsonl(out.resolve("effects.jsonl"), effectRows);

        Git git = new Git(ws.root);
        Map<String, Object> manifest = manifest(ws, parsed, kotlinFiles, groovyFiles, entryPoints, census,
                extractor.scheduledRecords(), CodeFacts.of(index), git, (System.nanoTime() - started) / 1_000_000);
        manifest.put("signals", new TreeMap<>(EffectAnalyzer.census(effects)));
        manifest.put("effectsAnalysed", effects.stream().filter(EffectAnalyzer.Result::analysed).count());
        Files.writeString(out.resolve("manifest.json"), Json.write(manifest) + "\n", StandardCharsets.UTF_8);
        return new Summary(manifest, entryPoints.size());
    }

    /**
     * SHA-256 over the path and content hash of every main-source file the scan reads, sorted by
     * path: equal values mean the atlas was built from exactly these sources, whatever git says.
     * An unreadable file contributes its path only.
     */
    public static String sourcesHash(Workspace ws) {
        try {
            MessageDigest all = MessageDigest.getInstance("SHA-256");
            List<Workspace.SourceEntry> sorted = new ArrayList<>(ws.sources);
            sorted.sort(Comparator.comparing(Workspace.SourceEntry::relPath));
            for (Workspace.SourceEntry e : sorted) {
                all.update(e.relPath().getBytes(StandardCharsets.UTF_8));
                all.update((byte) 0);
                try {
                    all.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(e.path())));
                } catch (IOException unreadable) {
                    all.update((byte) 1);
                }
                all.update((byte) '\n');
            }
            return HexFormat.of().formatHex(all.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void assignIds(List<EntryPoint> eps) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        Map<String, Integer> occurrences = new HashMap<>();
        Map<String, List<EntryPoint>> byShort = new HashMap<>();
        Map<EntryPoint, String> full = new HashMap<>();
        for (EntryPoint e : eps) {
            String key = e.key();
            int n = occurrences.merge(key, 1, Integer::sum);
            String hex = HexFormat.of().formatHex(sha1.digest((n == 1 ? key : key + "|" + n).getBytes(StandardCharsets.UTF_8)));
            full.put(e, hex);
            byShort.computeIfAbsent(hex.substring(0, 8), k -> new ArrayList<>()).add(e);
        }
        for (EntryPoint e : eps) {
            String hex = full.get(e);
            boolean collides = byShort.get(hex.substring(0, 8)).size() > 1;
            e.id = "EP-" + hex.substring(0, collides ? 12 : 8);
        }
    }

    private static List<Map<String, Object>> typeRecords(Index index) {
        Annotations an = new Annotations(index);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TypeDecl t : index.types()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fqn", t.fqn);
            m.put("kind", t.kind.name());
            m.put("abstract", t.isAbstract && t.kind == TypeDecl.Kind.CLASS);
            m.put("module", t.file.module());
            m.put("file", t.file.relPath());
            m.put("line", t.line);
            List<String> supers = new ArrayList<>();
            for (TypeDecl s : index.supertypes(t)) {
                supers.add(s.fqn);
            }
            supers.addAll(index.unresolvedSupertypes(t));
            m.put("supers", supers);
            Set<String> stereotypes = new LinkedHashSet<>();
            for (Annotations.Effective e : an.expandAll(t.annos, Ctx.of(t))) {
                if (STEREOTYPES.contains(e.base())) {
                    stereotypes.add(e.base());
                }
            }
            m.put("stereotypes", new ArrayList<>(stereotypes));
            Set<String> annos = new LinkedHashSet<>();
            for (Anno a : t.annos) {
                annos.add(a.simpleName());
            }
            m.put("annos", new ArrayList<>(annos));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("fqn")));
        return out;
    }

    private static Map<String, Object> manifest(Workspace ws, JavaSourceParser.Result parsed, int kotlinFiles,
                                                int groovyFiles, List<EntryPoint> eps, AnnotationCensus census, int scheduledRecords,
                                                CodeFacts facts, Git git, long millis) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schemaVersion", 1);
        m.put("scanner", "resilience-atlas " + VERSION);
        m.put("commit", git.head());
        m.put("dirty", git.dirty());
        m.put("scannedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("javaRuntime", Runtime.version().toString());
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("tool", ws.buildTool);
        build.put("modules", ws.modules.size());
        build.put("springBootVersion", ws.springBootVersion);
        build.put("javaVersion", ws.javaVersion);
        build.put("declares", new ArrayList<>(ws.declares));
        build.put("basePaths", ws.basePaths);
        if (!ws.servletMappings.isEmpty()) {
            build.put("servletMappings", new ArrayList<>(ws.servletMappings));
        }
        m.put("build", build);
        long generated = ws.sources.stream().filter(s -> s.origin() == Origin.GENERATED).count();
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("java", ws.sources.size() - kotlinFiles - groovyFiles);
        files.put("kotlin", kotlinFiles);
        files.put("groovy", groovyFiles);
        files.put("generated", generated);
        files.put("parseFailures", parsed.failed().size());
        m.put("files", files);
        m.put("sourcesHash", sourcesHash(ws));
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> statuses = new TreeMap<>();
        for (EntryPoint e : eps) {
            counts.merge(e.kind, 1, Integer::sum);
            statuses.merge(e.status.name(), 1, Integer::sum);
        }
        m.put("entrypoints", counts);
        m.put("statuses", statuses);
        m.put("warnings", warnings(ws, parsed, kotlinFiles, groovyFiles, eps, census, scheduledRecords, facts));
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (AnnotationCensus.Unresolved u : census.unresolved()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("annotation", "@" + u.name());
            row.put("count", u.count());
            row.put("samples", u.samples());
            unresolved.add(row);
        }
        m.put("unresolvedAnnotations", unresolved);
        m.put("durationMs", millis);
        return m;
    }

    private static List<String> warnings(Workspace ws, JavaSourceParser.Result parsed, int kotlinFiles, int groovyFiles,
                                         List<EntryPoint> eps, AnnotationCensus census, int scheduledRecords, CodeFacts facts) {
        List<String> w = new ArrayList<>();
        if (!parsed.failed().isEmpty()) {
            w.add(parsed.failed().size() + " Java files failed to parse with javac " + Runtime.version().feature()
                    + " and were scanned by regex (origin FALLBACK); first: " + String.join(" | ", parsed.failureSamples()));
        }
        if (kotlinFiles > 0) {
            w.add(kotlinFiles + " Kotlin files were scanned by regex only (origin KOTLIN): composed annotations and inherited mappings "
                    + "are not resolved there, constants only when they are string const vals");
        }
        if (groovyFiles > 0) {
            w.add(groovyFiles + " Groovy files were scanned by regex only (origin GROOVY): composed annotations and inherited mappings "
                    + "are not resolved there, constants only when they are single-line static final strings");
        }
        long missing = eps.stream().filter(e -> e.via.stream().anyMatch(v -> v.startsWith("missing-interface:"))).count();
        if (missing > 0) {
            w.add(missing + " HTTP records point at interfaces missing from the sources (generated code?); build the project, then rescan");
        }
        boolean messagingDeclared = ws.declares("spring-kafka") || ws.declares("kafka-clients") || ws.declares("sqs")
                || ws.declares("spring-rabbit") || ws.declares("spring-amqp") || ws.declares("spring-jms");
        if (messagingDeclared && eps.stream().noneMatch(e -> e.kind.equals("MESSAGING"))) {
            w.add("messaging libraries are declared but no consumer was found; consumers may be wired in configuration or by code this scanner does not recognise");
        }
        if (ws.xmlJaxWsEndpoints > 0) {
            w.add(ws.xmlJaxWsEndpoints + " JAX-WS endpoints are declared in XML (<jaxws:endpoint>) and are not in the atlas");
        }
        if (ws.xmlUrlHandlerMappings > 0) {
            w.add(ws.xmlUrlHandlerMappings + " SimpleUrlHandlerMapping declarations in XML map URLs that are not in the atlas");
        }
        List<AnnotationCensus.Unresolved> unresolved = census.unresolved();
        if (!unresolved.isEmpty()) {
            List<String> top = new ArrayList<>();
            for (AnnotationCensus.Unresolved u : unresolved.subList(0, Math.min(8, unresolved.size()))) {
                top.add("@" + u.name() + " x" + u.count() + " (" + u.samples().get(0) + ")");
            }
            w.add(unresolved.stream().mapToInt(AnnotationCensus.Unresolved::count).sum()
                    + " entry-like annotations come from outside the source set and may hide entry points: "
                    + String.join(", ", top) + (unresolved.size() > 8 ? ", ..." : "")
                    + "; full list in manifest.unresolvedAnnotations");
        }
        List<String> parents = census.libraryControllerParents();
        if (!parents.isEmpty()) {
            w.add(parents.size() + " controllers extend classes outside the source set, whose inherited mappings are unknown: "
                    + String.join("; ", parents.subList(0, Math.min(3, parents.size()))) + (parents.size() > 3 ? "; ..." : ""));
        }
        if (facts.pathPrefix()) {
            w.add("a WebMvcConfigurer adds a path prefix (addPathPrefix): controller paths in the atlas are as declared, without that prefix");
        }
        if (!ws.servletMappings.isEmpty() || ws.webXmlServlets + ws.webXmlFilters + ws.webXmlListeners > 0) {
            w.add("web.xml declares " + ws.webXmlServlets + " servlets, " + ws.webXmlFilters + " filters and " + ws.webXmlListeners
                    + " listeners that are not in the atlas" + (ws.servletMappings.isEmpty() ? ""
                    : "; controller paths are relative to the servlet mappings " + String.join(", ", ws.servletMappings)));
        }
        if (facts.registeredInCode() > 0) {
            w.add(facts.registeredInCode() + " servlets, filters, session listeners or JAX-WS endpoints are registered in code "
                    + "(ServletRegistrationBean, @WebServlet, HttpSessionListener, Endpoint.publish) and are not in the atlas: "
                    + String.join(", ", facts.registrationSamples()));
        }
        if (ws.xmlScheduledTasks > 0) {
            w.add(ws.xmlScheduledTasks + " scheduled tasks are declared in XML (<task:scheduled>) and are not in the atlas");
        }
        if (ws.xmlCamelRoutes > 0) {
            w.add(ws.xmlCamelRoutes + " Camel routes in XML consume from outside endpoints (<from uri>) and are not in the atlas");
        }
        if (scheduledRecords > 0 && !census.enableScheduling() && !ws.xmlTaskAnnotationDriven) {
            w.add(scheduledRecords + " @Scheduled methods but no @EnableScheduling in the source set: they run only if scheduling is enabled elsewhere");
        }
        if (census.asyncWithoutEnable() && !ws.xmlTaskAnnotationDriven) {
            w.add("@Async is used but no @EnableAsync is in the source set: those methods run synchronously unless async is enabled elsewhere");
        }
        if (ws.declares("spring-integration") && facts.springIntegrationUsed()) {
            w.add("Spring Integration is declared: only @InboundChannelAdapter is scanned; IntegrationFlow DSL and XML flows are not in the atlas");
        }
        if (ws.declares("spring-cloud-gateway")) {
            w.add("Spring Cloud Gateway is declared: its routes come from YAML or RouteLocator beans and are not in the atlas");
        }
        if (ws.sources.isEmpty()) {
            w.add("no Java, Kotlin or Groovy main sources found");
        }
        return w;
    }

    private static void writeJsonl(Path file, List<Map<String, Object>> rows) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map<String, Object> r : rows) {
                w.write(Json.write(r));
                w.write('\n');
            }
        }
    }
}
