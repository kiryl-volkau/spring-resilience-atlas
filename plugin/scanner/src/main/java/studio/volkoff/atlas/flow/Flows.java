package studio.volkoff.atlas.flow;

import studio.volkoff.atlas.audit.Slices;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.report.Risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The flow documents under {@code .reliability/flows/}: reading them, the fingerprint that tells
 * when the code under a flow changed, the places a flow crosses, and the derived {@code index.json}.
 */
public final class Flows {

    public static final Pattern ID = Pattern.compile("FLOW-[a-z0-9-]+");
    /** Steps that change state or hand work on: each must belong to one business part. */
    static final Set<String> EFFECTFUL = Set.of("DB_WRITE", "MESSAGE_SEND", "EXTERNAL_CALL", "EVENT_PUBLISH", "ASYNC_FORK");

    final Path repo;
    final Path atlasDir;
    final Path auditDir;
    final Path dir;

    public Flows(Path repo, Path atlasDir, Path auditDir, Path dir) {
        this.repo = repo;
        this.atlasDir = atlasDir;
        this.auditDir = auditDir;
        this.dir = dir;
    }

    public Path doc(String id) {
        return dir.resolve(id + ".json");
    }

    public Path page(String id) {
        return dir.resolve(id + ".html");
    }

    public Path draft(String id) {
        return dir.resolve("drafts").resolve(id + ".json");
    }

