package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.EvidenceVerifier;
import studio.volkoff.atlas.report.Places;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit record <batch>}: turns a checker's draft into stored rows. Every place of the batch
 * gets exactly one row: the accepted verdict, stamped by the scanner, or an UNJUDGED row with the
 * reason. Never fails for a bad draft, so a batch that was issued is always recorded.
 */
final class Record {

    static final Set<String> VERDICTS = Set.of("CONFIRMED", "REFUTED", "MITIGATED", "UNCERTAIN");
    static final Set<String> CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");
    static final Set<String> RESULTS = Set.of("FOUND", "PARTIAL", "NOT_FOUND", "NOT_CHECKED");
    static final Set<String> NEEDS = Set.of("file", "symbol", "config", "runtime");
    /** What a checker may write in {@code remaining} to say nothing is left, compared lower-case without the final period. */
    static final Set<String> NOTHING_LEFT = Set.of("", "none", "nothing", "no", "-", "n/a");
    /**
     * Fields the scanner stamps; a draft that writes one is rejected, so a model can never set its
     * own tier or freshness. Other unknown fields are dropped.
     */
    static final Set<String> STAMPED = Set.of("tier", "model", "batch", "commit", "core", "opened", "openedFiles", "recordedAt",
            "status", "fingerprint", "draftVerdict", "downgraded", "overBudget");

    private Record() {
    }

