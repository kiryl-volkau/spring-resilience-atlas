package studio.volkoff.atlas.flow;

import studio.volkoff.atlas.audit.Slices;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code .reliability/history/}: snapshots of the flows and the deep audit's findings, and the
 * comparison of two of them. A snapshot is an index of ids, statuses and code fingerprints, not a
 * copy of the documents, so it stays small on a large application.
 */
public final class History {

    /** {@code <yyyyMMddTHHmmssZ>-<commit7>.json}: sortable by time. */
    static final Pattern NAME = Pattern.compile("\\d{8}T\\d{6}Z-[0-9a-z?]{1,7}\\.json");
    public static final int DEFAULT_KEEP = 20;
    private static final Set<String> OPEN = Set.of("CANDIDATE", "VERIFIED");

    private History() {
    }

    public static Path dir(Flows flows) {
        return flows.dir.toAbsolutePath().getParent().resolve("history");
    }

    /** The snapshots, oldest first. */
    public static List<Path> all(Flows flows) throws IOException {
        Path dir = dir(flows);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> NAME.matcher(p.getFileName().toString()).matches()).sorted().toList();
        }
    }

    /**
     * The flows, scenarios, deep findings and place verdicts as they stand, with each finding's code
     * fingerprint recomputed against the working tree ({@code liveCore}) next to the one it was
     * recorded with ({@code core}).
     */
    public static Map<String, Object> state(Flows flows, String reason) throws Exception {
        Slices slices = new Slices(flows.repo);
        Map<String, Object> manifest = Files.exists(flows.atlasDir.resolve("manifest.json"))
                ? Json.parseObject(Files.readString(flows.atlasDir.resolve("manifest.json"), StandardCharsets.UTF_8)) : Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("takenAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        out.put("commit", manifest.get("commit"));
        out.put("sourcesHash", manifest.get("sourcesHash"));
        out.put("reason", reason);
        List<Map<String, Object>> flowRows = new ArrayList<>();
        List<Map<String, Object>> scenarioRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> f : flows.all().entrySet()) {
            Map<String, Object> doc = f.getValue();
            if (!Flows.v2(doc)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getKey());
            row.put("core", doc.get("fingerprint") instanceof Map<?, ?> fp ? fp.get("core") : null);
            row.put("tracedAt", doc.get("tracedAt"));
            row.put("status", flows.stale(slices, doc) ? "STALE" : "CURRENT");
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Map<String, Object> s : Flows.maps(doc.get("steps"))) {
                steps.add(pick(s, "id", "kind", "from", "to"));
            }
            row.put("steps", steps);
            flowRows.add(row);
            for (Map<String, Object> sc : flows.scenarios(f.getKey())) {
                scenarioRows.add(pick(sc, "id", "flowId", "status", "brokenAt", "recordedAt", "notRejudged"));
            }
        }
        out.put("flows", flowRows);
        out.put("scenarios", scenarioRows);
        List<Map<String, Object>> findingRows = new ArrayList<>();
        Path findings = flows.auditDir.resolve("findings.jsonl");
        if (Files.exists(findings)) {
            for (Map<String, Object> f : AtlasStore.readJsonl(findings)) {
                if (f.get("scenarioIds") == null) {
                    continue;
                }
                Map<String, Object> row = pick(f, "id", "dedupeKey", "ruleId", "title", "flowId", "brokenAt", "brokenAtKey", "status",
                        "severity", "confidence", "recordedAt", "notRejudged", "core");
                List<Map<String, Object>> cited = new ArrayList<>();
                for (Map<String, Object> e : Flows.maps(f.get("evidence"))) {
                    cited.add(pick(e, "file", "line"));
                }
                if (f.get("brokenAtEvidence") instanceof Map<?, ?> at) {
                    cited.add(pick(Flows.maps(List.of(at)).get(0), "file", "line"));
                }
                row.put("liveCore", Flows.evidenceCore(slices, cited));
                findingRows.add(row);
            }
        }
        out.put("findings", findingRows);
        List<Map<String, Object>> placeRows = new ArrayList<>();
        Path places = flows.auditDir.resolve("places.jsonl");
        if (Files.exists(places)) {
            for (Map<String, Object> p : AtlasStore.readJsonl(places)) {
                placeRows.add(pick(p, "placeId", "verdict", "tier", "status"));
            }
        }
        out.put("places", placeRows);
        return out;
    }

    /** Writes a snapshot of the current state and keeps the newest {@code keep}; returns its path. */
    public static Path snapshot(Flows flows, String reason, int keep) throws Exception {
        Map<String, Object> state = state(flows, reason);
        String commit = state.get("commit") == null ? "?" : String.valueOf(state.get("commit"));
        String name = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")) + "-"
                + commit.substring(0, Math.min(7, commit.length())) + ".json";
        Path out = dir(flows).resolve(name);
        Files.createDirectories(out.getParent());
        Files.writeString(out, Json.pretty(state) + "\n", StandardCharsets.UTF_8);
        List<Path> all = all(flows);
        for (int i = 0; i < all.size() - keep; i++) {
            Files.deleteIfExists(all.get(i));
        }
        return out;
    }

    /** A snapshot by file name in the history directory, by path, or {@code live} for the current state. */
    public static Map<String, Object> load(Flows flows, String ref) throws Exception {
        if (ref.equals("live")) {
            return state(flows, "live");
        }
        Path p = dir(flows).resolve(ref);
        if (!Files.exists(p)) {
            p = Path.of(ref).isAbsolute() ? Path.of(ref) : flows.repo.resolve(ref);
        }
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("no snapshot " + ref + " in " + dir(flows) + "; flow snapshot takes one");
        }
        Map<String, Object> s = Json.parseObject(Files.readString(p, StandardCharsets.UTF_8));
        s.put("name", p.getFileName().toString());
        return s;
    }

    /**
     * Compares the deep findings of two states, {@code a} the older. Per finding of {@code a} that
     * was open (CANDIDATE or VERIFIED), matched in {@code b} by its dedupe key, else by rule, flow
     * and the broken step's key:
     * <ul>
     *   <li><b>disproved</b>: DISPROVED in {@code b};</li>
     *   <li><b>notRechecked</b>: in {@code b} but the code it cites changed since it was recorded,
     *       or absent from {@code b} without a later deep run on its flow;</li>
     *   <li><b>stillOpen</b>: in {@code b} with its code unchanged;</li>
     *   <li><b>fixed</b>: absent from {@code b}, and {@code b} has scenarios on its flow recorded
     *       after it. A run keeps every finding it did not judge again while its code is unchanged,
     *       so absence after a later run means the code changed and the story no longer holds.</li>
     * </ul>
     * Open findings of {@code b} matched by nothing in {@code a} are <b>new</b>.
     */
    public static Map<String, Object> diff(Map<String, Object> a, Map<String, Object> b) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        for (String k : List.of("fixed", "new", "stillOpen", "notRechecked", "disproved")) {
            buckets.put(k, new ArrayList<>());
        }
        List<Map<String, Object>> bFindings = Flows.maps(b.get("findings"));
        List<Map<String, Object>> matchedInB = new ArrayList<>();
        for (Map<String, Object> f : Flows.maps(a.get("findings"))) {
            if (!OPEN.contains(String.valueOf(f.get("status")))) {
                continue;
            }
            Map<String, Object> m = match(f, bFindings);
            String note = null;
            String bucket;
            if (m != null) {
                matchedInB.add(m);
                if (!m.get("id").equals(f.get("id")) || m.get("brokenAt") != null && !m.get("brokenAt").equals(f.get("brokenAt"))) {
                    note = "step renumbered";
                }
                if ("DISPROVED".equals(m.get("status"))) {
                    bucket = "disproved";
                } else if (m.get("core") != null && !m.get("core").equals(m.get("liveCore"))) {
                    bucket = "notRechecked";
                    note = "its code changed; not audited again";
                } else {
                    bucket = "stillOpen";
                }
            } else if (rerunAfter(b, String.valueOf(f.get("flowId")), String.valueOf(f.get("recordedAt")))) {
                bucket = "fixed";
            } else {
                bucket = "notRechecked";
                note = "no longer recorded, and its flow was not audited again";
            }
            buckets.get(bucket).add(row(f, note));
        }
        for (Map<String, Object> f : bFindings) {
            if (OPEN.contains(String.valueOf(f.get("status"))) && matchedInB.stream().noneMatch(x -> x == f)) {
                buckets.get("new").add(row(f, null));
            }
        }
        Map<String, Object> flowsA = byId(a.get("flows"));
        Map<String, Object> flowsB = byId(b.get("flows"));
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> retraced = new ArrayList<>();
        flowsB.forEach((id, f) -> {
            if (!flowsA.containsKey(id)) {
                added.add(id);
            } else if (!String.valueOf(((Map<?, ?>) f).get("core")).equals(String.valueOf(((Map<?, ?>) flowsA.get(id)).get("core")))) {
                retraced.add(id);
            }
        });
        flowsA.keySet().stream().filter(id -> !flowsB.containsKey(id)).forEach(removed::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", side(a));
        out.put("to", side(b));
        Map<String, Object> counts = new LinkedHashMap<>();
        buckets.forEach((k, v) -> counts.put(k, v.size()));
        out.put("counts", counts);
        out.put("findings", buckets);
        out.put("flows", Map.of("added", added, "removed", removed, "retraced", retraced));
        return out;
    }

    /** The diff as text: one summary line, the flow changes, then one line per finding. */
    public static String render(Map<String, Object> d) {
        Map<?, ?> from = (Map<?, ?>) d.get("from");
        Map<?, ?> to = (Map<?, ?>) d.get("to");
        StringBuilder out = new StringBuilder("# since ").append(label(from)).append(" → ").append(label(to)).append(": ").append(summary(d)).append('\n');
        Map<?, ?> fl = (Map<?, ?>) d.get("flows");
        for (String k : List.of("added", "removed", "retraced")) {
            if (fl.get(k) instanceof List<?> l && !l.isEmpty()) {
                out.append("flows ").append(k).append(": ").append(String.join(", ", l.stream().map(String::valueOf).toList())).append('\n');
            }
        }
        Map<?, ?> buckets = (Map<?, ?>) d.get("findings");
        Map<String, String> tags = Map.of("fixed", "FIXED", "new", "NEW", "stillOpen", "OPEN", "notRechecked", "CHANGED", "disproved", "DISPROVED");
        for (String k : List.of("fixed", "new", "notRechecked", "disproved", "stillOpen")) {
            for (Map<String, Object> f : Flows.maps(buckets.get(k))) {
                out.append(tags.get(k)).append('\t').append(f.get("id")).append('\t').append(f.get("severity")).append('\t').append(f.get("flowId"))
                        .append('\t').append(f.get("title")).append(f.get("note") != null ? "  (" + f.get("note") + ")" : "").append('\n');
            }
        }
        return out.toString();
    }

    /** "1 finding fixed, 0 new, 2 still open, 1 changed and not audited again, 0 disproved". */
    public static String summary(Map<String, Object> d) {
        Map<?, ?> c = (Map<?, ?>) d.get("counts");
        int fixed = ((Number) c.get("fixed")).intValue();
        return fixed + (fixed == 1 ? " finding" : " findings") + " fixed, " + c.get("new") + " new, " + c.get("stillOpen") + " still open, "
                + c.get("notRechecked") + " changed and not audited again, " + c.get("disproved") + " disproved";
    }

    /**
     * The comparison the report and the backlog show: the newest snapshot taken at another commit
     * than the live one (else the newest) against the live state; null without a snapshot.
     */
    public static Map<String, Object> since(Flows flows) throws Exception {
        List<Path> all = all(flows);
        if (all.isEmpty()) {
            return null;
        }
        Map<String, Object> live = state(flows, "live");
        Path base = all.get(all.size() - 1);
        for (int i = all.size() - 1; i >= 0; i--) {
            Map<String, Object> s = Json.parseObject(Files.readString(all.get(i), StandardCharsets.UTF_8));
            if (!String.valueOf(s.get("commit")).equals(String.valueOf(live.get("commit")))) {
                base = all.get(i);
                break;
            }
        }
        return diff(load(flows, base.getFileName().toString()), live);
    }

    private static Map<String, Object> match(Map<String, Object> f, List<Map<String, Object>> in) {
        for (Map<String, Object> x : in) {
            if (x.get("dedupeKey") != null && x.get("dedupeKey").equals(f.get("dedupeKey"))) {
                return x;
            }
        }
        for (Map<String, Object> x : in) {
            if (f.get("brokenAtKey") != null && f.get("brokenAtKey").equals(x.get("brokenAtKey")) && f.get("ruleId").equals(x.get("ruleId"))
                    && f.get("flowId").equals(x.get("flowId"))) {
                return x;
            }
        }
        return null;
    }

    private static boolean rerunAfter(Map<String, Object> state, String flowId, String recordedAt) {
        for (Map<String, Object> sc : Flows.maps(state.get("scenarios"))) {
            if (flowId.equals(sc.get("flowId")) && sc.get("notRejudged") == null && String.valueOf(sc.get("recordedAt")).compareTo(recordedAt) > 0) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> row(Map<String, Object> f, String note) {
        Map<String, Object> r = pick(f, "id", "ruleId", "severity", "flowId", "title", "brokenAt");
        if (note != null) {
            r.put("note", note);
        }
        return r;
    }

    private static Map<String, Object> side(Map<String, Object> s) {
        Map<String, Object> out = pick(s, "name", "takenAt", "commit", "reason");
        if (out.get("name") == null) {
            out.put("name", "live");
        }
        return out;
    }

    private static String label(Map<?, ?> side) {
        if ("live".equals(side.get("name"))) {
            return "live";
        }
        String commit = String.valueOf(side.get("commit"));
        return String.valueOf(side.get("takenAt")).replace("T", " ").replace("Z", " UTC") + " (" + commit.substring(0, Math.min(7, commit.length())) + ")";
    }

    private static Map<String, Object> byId(Object rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        Flows.maps(rows).forEach(r -> out.put(String.valueOf(r.get("id")), r));
        return out;
    }

    private static Map<String, Object> pick(Map<String, Object> m, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            if (m.get(k) != null) {
                out.put(k, m.get(k));
            }
        }
        return out;
    }
}