    /** Every recorded flow document, version 1 ones included, by id. */
    public Map<String, Map<String, Object>> all() throws IOException {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".json") && ID.matcher(name.substring(0, name.length() - 5)).matches()) {
                    try {
                        out.put(name.substring(0, name.length() - 5), read(f));
                    } catch (IllegalArgumentException malformed) {
                        // A hand-broken file is left out of the index; record-flow names the problem.
                    }
                }
            }
        }
        return out;
    }

    public static Map<String, Object> read(Path f) throws IOException {
        String text = Files.readString(f, StandardCharsets.UTF_8);
        return Json.parseObject(text.startsWith("﻿") ? text.substring(1) : text);
    }

    public static boolean v2(Map<String, Object> doc) {
        return doc.get("schemaVersion") instanceof Number n && n.intValue() == 2;
    }

    /** Hash of the methods around every step's evidence, whitespace collapsed: what a flow's facts depend on. */
    static String core(Slices slices, Map<String, Object> doc) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        for (Map<String, Object> s : maps(doc.get("steps"))) {
            if (s.get("evidence") instanceof Map<?, ?> e) {
                files.add(String.valueOf(e.get("file")));
            }
        }
        slices.load(files);
        TreeSet<String> parts = new TreeSet<>();
        for (Map<String, Object> s : maps(doc.get("steps"))) {
            if (s.get("evidence") instanceof Map<?, ?> e && e.get("line") instanceof Number n) {
                Slices.Slice slice = slices.enclosing(String.valueOf(e.get("file")), n.intValue());
                parts.add(e.get("file") + "|" + (slice == null ? "missing" : Slices.normalised(slice)));
            }
        }
        return sha256(String.join("\n", parts).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Hash of the methods around each {@code {file, line}} in order, whitespace collapsed: what a
     * finding depends on. A missing file or a line outside any method hashes as such.
     */
    public static String evidenceCore(Slices slices, List<Map<String, Object>> evidence) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        evidence.forEach(e -> files.add(String.valueOf(e.get("file"))));
        slices.load(files);
        StringBuilder s = new StringBuilder();
        for (Map<String, Object> e : evidence) {
            Slices.Slice slice = e.get("line") instanceof Number n ? slices.enclosing(String.valueOf(e.get("file")), n.intValue()) : null;
            s.append(e.get("file")).append('|').append(slice == null ? "missing" : Slices.normalised(slice)).append('\n');
        }
        return sha256(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * What a step id hashes without its segment: kind, both participants, the evidence file and the
     * method around the evidence line. Matches a step across re-traces that renumbered or re-lettered it.
     */
    public static String stepKey(Slices slices, Map<String, Object> step) throws Exception {
        Map<?, ?> e = step.get("evidence") instanceof Map<?, ?> m ? m : Map.of();
        String file = String.valueOf(e.get("file"));
        slices.load(List.of(file));
        Slices.Slice slice = e.get("line") instanceof Number n ? slices.enclosing(file, n.intValue()) : null;
        return step.get("kind") + "|" + step.get("from") + "|" + step.get("to") + "|" + file + "|" + (slice == null ? "?" : slice.method());
    }

    /** Content hash of every file the flow cites. */
    Map<String, Object> fileHashes(Map<String, Object> doc) {
        Map<String, Object> out = new TreeMap<>();
        for (String f : evidenceFiles(doc)) {
            Path p = repo.resolve(f).normalize();
            try {
                out.put(f, p.startsWith(repo) && Files.isRegularFile(p) ? sha256(Files.readAllBytes(p)) : "missing");
            } catch (IOException e) {
                out.put(f, "unreadable");
            }
        }
        return out;
    }

    static Set<String> evidenceFiles(Map<String, Object> doc) {
        Set<String> out = new TreeSet<>();
        collectFiles(doc, out);
        return out;
    }

    private static void collectFiles(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> m) {
            if (m.get("file") instanceof String f && m.containsKey("snippet")) {
                out.add(f);
            }
            m.values().forEach(v -> collectFiles(v, out));
        } else if (node instanceof List<?> l) {
            l.forEach(v -> collectFiles(v, out));
        }
    }

    /** Whether the code under a recorded v2 flow changed since it was traced. */
    public boolean stale(Slices slices, Map<String, Object> doc) throws Exception {
        Object recorded = doc.get("fingerprint") instanceof Map<?, ?> fp ? fp.get("core") : null;
        return recorded == null || !recorded.equals(core(slices, doc));
    }

    /**
     * The places a flow crosses: one of its entries reaches the place, or one of its steps cites a
     * line inside the place's method. Module places (config, migrations) are kept apart.
     */
    public record Crossing(Places.Place place, List<String> steps, boolean module) {
    }

    public List<Crossing> crossings(Slices slices, Map<String, Object> doc, List<Places.Place> places) throws Exception {
        Set<String> entries = new LinkedHashSet<>();
        for (Map<String, Object> e : maps(doc.get("entries"))) {
            entries.add(String.valueOf(e.get("entrypointId")));
        }
        Set<String> files = new LinkedHashSet<>();
        for (Map<String, Object> s : maps(doc.get("steps"))) {
            if (s.get("evidence") instanceof Map<?, ?> e) {
                files.add(String.valueOf(e.get("file")));
            }
        }
        slices.load(files);
        Map<String, List<String>> stepsBySymbol = new LinkedHashMap<>();
        for (Map<String, Object> s : maps(doc.get("steps"))) {
            if (s.get("evidence") instanceof Map<?, ?> e && e.get("line") instanceof Number n) {
                Slices.Slice slice = slices.enclosing(String.valueOf(e.get("file")), n.intValue());
                if (slice != null) {
                    stepsBySymbol.computeIfAbsent(e.get("file") + "|" + slice.method(), k -> new ArrayList<>()).add(String.valueOf(s.get("id")));
                }
            }
        }
        List<Crossing> out = new ArrayList<>();
        for (Places.Place p : places) {
            String simpleSymbol = p.symbol().contains(".") ? p.symbol().substring(p.symbol().lastIndexOf('.', p.symbol().lastIndexOf('.') - 1) + 1) : p.symbol();
            List<String> steps = stepsBySymbol.getOrDefault(p.file() + "|" + simpleSymbol, List.of());
            boolean reached = p.ops().stream().anyMatch(entries::contains);
            if (reached || !steps.isEmpty()) {
                out.add(new Crossing(p, steps, Risk.MODULE_RULES.contains(p.code())));
            }
        }
        return out;
    }

    /** Rewrites {@code index.json}: one row per recorded flow with its status and the places it crosses. */
    public List<Map<String, Object>> writeIndex() throws Exception {
        List<Places.Place> places = places();
        Slices slices = new Slices(repo);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> f : all().entrySet()) {
            Map<String, Object> doc = f.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getKey());
            row.put("title", doc.getOrDefault("title", f.getKey()));
            row.put("schemaVersion", v2(doc) ? 2 : 1);
            row.put("summary", doc.get("summary"));
            row.put("commit", doc.get("commit"));
            row.put("tracedAt", doc.get("tracedAt"));
            if (v2(doc)) {
                List<String> entries = new ArrayList<>();
                maps(doc.get("entries")).forEach(e -> entries.add(String.valueOf(e.get("entrypointId"))));
                row.put("entries", entries);
                row.put("steps", maps(doc.get("steps")).size());
                row.put("links", maps(doc.get("links")).size());
                row.put("status", stale(slices, doc) ? "STALE" : "CURRENT");
                List<String> crossed = new ArrayList<>();
                Map<String, Object> by = new LinkedHashMap<>();
                for (Crossing c : crossings(slices, doc, places)) {
                    if (!c.module()) {
                        crossed.add(c.place().id());
                        by.put(c.place().id(), c.steps().isEmpty() ? "ENTRY" : "METHOD");
                    }
                }
                row.put("places", crossed);
                row.put("crossedBy", by);
                row.put("page", f.getKey() + ".html");
                deepFields(f.getKey(), row);
            } else {
                row.put("entries", doc.get("entrypoint") instanceof Map<?, ?> ep ? List.of(String.valueOf(ep.get("id"))) : List.of());
                row.put("status", "LEGACY");
                row.put("places", List.of());
            }
            rows.add(row);
        }
        Files.createDirectories(dir);
        StringBuilder out = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            out.append("  ").append(Json.write(rows.get(i))).append(i + 1 < rows.size() ? ",\n" : "\n");
        }
        Files.writeString(dir.resolve("index.json"), out.append("]\n").toString(), StandardCharsets.UTF_8);
        return rows;
    }

    /** The scanner's candidate links between entry points, {@code atlas/links.jsonl}; empty for an atlas built before links. */
    public List<Map<String, Object>> links() throws IOException {
        Path f = atlasDir.resolve("links.jsonl");
        return Files.exists(f) ? AtlasStore.readJsonl(f) : List.of();
    }

    /** The deep audit's scenarios on one flow, {@code audit/scenarios/<id>.jsonl}; empty before one ran. */
    public List<Map<String, Object>> scenarios(String id) throws IOException {
        Path f = auditDir.resolve("scenarios").resolve(id + ".jsonl");
        return Files.exists(f) ? AtlasStore.readJsonl(f) : new ArrayList<>();
    }

    /** {@code {matched, uncovered}}: the failure patterns of the last deep audit of one flow; null before one ran. */
    public Map<String, Object> concepts(String id) throws IOException {
        Path f = auditDir.resolve("scenarios").resolve(id + ".concepts.json");
        return Files.exists(f) ? Json.parseObject(Files.readString(f, StandardCharsets.UTF_8)) : null;
    }

    /** The findings of {@code audit/findings.jsonl} that belong to one flow. */
    public List<Map<String, Object>> findings(String id) throws IOException {
        Path f = auditDir.resolve("findings.jsonl");
        List<Map<String, Object>> out = new ArrayList<>();
        if (Files.exists(f)) {
            for (Map<String, Object> row : AtlasStore.readJsonl(f)) {
                if (id.equals(row.get("flowId"))) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    /**
     * Index fields of the deep audit: scenario counts, the worst live one, the flow's finding ids,
     * whether a design-level change is needed ({@code verdict}) and how far the audit got ({@code tier}).
     */
    private void deepFields(String id, Map<String, Object> row) throws IOException {
        List<Map<String, Object>> scs = scenarios(id);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of("CANDIDATE", "VERIFIED", "DISPROVED", "HARMLESS")) {
            counts.put(k.toLowerCase(Locale.ROOT), scs.stream().filter(s -> k.equals(s.get("status"))).count());
        }
        List<String> severity = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
        List<Map<String, Object>> live = scs.stream().filter(s -> "CANDIDATE".equals(s.get("status")) || "VERIFIED".equals(s.get("status"))).toList();
        Map<String, Object> worst = live.stream().max(Comparator.comparingInt(s -> severity.indexOf(String.valueOf(s.get("severity"))))).orElse(null);
        boolean design = live.stream().anyMatch(s -> severity.indexOf(String.valueOf(s.get("severity"))) >= 2
                && s.get("concept") instanceof Map<?, ?> c && "CONCEPT".equals(c.get("scope")));
        row.put("tier", scs.isEmpty() ? "TRACED" : scs.stream().anyMatch(s -> s.get("verifiedBy") != null) ? "VERIFIED" : "SCENARIOS");
        if (scs.isEmpty()) {
            return;
        }
        row.put("scenarios", counts);
        row.put("verdict", design ? "CONCEPT" : live.isEmpty() ? "NONE" : "STEP");
        Map<String, Object> concepts = concepts(id);
        if (concepts != null) {
            List<Object> uncovered = new ArrayList<>();
            maps(concepts.get("uncovered")).forEach(c -> uncovered.add(c.get("id")));
            row.put("concepts", Map.of("matched", maps(concepts.get("matched")).size(), "uncovered", uncovered));
        }
        if (worst != null) {
            row.put("worst", Map.of("severity", worst.get("severity"), "title", worst.get("title"), "id", worst.get("id")));
        }
        List<Object> ids = new ArrayList<>();
        findings(id).forEach(f -> ids.add(f.get("id")));
        row.put("findingIds", ids);
    }

    public List<Places.Place> places() throws IOException {
        if (!Files.exists(atlasDir.resolve("entrypoints.jsonl"))) {
            return List.of();
        }
        return Places.derive(Places.inputs(AtlasStore.open(repo, atlasDir))).places();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1Short(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                if (x instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }
}