    static String record(Path repo, Path atlasDir, AuditStore store, String batchId) throws Exception {
        Map<String, Object> plan = store.plan();
        if (plan == null) {
            throw new IllegalArgumentException("no plan in " + store.dir);
        }
        Map<String, Object> batch = null;
        for (Map<String, Object> b : Plan.maps(plan.get("batches"))) {
            if (batchId.equals(b.get("id"))) {
                batch = b;
            }
        }
        if (batch == null) {
            throw new IllegalArgumentException("batch " + batchId + " is not in plan " + plan.get("id"));
        }
        if ("RECORDED".equals(batch.get("status"))) {
            return "# batch " + batchId + " was already recorded; nothing changed\n";
        }
        if ("PLANNED".equals(batch.get("status"))) {
            throw new IllegalArgumentException("batch " + batchId + " was never issued; audit next issues batches");
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        Map<String, String> plannedCore = new LinkedHashMap<>();
        for (Map<String, Object> pr : Plan.maps(plan.get("places"))) {
            plannedCore.put(String.valueOf(pr.get("id")), String.valueOf(pr.get("core")));
        }

        Path draft = store.draft(batchId);
        Map<String, Map<String, Object>> drafted = new LinkedHashMap<>();
        List<String> draftProblems = new ArrayList<>();
        String whole = null;
        if (!Files.exists(draft) || AuditStore.read(draft).isBlank()) {
            whole = "no-draft";
        } else {
            int lines = 0;
            String[] all = AuditStore.read(draft).split("\\R");
            for (int i = 0; i < all.length; i++) {
                String line = all[i].strip();
                if (line.isEmpty()) {
                    continue;
                }
                lines++;
                try {
                    Map<String, Object> row = Json.parseObject(line);
                    String pid = String.valueOf(row.get("placeId"));
                    if (drafted.containsKey(pid)) {
                        draftProblems.add("line " + (i + 1) + ": a second row for " + pid + " was ignored");
                    } else {
                        drafted.put(pid, row);
                    }
                } catch (IllegalArgumentException e) {
                    draftProblems.add("line " + (i + 1) + ": not a JSON object (" + e.getMessage() + ")");
                }
            }
            if (lines > 0 && drafted.isEmpty()) {
                whole = "unparsable";
            }
        }

        EvidenceVerifier verifier = new EvidenceVerifier(repo);
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String commit = String.valueOf(((Map<?, ?>) plan.get("atlas")).get("commit"));
        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder report = new StringBuilder("placeId\tverdict\tconfidence\tnote\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        Set<String> batchPlaces = new LinkedHashSet<>();
        for (Object pid : (List<?>) batch.get("places")) {
            batchPlaces.add(String.valueOf(pid));
        }
        for (String pid : batchPlaces) {
            Places.Place place = ctx.placeById.get(pid);
            Map<String, Object> d = drafted.get(pid);
            Map<String, Object> row;
            if (place == null) {
                row = unjudged(pid, null, plan, batchId, commit, plannedCore.get(pid), now, "rejected",
                        List.of("the place no longer derives from the atlas"));
            } else {
                Packet.PlaceView view = Packet.view(ctx, place);
                String core = Fingerprint.core(ctx, view);
                if (d == null) {
                    row = unjudged(pid, place, plan, batchId, commit, core, now, whole != null ? whole : "partial", List.of());
                } else {
                    List<String> problems = validate(d, place.code(), ctx.hypotheses.get(place.code()), Packet.ruleCodes(ctx), verifier);
                    if (!core.equals(plannedCore.get(pid))) {
                        problems.add("CHANGED_SINCE_PACKET: the code behind the place changed after the packet was written");
                    }
                    row = problems.isEmpty() ? accepted(d, place, view, plan, batchId, commit, core, now, repo)
                            : unjudged(pid, place, plan, batchId, commit, core, now, "rejected", problems);
                }
            }
            rows.add(row);
            String verdict = "UNJUDGED".equals(row.get("status")) ? "UNJUDGED" : String.valueOf(row.get("verdict"));
            counts.merge(verdict, 1, Integer::sum);
            report.append(pid).append('\t').append(verdict).append('\t').append(row.getOrDefault("confidence", "-")).append('\t')
                    .append(note(row)).append('\n');
        }
        for (String pid : drafted.keySet()) {
            if (!batchPlaces.contains(pid)) {
                draftProblems.add("a row for " + pid + ", which is not in this batch, was ignored");
            }
        }
        store.writeRows(batchId, rows);
        batch.put("status", "RECORDED");
        batch.put("recordedAt", now);
        store.writePlan(plan);
        Derive.run(ctx, store);

        StringBuilder out = new StringBuilder();
        int judged = rows.size() - counts.getOrDefault("UNJUDGED", 0);
        out.append("# recorded ").append(batchId).append(": ").append(judged).append(" judged");
        List<String> parts = new ArrayList<>();
        for (String v : List.of("CONFIRMED", "MITIGATED", "REFUTED", "UNCERTAIN")) {
            if (counts.containsKey(v)) {
                parts.add(v.toLowerCase(Locale.ROOT) + " " + counts.get(v));
            }
        }
        if (!parts.isEmpty()) {
            out.append(" (").append(String.join(", ", parts)).append(")");
        }
        out.append(", ").append(counts.getOrDefault("UNJUDGED", 0)).append(" unjudged\n");
        for (String p : draftProblems) {
            out.append("# draft: ").append(p).append('\n');
        }
        return out.append(report).toString();
    }

    /** Problems with one drafted row; empty when the row can be stored. */
    static List<String> validate(Map<String, Object> d, String code, Hypotheses.Hypothesis h, List<String> ruleCodes,
                                 EvidenceVerifier verifier) throws Exception {
        List<String> problems = new ArrayList<>();
        for (String f : STAMPED) {
            if (d.containsKey(f)) {
                problems.add("the draft sets " + f + ", which only the scanner writes");
            }
        }
        String verdict = String.valueOf(d.get("verdict"));
        if (!VERDICTS.contains(verdict)) {
            problems.add("verdict " + d.get("verdict") + " is not CONFIRMED, REFUTED, MITIGATED or UNCERTAIN");
        }
        if (!CONFIDENCES.contains(String.valueOf(d.get("confidence")))) {
            problems.add("confidence " + d.get("confidence") + " is not HIGH, MEDIUM or LOW");
        }
        if (!(d.get("summary") instanceof String s) || s.isBlank()) {
            problems.add("summary is missing");
        }
        List<?> evidence = d.get("evidence") instanceof List<?> l ? l : List.of();
        if (!verdict.equals("UNCERTAIN") && evidence.isEmpty()) {
            problems.add(verdict + " needs at least one evidence object");
        }
        for (int i = 0; i < evidence.size(); i++) {
            if (!(evidence.get(i) instanceof Map<?, ?> e)) {
                problems.add("evidence[" + i + "] is not an object");
                continue;
            }
            EvidenceVerifier.Check c = verifier.check(e);
            if (c != EvidenceVerifier.Check.OK) {
                problems.add("evidence[" + i + "] " + c + " " + e.get("file") + ":" + e.get("line"));
            }
        }
        if (verdict.equals("UNCERTAIN")) {
            if (!(d.get("needs") instanceof Map<?, ?> n) || !NEEDS.contains(String.valueOf(n.get("kind")))
                    || !(n.get("what") instanceof String w) || w.isBlank()) {
                problems.add("UNCERTAIN needs \"needs\": {kind: file|symbol|config|runtime, what, wouldOpen}");
            }
        }
        if ((verdict.equals("REFUTED") || verdict.equals("MITIGATED")) && !(d.get("remaining") instanceof String)) {
            problems.add(verdict + " needs \"remaining\": \"none\" or how the failure still happens");
        }
        if (code.equals(Places.NO_HINT) && verdict.equals("CONFIRMED") && !ruleCodes.contains(String.valueOf(d.get("foundRule")))) {
            problems.add("a CONFIRMED no-hint place needs \"foundRule\", one of " + String.join(", ", ruleCodes) + "; got " + d.get("foundRule"));
        }
        Map<String, String> checked = new LinkedHashMap<>();
        if (d.get("mitigationsChecked") instanceof List<?> mc) {
            for (Object o : mc) {
                if (o instanceof Map<?, ?> m) {
                    String result = String.valueOf(m.get("result"));
                    if (!RESULTS.contains(result)) {
                        problems.add("mitigation " + m.get("name") + ": result " + result + " is not FOUND, PARTIAL, NOT_FOUND or NOT_CHECKED");
                    }
                    checked.put(normal(String.valueOf(m.get("name"))), result);
                }
            }
        }
        for (Hypotheses.Mitigation m : h.mitigations()) {
            if (!checked.containsKey(normal(m.name()))) {
                problems.add("mitigationsChecked lacks \"" + m.name() + "\"");
            }
        }
        if (d.containsKey("filesOpened") && !(d.get("filesOpened") instanceof List<?>)) {
            problems.add("filesOpened is not a list");
        }
        return problems;
    }

    private static Map<String, Object> accepted(Map<String, Object> d, Places.Place place, Packet.PlaceView view, Map<String, Object> plan,
                                                String batchId, String commit, String core, String now, Path repo) {
        Map<String, Object> row = base(place.id(), place, plan, batchId, commit, core, now);
        String verdict = String.valueOf(d.get("verdict"));
        String remaining = remaining(d);
        boolean fullMitigation = d.get("mitigationsChecked") instanceof List<?> found
                && found.stream().anyMatch(o -> o instanceof Map<?, ?> m && "FOUND".equals(m.get("result")));
        String downgraded = null;
        if (verdict.equals("MITIGATED") && (remaining != null || !fullMitigation)) {
            downgraded = remaining != null ? "the check names a failure that remains: " + remaining : "no listed mitigation was found in full";
            verdict = "CONFIRMED";
        } else if (verdict.equals("REFUTED") && remaining != null) {
            downgraded = "the check names a failure it could not rule out: " + remaining;
            verdict = "UNCERTAIN";
        }
        row.put("verdict", verdict);
        if (downgraded != null) {
            row.put("draftVerdict", d.get("verdict"));
            row.put("downgraded", downgraded);
        }
        if (remaining != null) {
            row.put("remaining", remaining);
        }
        if (place.code().equals(Places.NO_HINT) && d.get("foundRule") != null && verdict.equals("CONFIRMED")) {
            row.put("foundRule", d.get("foundRule"));
        }
        String confidence = String.valueOf(d.get("confidence"));
        boolean notChecked = d.get("mitigationsChecked") instanceof List<?> mc
                && mc.stream().anyMatch(o -> o instanceof Map<?, ?> m && "NOT_CHECKED".equals(m.get("result")));
        if (notChecked && confidence.equals("HIGH")) {
            confidence = "MEDIUM";
        }
        row.put("confidence", confidence);
        row.put("summary", d.get("summary"));
        row.put("evidence", d.get("evidence"));
        row.put("mitigationsChecked", d.get("mitigationsChecked"));
        if (d.get("needs") != null) {
            row.put("needs", d.get("needs"));
        }
        List<String> opened = new ArrayList<>();
        List<String> outside = new ArrayList<>();
        Set<String> packetFiles = view.files();
        String root = repo.toString().replace('\\', '/') + "/";
        if (d.get("filesOpened") instanceof List<?> l) {
            for (Object o : l) {
                String f = String.valueOf(o).replace('\\', '/');
                if (f.regionMatches(true, 0, root, 0, root.length())) {
                    f = f.substring(root.length());
                }
                if (f.startsWith(".reliability/") || f.contains("/.reliability/") || opened.contains(f)) {
                    continue;
                }
                opened.add(f);
                if (!packetFiles.contains(f)) {
                    outside.add(f);
                }
            }
        }
        row.put("filesOpened", opened);
        row.put("openedFiles", outside);
        row.put("opened", Fingerprint.opened(repo, outside));
        if (opened.size() > Packet.EXTRA_FILES_PER_PLACE) {
            row.put("overBudget", opened.size());
        }
        row.put("status", "CURRENT");
        return row;
    }

    static Map<String, Object> unjudged(String pid, Places.Place place, Map<String, Object> plan, String batchId, String commit,
                                        String core, String now, String reason, List<String> problems) {
        Map<String, Object> row = base(pid, place, plan, batchId, commit, core, now);
        row.put("status", "UNJUDGED");
        row.put("reason", reason);
        if (!problems.isEmpty()) {
            row.put("problems", problems);
        }
        return row;
    }

    private static Map<String, Object> base(String pid, Places.Place place, Map<String, Object> plan, String batchId, String commit,
                                            String core, String now) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("placeId", pid);
        if (place != null) {
            row.put("rule", place.code());
            row.put("level", place.level().name());
            row.put("file", place.file());
            row.put("symbol", place.symbol());
            row.put("operationIds", place.ops());
        }
        row.put("tier", plan.getOrDefault("tier", "TRIAGE"));
        row.put("model", plan.get("modelId"));
        row.put("batch", batchId);
        row.put("commit", commit);
        row.put("core", core);
        row.put("recordedAt", now);
        return row;
    }

    private static String note(Map<String, Object> row) {
        if ("UNJUDGED".equals(row.get("status"))) {
            Object problems = row.get("problems");
            return row.get("reason") + (problems instanceof List<?> l && !l.isEmpty() ? ": " + String.join("; ", l.stream().map(String::valueOf).toList()) : "");
        }
        List<String> notes = new ArrayList<>();
        if (row.get("downgraded") != null) {
            notes.add(row.get("draftVerdict") + " recorded as " + row.get("verdict") + ": " + row.get("downgraded"));
        }
        if (row.get("overBudget") != null) {
            notes.add("opened " + row.get("overBudget") + " files (budget " + Packet.EXTRA_FILES_PER_PLACE + ")");
        }
        return String.join("; ", notes);
    }

    private static String normal(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }

    /** The draft's {@code remaining}, or null when it says nothing is left. */
    static String remaining(Map<String, Object> d) {
        if (!(d.get("remaining") instanceof String s)) {
            return null;
        }
        String t = s.strip();
        return NOTHING_LEFT.contains(normal(t).replaceAll("[.!]+$", "")) ? null : t;
    }

    /**
     * Writes UNJUDGED rows ({@code not-recorded}) for every batch the previous plan issued but
     * nobody recorded, so their spend and their places stay in the ledger.
     *
     * @return the number of batches closed
     */
    static int closeUnrecorded(AuditContext ctx, AuditStore store, Map<String, Object> plan) throws Exception {
        if (plan == null) {
            return 0;
        }
        Map<String, String> plannedCore = new LinkedHashMap<>();
        for (Map<String, Object> pr : Plan.maps(plan.get("places"))) {
            plannedCore.put(String.valueOf(pr.get("id")), String.valueOf(pr.get("core")));
        }
        String commit = String.valueOf(((Map<?, ?>) plan.get("atlas")).get("commit"));
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        int closed = 0;
        for (Map<String, Object> b : Plan.maps(plan.get("batches"))) {
            String batchId = String.valueOf(b.get("id"));
            if (!"ISSUED".equals(b.get("status")) || Files.exists(store.batchRows(batchId))) {
                continue;
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Object pid : (List<?>) b.get("places")) {
                String id = String.valueOf(pid);
                rows.add(unjudged(id, ctx.placeById.get(id), plan, batchId, commit, plannedCore.get(id), now, "not-recorded", List.of()));
            }
            store.writeRows(batchId, rows);
            b.put("status", "RECORDED");
            b.put("recordedAt", now);
            closed++;
        }
        if (closed > 0) {
            store.writePlan(plan);
        }
        return closed;
    }
}
