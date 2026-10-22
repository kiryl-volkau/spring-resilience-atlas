package studio.volkoff.atlas.report;

import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.scan.Scanner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Renders {@code report.html}: one self-contained page from the atlas and, when present, the audit
 * records. No model writes HTML or picks a colour; every colour is derived here from verdict and
 * finding records, by the rules in the page's "How to read this report" section.
 */
public final class ReportBuilder {

    public record Options(Path repo, Path atlasDir, Path auditDir, Path flowsDir, Path out, String title) {
    }

    /** What the command prints: counts and the top of the attention list. */
    public record Summary(Path out, int operations, Map<String, Integer> byKind, Map<String, Integer> byLevel,
                          Map<String, Integer> byColour, int notAnalysed, List<Map<String, Object>> top,
                          List<String> warnings, String auditStatus, List<String> auditProblems) {
    }

    private static final Map<String, Integer> SEVERITY = Map.of("CRITICAL", 4, "HIGH", 3, "MEDIUM", 2, "LOW", 1);
    private static final Map<String, Integer> CONFIDENCE = Map.of("HIGH", 3, "MEDIUM", 2, "LOW", 1);
    private static final Set<String> COLOURS = Set.of("RED", "YELLOW", "GREEN", "GREY");
    private static final int SNIPPET_MAX = 200;
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._-]+");
    /** Summary bucket for operations whose level is NONE only because their code was never read. */
    public static final String NOT_ANALYSED = "NOT_ANALYSED";

    private final Map<String, List<String>> fileLines = new HashMap<>();
    private final List<String> auditProblems = new ArrayList<>();

    public Summary build(Options o) throws IOException {
        AtlasStore atlas = AtlasStore.open(o.repo(), o.atlasDir());
        Map<String, Map<String, Object>> effectsById = new HashMap<>();
        for (Map<String, Object> row : atlas.effects()) {
            effectsById.put(String.valueOf(row.get("id")), row);
        }
        Map<String, Map<String, Object>> verdicts = new HashMap<>();
        for (Map<String, Object> v : readAudit(o.auditDir().resolve("verdicts.jsonl"))) {
            verdicts.put(String.valueOf(v.get("id")), v);
        }
        List<Map<String, Object>> allFindings = readAudit(o.auditDir().resolve("findings.jsonl"));
        Map<String, Object> coverage = readAuditObject(o.auditDir().resolve("coverage.json"));

        List<Map<String, Object>> eps = atlas.entrypoints();
        Risk.Context context = Risk.Context.from(eps);
        boolean domains = !atlas.domains().isEmpty();
        Map<String, Long> perModule = new HashMap<>();
        for (Map<String, Object> e : eps) {
            perModule.merge(String.valueOf(e.get("module")), 1L, Long::sum);
        }
        long largest = perModule.values().stream().mapToLong(Long::longValue).max().orElse(0);
        // A module split where one module holds nearly everything tells the reader nothing.
        boolean byModule = perModule.size() > 1 && largest < 0.9 * eps.size();
        String commonPrefix = commonPackagePrefix(eps);

        List<String> files = new ArrayList<>();
        Map<String, Integer> fileIndex = new HashMap<>();
        List<Map<String, Object>> ops = new ArrayList<>();
        Map<String, Map<String, Object>> flows = new LinkedHashMap<>();
        for (Map<String, Object> ep : eps) {
            String id = String.valueOf(ep.get("id"));
            Map<String, Object> fx = effectsById.get(id);
            List<Map<String, Object>> effects = fx == null ? List.of() : maps(fx.get("effects"));
            String entryReturns = fx == null || fx.get("entryReturns") == null ? null : fx.get("entryReturns").toString();
            Risk.Assessment risk = Risk.assess(ep, effects, entryReturns, context);
            Map<String, Object> op = new LinkedHashMap<>();
            String cls = String.valueOf(ep.get("cls"));
            op.put("id", id);
            op.put("kind", ep.get("kind"));
            op.put("subkind", ep.get("subkind"));
            op.put("target", ep.get("target"));
            op.put("cls", simpleName(cls));
            op.put("pkg", packageOf(cls));
            op.put("method", ep.get("method"));
            op.put("file", index(String.valueOf(ep.get("file")), files, fileIndex));
            op.put("line", ep.get("line"));
            op.put("module", ep.get("module"));
            op.put("area", area(atlas, ep, cls, domains, byModule, commonPrefix));
            op.put("discovery", ep.get("status"));
            op.put("origin", ep.get("origin"));
            op.put("via", ep.get("via"));
            op.put("placeholder", ep.get("placeholder"));
            op.put("attrs", Risk.attrs(ep));
            op.put("tags", tags(ep));
            op.put("mutating", risk.mutating());
            op.put("level", risk.level().name());
            op.put("score", risk.score());
            List<Map<String, Object>> reasons = new ArrayList<>();
            for (Risk.Reason r : risk.reasons()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", r.code());
                m.put("level", r.level().name());
                m.put("title", r.title());
                m.put("ev", r.evidence());
                reasons.add(m);
            }
            op.put("reasons", reasons);
            op.put("mitigations", risk.mitigations());
            List<Map<String, Object>> effectRows = new ArrayList<>();
            for (Map<String, Object> e : effects) {
                Map<String, Object> m = new LinkedHashMap<>();
                String file = String.valueOf(e.get("file"));
                int line = e.get("line") instanceof Number n ? n.intValue() : 0;
                m.put("s", e.get("signal"));
                m.put("d", e.get("detail"));
                m.put("f", index(file, files, fileIndex));
                m.put("l", line);
                m.put("sym", e.get("symbol"));
                m.put("hop", e.get("hop"));
                m.put("tx", e.get("inTx"));
                if (Boolean.TRUE.equals(e.get("inReadOnlyTx"))) {
                    m.put("ro", true);
                }
                m.put("code", snippet(o.repo(), file, line));
                effectRows.add(m);
            }
            op.put("effects", effectRows);
            Map<String, Object> reach = new LinkedHashMap<>();
            reach.put("analysed", fx != null && Boolean.TRUE.equals(fx.get("analysed")));
            if (fx != null) {
                reach.put("methods", fx.get("methods"));
                reach.put("files", fx.get("files") instanceof List<?> l ? l.size() : 0);
                reach.put("truncated", fx.get("truncated"));
                reach.put("budgetHit", fx.get("budgetHit"));
                reach.put("unresolved", fx.get("unresolvedCalls"));
                reach.put("beyond", fx.get("beyondReach"));
            }
            op.put("reach", reach);

            Map<String, Object> verdict = verdicts.get(id);
            List<Map<String, Object>> opFindings = findingsFor(id, verdict, allFindings);
            Colour c = colour(verdict, opFindings);
            op.put("colour", c.colour());
            op.put("confidence", c.confidence());
            op.put("colourNote", c.note());
            op.put("tier", verdict == null || "STALE".equals(verdict.get("status")) ? "STATIC" : verdict.get("tier"));
            op.put("verdict", verdict);
            op.put("findings", opFindings);
            ops.add(op);

            Object flowId = verdict == null ? null : verdict.get("flowId");
            if (flowId != null && !flows.containsKey(flowId.toString())) {
                String fid = flowId.toString();
                if (SAFE_ID.matcher(fid).matches()) {
                    flows.put(fid, readAuditObjectOrNull(o.flowsDir().resolve(fid + ".json")));
                } else {
                    auditProblems.add("verdict of " + id + ": flowId " + fid + " is not a file name, its flow is not shown");
                    flows.put(fid, null);
                }
            }
        }

        Map<String, Object> manifest = atlas.manifest;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", o.title() != null ? o.title() : defaultTitle(o.repo()));
        data.put("repo", o.repo().getFileName() == null ? o.repo().toString() : o.repo().getFileName().toString());
        data.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("generator", "resilience-atlas " + Scanner.VERSION);
        Map<String, Object> atlasInfo = new LinkedHashMap<>();
        for (String k : List.of("commit", "dirty", "scannedAt", "scanner", "build", "files", "durationMs", "javaRuntime")) {
            atlasInfo.put(k, manifest.get(k));
        }
        atlasInfo.put("effectsPresent", Files.exists(o.atlasDir().resolve("effects.jsonl")));
        atlasInfo.put("areaSource", domains ? "domain" : byModule ? "module" : "package");
        data.put("atlas", atlasInfo);
        data.put("warnings", warnings(manifest));
        data.put("unresolvedAnnotations", manifest.getOrDefault("unresolvedAnnotations", List.of()));
        Map<String, Object> rules = new LinkedHashMap<>();
        Risk.RULES.forEach((code, tt) -> rules.put(code, Map.of("title", tt[0], "text", tt[1])));
        data.put("rules", rules);
        data.put("coverage", coverage);
        data.put("auditProblems", auditProblems);
        data.put("files", files);
        data.put("ops", ops);
        data.put("flows", flows);

        // Escaping every "<" keeps "</script>" and "<!--" in snippets from ending the data block.
        String payload = Json.write(data).replace("<", "\\u003c");
        Files.createDirectories(o.out().toAbsolutePath().getParent());
        Files.writeString(o.out(), fill(template(), escapeHtml(String.valueOf(data.get("title"))), payload), StandardCharsets.UTF_8);

        return summary(o.out(), ops, manifest, coverage);
    }

    /** Puts the title and the data into the template in one pass, so neither can inject into the other. */
    private static String fill(String template, String title, String payload) {
        StringBuilder out = new StringBuilder(template.length() + payload.length());
        int i = 0;
        while (true) {
            int t = template.indexOf("__TITLE__", i);
            int d = template.indexOf("__DATA__", i);
            if (t < 0 && d < 0) {
                break;
            }
            boolean titleFirst = t >= 0 && (d < 0 || t < d);
            int at = titleFirst ? t : d;
            out.append(template, i, at).append(titleFirst ? title : payload);
            i = at + (titleFirst ? "__TITLE__".length() : "__DATA__".length());
        }
        return out.append(template.substring(i)).toString();
    }

    /** Findings name their operation, or belong to the verdict's flow or finding list. */
    private static List<Map<String, Object>> findingsFor(String id, Map<String, Object> verdict, List<Map<String, Object>> all) {
        Object flowId = verdict == null ? null : verdict.get("flowId");
        Set<String> ids = new LinkedHashSet<>();
        if (verdict != null && verdict.get("findingIds") instanceof List<?> l) {
            l.forEach(x -> ids.add(String.valueOf(x)));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : all) {
            boolean mine = id.equals(f.get("operationId"))
                    || f.get("operationId") == null && flowId != null && flowId.equals(f.get("flowId"))
                    || ids.contains(String.valueOf(f.get("id")));
            if (mine) {
                out.add(f);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> f) -> -SEVERITY.getOrDefault(String.valueOf(f.get("severity")), 0)));
        return out;
    }

    private record Colour(String colour, String confidence, String note) {
    }

    /**
     * GREY without a current verdict. RED needs a DEEP or VERIFIED verdict with a live CRITICAL/HIGH
     * finding that is VERIFIED, or a CANDIDATE with HIGH confidence. A DEEP verdict without live
     * findings keeps its own colour when it states one, else it is GREEN. TRIAGE cannot assert RED:
     * a triage RED is shown YELLOW until the deep audit runs.
     */
    private static Colour colour(Map<String, Object> verdict, List<Map<String, Object>> findings) {
        if (verdict == null) {
            return new Colour("GREY", null, "");
        }
        if ("STALE".equals(verdict.get("status"))) {
            return new Colour("GREY", null, "stale: files changed since the verdict (was " + verdict.getOrDefault("previousColour", "?") + ")");
        }
        String tier = String.valueOf(verdict.get("tier"));
        String confidence = CONFIDENCE.containsKey(String.valueOf(verdict.get("confidence"))) ? verdict.get("confidence").toString() : "MEDIUM";
        String stated = COLOURS.contains(String.valueOf(verdict.get("colour"))) ? verdict.get("colour").toString() : null;
        if (tier.equals("DEEP") || tier.equals("VERIFIED")) {
            List<Map<String, Object>> live = new ArrayList<>();
            for (Map<String, Object> f : findings) {
                if (!"DISPROVED".equals(f.get("status")) && !"DUPLICATE".equals(f.get("status"))) {
                    live.add(f);
                }
            }
            boolean anyVerifiedRed = false;
            boolean anyRed = false;
            for (Map<String, Object> f : live) {
                boolean severe = SEVERITY.getOrDefault(String.valueOf(f.get("severity")), 0) >= 3;
                if (severe && "VERIFIED".equals(f.get("status"))) {
                    anyVerifiedRed = true;
                }
                if (severe && ("VERIFIED".equals(f.get("status")) || "HIGH".equals(f.get("confidence")))) {
                    anyRed = true;
                }
            }
            if (anyRed) {
                return new Colour("RED", confidence, anyVerifiedRed ? "" : "unverified");
            }
            if (!live.isEmpty()) {
                return new Colour("YELLOW", confidence, "");
            }
            if ("RED".equals(stated)) {
                return new Colour("YELLOW", confidence, "verdict says RED but no finding is attached");
            }
            return new Colour(stated == null ? "GREEN" : stated, confidence, "");
        }
        String colour = stated == null ? "YELLOW" : stated;
        if (CONFIDENCE.getOrDefault(confidence, 1) > 2) {
            confidence = "MEDIUM";
        }
        if (colour.equals("RED")) {
            return new Colour("YELLOW", confidence, "triage suspects a defect; deep audit pending");
        }
        return new Colour(colour, confidence, "");
    }

    private Summary summary(Path out, List<Map<String, Object>> ops, Map<String, Object> manifest, Map<String, Object> coverage) {
        Map<String, Integer> byKind = new TreeMap<>();
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        for (Risk.Level l : Risk.Level.values()) {
            byLevel.put(l.name(), 0);
        }
        Map<String, Integer> byColour = new LinkedHashMap<>();
        for (String c : List.of("RED", "YELLOW", "GREEN", "GREY")) {
            byColour.put(c, 0);
        }
        byLevel.put(NOT_ANALYSED, 0);
        int notAnalysed = 0;
        for (Map<String, Object> op : ops) {
            byKind.merge(String.valueOf(op.get("kind")), 1, Integer::sum);
            byColour.merge(String.valueOf(op.get("colour")), 1, Integer::sum);
            boolean analysed = op.get("reach") instanceof Map<?, ?> r && Boolean.TRUE.equals(r.get("analysed"));
            if (!analysed) {
                notAnalysed++;
            }
            String level = String.valueOf(op.get("level"));
            byLevel.merge(!analysed && level.equals("NONE") ? NOT_ANALYSED : level, 1, Integer::sum);
        }
        List<Map<String, Object>> top = new ArrayList<>(ops);
        top.removeIf(op -> "NONE".equals(op.get("level")) || "LOW".equals(op.get("level")));
        top.sort(Comparator.comparing((Map<String, Object> op) -> -((Number) op.get("score")).intValue()));
        List<String> warnings = new ArrayList<>();
        if (manifest.get("warnings") instanceof List<?> w) {
            w.forEach(x -> warnings.add(String.valueOf(x)));
        }
        return new Summary(out, ops.size(), byKind, byLevel, byColour, notAnalysed, top.subList(0, Math.min(10, top.size())),
                warnings, String.valueOf(coverage.getOrDefault("status", "NOT_RUN")), auditProblems);
    }

    /**
     * Rows of a model-written audit file. A bad row is skipped and reported instead of failing
     * the page, and a byte-order mark (Windows PowerShell 5.1 writes one) is ignored.
     */
    private List<Map<String, Object>> readAudit(Path f) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.exists(f)) {
            return rows;
        }
        List<String> lines = readLines(f);
        for (int i = 0; i < lines.size(); i++) {
            String line = stripBom(lines.get(i));
            if (line.isBlank()) {
                continue;
            }
            try {
                rows.add(Json.parseObject(line));
            } catch (IllegalArgumentException e) {
                auditProblems.add(f.getFileName() + ":" + (i + 1) + ": " + e.getMessage());
            }
        }
        return rows;
    }

    private Map<String, Object> readAuditObject(Path f) throws IOException {
        Map<String, Object> m = readAuditObjectOrNull(f);
        return m == null ? new LinkedHashMap<>() : m;
    }

    private Map<String, Object> readAuditObjectOrNull(Path f) throws IOException {
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return Json.parseObject(String.join("\n", readLines(f)));
        } catch (IllegalArgumentException e) {
            auditProblems.add(f.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    private static String stripBom(String s) {
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** The manifest's warning strings with a stable code each, so the page can title and group them. */
    private static List<Map<String, Object>> warnings(Map<String, Object> manifest) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(manifest.get("warnings") instanceof List<?> list)) {
            return out;
        }
        for (Object w : list) {
            String text = String.valueOf(w);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", warningCode(text));
            m.put("text", text);
            out.add(m);
        }
        return out;
    }

    static String warningCode(String text) {
        if (text.contains("failed to parse")) {
            return "parse-failures";
        }
        if (text.contains("Kotlin files")) {
            return "kotlin";
        }
        if (text.contains("missing from the sources")) {
            return "missing-interfaces";
        }
        if (text.contains("no consumer was found")) {
            return "messaging-without-consumers";
        }
        if (text.contains("path prefix")) {
            return "path-prefix";
        }
        if (text.contains("web.xml")) {
            return "web-xml";
        }
        if (text.contains("registered in code")) {
            return "programmatic-registration";
        }
        if (text.contains("in XML") || text.contains("XML map")) {
            return "xml";
        }
        if (text.contains("entry-like annotations")) {
            return "unresolved-annotations";
        }
        if (text.contains("extend classes outside")) {
            return "library-parents";
        }
        if (text.contains("@EnableScheduling")) {
            return "scheduling-not-enabled";
        }
        if (text.contains("@EnableAsync")) {
            return "async-not-enabled";
        }
        if (text.contains("Spring Integration")) {
            return "spring-integration";
        }
        if (text.contains("Spring Cloud Gateway")) {
            return "gateway";
        }
        if (text.contains("no Java or Kotlin")) {
            return "no-sources";
        }
        return "other";
    }

    private static List<String> tags(Map<String, Object> ep) {
        List<String> tags = new ArrayList<>();
        if (Risk.attrs(ep).get("webhook") instanceof Map<?, ?> w) {
            String verdict = String.valueOf(w.get("verdict"));
            tags.add(switch (verdict) {
                case "WEBHOOK" -> "webhook";
                case "OAUTH_CALLBACK" -> "oauth-callback";
                case "WEBHOOK_MANAGEMENT" -> "webhook-management";
                case "UNAUTHENTICATED_WRITE" -> "unauthenticated-write";
                default -> verdict.toLowerCase(java.util.Locale.ROOT);
            });
        }
        if (Boolean.TRUE.equals(Risk.attrs(ep).get("disabled"))) {
            tags.add("disabled");
        }
        return tags;
    }

    private static String area(AtlasStore atlas, Map<String, Object> ep, String cls, boolean domains, boolean byModule,
                               String commonPrefix) throws IOException {
        if (domains) {
            String d = atlas.domainOf(cls);
            return d == null ? "(unassigned)" : d;
        }
        if (byModule) {
            String m = String.valueOf(ep.get("module"));
            return m.isEmpty() || m.equals(".") ? "(root)" : m;
        }
        String pkg = packageOf(cls);
        String rest = pkg.length() > commonPrefix.length() ? pkg.substring(commonPrefix.length()).replaceFirst("^\\.", "") : "";
        int dot = rest.indexOf('.');
        String first = dot < 0 ? rest : rest.substring(0, dot);
        return first.isEmpty() ? "(root)" : first;
    }

    private static String commonPackagePrefix(List<Map<String, Object>> eps) {
        String prefix = null;
        for (Map<String, Object> ep : eps) {
            String pkg = packageOf(String.valueOf(ep.get("cls")));
            if (prefix == null) {
                prefix = pkg;
                continue;
            }
            while (!prefix.isEmpty() && !(pkg.equals(prefix) || pkg.startsWith(prefix + "."))) {
                int dot = prefix.lastIndexOf('.');
                prefix = dot < 0 ? "" : prefix.substring(0, dot);
            }
        }
        return prefix == null ? "" : prefix;
    }

    /** Lower-case package segments; the first capitalised segment starts the class name. */
    private static String packageOf(String cls) {
        String[] parts = cls.split("\\.");
        StringBuilder pkg = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            String p = parts[i];
            if (!p.isEmpty() && Character.isUpperCase(p.charAt(0))) {
                break;
            }
            if (pkg.length() > 0) {
                pkg.append('.');
            }
            pkg.append(p);
        }
        return pkg.toString();
    }

    private static String simpleName(String cls) {
        String pkg = packageOf(cls);
        return pkg.isEmpty() || cls.length() <= pkg.length() ? cls : cls.substring(pkg.length() + 1);
    }

    private static int index(String file, List<String> files, Map<String, Integer> fileIndex) {
        return fileIndex.computeIfAbsent(file, f -> {
            files.add(f);
            return files.size() - 1;
        });
    }

    private String snippet(Path repo, String file, int line) {
        List<String> lines = fileLines.computeIfAbsent(file, f -> readLines(repo.resolve(f)));
        if (line < 1 || line > lines.size()) {
            return null;
        }
        int i = line - 1;
        StringBuilder s = new StringBuilder(lines.get(i).strip());
        // An annotation alone says little: follow it with the declaration it is on, skipping the
        // other annotations in between.
        if (s.length() > 0 && s.charAt(0) == '@') {
            while (i + 1 < lines.size() && lines.get(i + 1).strip().startsWith("@")) {
                i++;
            }
            if (i + 1 < lines.size()) {
                i++;
                s.append(' ').append(lines.get(i).strip());
            }
        }
        // A statement split over lines reads better whole: take up to two continuation lines.
        for (int j = i + 1; j < Math.min(lines.size(), i + 3) && !endsStatement(s); j++) {
            s.append(' ').append(lines.get(j).strip());
        }
        return s.length() > SNIPPET_MAX ? s.substring(0, SNIPPET_MAX) + "…" : s.toString();
    }

    /**
     * UTF-8, UTF-16 with a byte-order mark (Windows PowerShell 5.1 writes it), else windows-1251 like
     * the parser, so legacy sources and hand-made audit files still read.
     */
    private static List<String> readLines(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String text;
            if (bytes.length >= 2 && (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE || bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF)) {
                return stripBom(new String(bytes, StandardCharsets.UTF_16)).lines().toList();
            }
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.isSupported("windows-1251") ? Charset.forName("windows-1251") : StandardCharsets.ISO_8859_1);
            }
            return stripBom(text).lines().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static boolean endsStatement(CharSequence s) {
        String t = s.toString().strip();
        return t.isEmpty() || t.endsWith(";") || t.endsWith("{") || t.endsWith("}") || t.endsWith("*/");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    private static String defaultTitle(Path repo) {
        Path name = repo.getFileName();
        Path parent = repo.getParent() == null ? null : repo.getParent().getFileName();
        String n = name == null ? repo.toString() : name.toString();
        if (parent != null && Set.of("backend", "server", "service", "app", "api", "source", "src").contains(n)) {
            n = parent + "/" + n;
        }
        return n;
    }

    private static String template() throws IOException {
        try (InputStream in = ReportBuilder.class.getResourceAsStream("/report/report.html")) {
            if (in == null) {
                throw new IllegalStateException("report template is missing from the scanner build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
