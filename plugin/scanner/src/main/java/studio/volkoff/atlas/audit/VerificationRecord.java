package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.EvidenceVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit record-step <flow>:VERIFY}: applies the verifier's outcomes to the scenarios it was
 * given, their findings and the deep place rows. The verifier can confirm or knock down, never
 * raise: a DISPROVED without evidence that verifies counts as DOUBT, and DOUBT lowers confidence.
 * BLOCKED confirms the mechanism while another defect stops the flow earlier: the scenario counts
 * as verified and carries {@code blockedBy}, so the page and the backlog say it bites once that is
 * fixed.
 */
final class VerificationRecord {

    static final Set<String> OUTCOMES = Set.of("VERIFIED", "DISPROVED", "DOUBT", "BLOCKED");
    private static final List<String> CONFIDENCE = List.of("LOW", "MEDIUM", "HIGH");

    private VerificationRecord() {
    }

    static Deep.Outcome record(AuditContext ctx, AuditStore store, Flows flows, Map<String, Object> plan, Map<String, Object> job,
                               Map<String, Object> step, String batch) throws Exception {
        String flowId = String.valueOf(job.get("flowId"));
        Path draft = Deep.draft(store, flows, flowId, "VERIFY");
        if (!Files.exists(draft) || AuditStore.read(draft).isBlank()) {
            return new Deep.Outcome(false, "no-draft", "# the verifier wrote no draft at " + Plan.shown(draft) + "\n");
        }
        Map<String, Map<String, Object>> asked = new LinkedHashMap<>();
        for (Map<String, Object> sc : DeepPacket.toVerify(store, flowId)) {
            asked.put(String.valueOf(sc.get("id")), sc);
        }
        List<Map<String, Object>> scenarios = ScenarioRecord.stored(store, flowId);
        EvidenceVerifier verifier = new EvidenceVerifier(ctx.repo);
        String model = String.valueOf(step.get("modelId"));
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Map<String, Object>> verdicts = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        String[] all = AuditStore.read(draft).split("\\R");
        for (int i = 0; i < all.length; i++) {
            String line = all[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            String at = "line " + (i + 1);
            Map<String, Object> d;
            try {
                d = Json.parseObject(line);
            } catch (IllegalArgumentException e) {
                problems.add(at + "\t-\tnot a JSON object (" + e.getMessage() + ")");
                continue;
            }
            String id = String.valueOf(d.get("scenarioId"));
            ScenarioRecord.Check c = new ScenarioRecord.Check();
            if (!asked.containsKey(id)) {
                c.problem("scenarioId", id + " is not one of the scenarios of the packet");
            } else if (verdicts.containsKey(id)) {
                c.problem("scenarioId", "a second row for " + id + " was ignored");
            }
            c.oneOf("outcome", d.get("outcome"), OUTCOMES);
            c.limit("note", d.get("note"), 160, true);
            if (!c.problems.isEmpty()) {
                c.problems.forEach(p -> problems.add(at + "\t" + p));
                continue;
            }
            List<Object> good = new ArrayList<>();
            List<?> evidence = d.get("evidence") instanceof List<?> l ? l : List.of();
            for (int k = 0; k < evidence.size(); k++) {
                if (evidence.get(k) instanceof Map<?, ?> e && verifier.check(e) == EvidenceVerifier.Check.OK) {
                    good.add(e);
                } else {
                    problems.add(at + "\tevidence[" + k + "]\tdoes not verify and was dropped");
                }
            }
            String outcome = String.valueOf(d.get("outcome"));
            String note = String.valueOf(d.get("note"));
            if (outcome.equals("DISPROVED") && good.isEmpty()) {
                outcome = "DOUBT";
                problems.add(at + "\toutcome\tDISPROVED without evidence that verifies is recorded as DOUBT");
            }
            Map<String, Object> blockedBy = null;
            if (outcome.equals("BLOCKED")) {
                Map<?, ?> b = d.get("blockedBy") instanceof Map<?, ?> m ? m : Map.of();
                List<Object> proof = new ArrayList<>();
                for (Object e : b.get("evidence") instanceof List<?> l ? l : List.of()) {
                    if (e instanceof Map<?, ?> m && verifier.check(m) == EvidenceVerifier.Check.OK) {
                        proof.add(m);
                    }
                }
                if (!(b.get("what") instanceof String what) || what.isBlank() || proof.isEmpty()) {
                    outcome = "DOUBT";
                    problems.add(at + "\tblockedBy\tBLOCKED needs what stops the flow and evidence that verifies; recorded as DOUBT");
                } else {
                    blockedBy = new LinkedHashMap<>();
                    blockedBy.put("what", what.length() > 160 ? what.substring(0, 159) + "…" : what);
                    blockedBy.put("evidence", proof);
                }
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("model", model);
            v.put("run", batch);
            v.put("outcome", outcome);
            v.put("note", note);
            v.put("evidence", good);
            v.put("filesOpened", d.get("filesOpened") instanceof List<?> f ? f : List.of());
            v.put("verifiedAt", now);
            if (blockedBy != null) {
                v.put("blockedBy", blockedBy);
            }
            verdicts.put(id, v);
        }
        uniformDisproof(verdicts, asked.size(), problems);
        StringBuilder out = new StringBuilder();
        if (verdicts.isEmpty()) {
            out.append("# no verification accepted for ").append(flowId).append('\n');
            problems.forEach(p -> out.append("REJECTED\t").append(p).append('\n'));
            return new Deep.Outcome(false, "rejected", out.toString());
        }

        for (Map<String, Object> sc : scenarios) {
            Map<String, Object> v = verdicts.get(String.valueOf(sc.get("id")));
            if (v == null) {
                continue;
            }
            sc.put("verifiedBy", v);
            switch (String.valueOf(v.get("outcome"))) {
                case "VERIFIED" -> sc.put("status", "VERIFIED");
                case "BLOCKED" -> {
                    sc.put("status", "VERIFIED");
                    sc.put("blockedBy", v.get("blockedBy"));
                }
                case "DISPROVED" -> sc.put("status", "DISPROVED");
                default -> sc.put("confidence", lower(sc.get("confidence")));
            }
            rows.append(sc.get("id")).append('\t').append(v.get("outcome")).append('\t').append(sc.get("title")).append(" — ").append(v.get("note")).append('\n');
        }
        ScenarioRecord.store(store, flowId, scenarios);

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        scenarios.forEach(sc -> byId.put(String.valueOf(sc.get("id")), sc));
        List<Map<String, Object>> findings = DeepPacket.findings(store);
        Map<String, Map<String, Object>> findingById = new LinkedHashMap<>();
        for (Map<String, Object> f : findings) {
            findingById.put(String.valueOf(f.get("id")), f);
            if (!flowId.equals(f.get("flowId")) || !(f.get("scenarioIds") instanceof List<?> ids)) {
                continue;
            }
            List<Map<String, Object>> mine = new ArrayList<>();
            ids.forEach(x -> {
                if (byId.containsKey(String.valueOf(x))) {
                    mine.add(byId.get(String.valueOf(x)));
                }
            });
            Map<String, Object> verified = mine.stream().filter(s -> "VERIFIED".equals(s.get("status"))).findFirst().orElse(null);
            boolean allDisproved = !mine.isEmpty() && mine.stream().allMatch(s -> "DISPROVED".equals(s.get("status")));
            Map<String, Object> doubt = mine.stream().filter(s -> s.get("verifiedBy") instanceof Map<?, ?> v && "DOUBT".equals(v.get("outcome")))
                    .findFirst().orElse(null);
            if (verified != null) {
                f.put("status", "VERIFIED");
                f.put("verifiedBy", verified.get("verifiedBy"));
                if (verified.get("blockedBy") != null) {
                    f.put("blockedBy", verified.get("blockedBy"));
                }
            } else if (allDisproved) {
                f.put("status", "DISPROVED");
                f.put("verifiedBy", mine.get(0).get("verifiedBy"));
            } else if (doubt != null) {
                f.put("confidence", lower(f.get("confidence")));
                f.put("verifiedBy", doubt.get("verifiedBy"));
            }
        }
        store.writeJsonl("findings.jsonl", findings);

        String jobBatch = String.valueOf(job.get("batch"));
        Path placeFile = store.batchRows(jobBatch);
        int verifiedPlaces = 0;
        if (Files.exists(placeFile)) {
            List<Map<String, Object>> placeRows = AtlasStore.readJsonl(placeFile);
            for (Map<String, Object> row : placeRows) {
                List<Map<String, Object>> fs = new ArrayList<>();
                if (row.get("findingIds") instanceof List<?> ids) {
                    ids.forEach(x -> {
                        if (findingById.containsKey(String.valueOf(x))) {
                            fs.add(findingById.get(String.valueOf(x)));
                        }
                    });
                }
                if (fs.isEmpty() || !fs.stream().allMatch(f -> "VERIFIED".equals(f.get("status")) || "DISPROVED".equals(f.get("status")))) {
                    continue;
                }
                row.put("tier", "VERIFIED");
                row.put("verifiedAt", now);
                verifiedPlaces++;
                if (fs.stream().allMatch(f -> "DISPROVED".equals(f.get("status")))) {
                    row.put("verdict", "REFUTED");
                    row.put("summary", "disproved: " + ((Map<?, ?>) fs.get(0).get("verifiedBy")).get("note"));
                }
            }
            store.writeRows(jobBatch, placeRows);
        }
        long blocked = verdicts.values().stream().filter(v -> "BLOCKED".equals(v.get("outcome"))).count();
        long verified = blocked + verdicts.values().stream().filter(v -> "VERIFIED".equals(v.get("outcome"))).count();
        long disproved = verdicts.values().stream().filter(v -> "DISPROVED".equals(v.get("outcome"))).count();
        long missing = asked.keySet().stream().filter(id -> !verdicts.containsKey(id)).count();
        step.put("verified", verified);
        step.put("disproved", disproved);
        out.append("# verification recorded for ").append(flowId).append(": ").append(verified).append(" verified")
                .append(blocked > 0 ? " (" + blocked + " blocked for now by another defect)" : "").append(", ").append(disproved)
                .append(" disproved, ").append(verdicts.size() - verified - disproved).append(" doubt")
                .append(missing > 0 ? ", " + missing + " not answered" : "").append(" · ").append(verifiedPlaces)
                .append(verifiedPlaces == 1 ? " place" : " places").append(" verified\n");
        out.append(rows);
        problems.forEach(p -> out.append("REJECTED\t").append(p).append('\n'));
        return new Deep.Outcome(true, null, out.toString());
    }

    /**
     * A verifier that disproves every scenario it was given with the same files has most likely
     * found one defect that stops the flow early, which is BLOCKED, not a disproof of each story:
     * those outcomes are recorded as DOUBT.
     */
    private static void uniformDisproof(Map<String, Map<String, Object>> verdicts, int asked, List<String> problems) {
        List<Map<String, Object>> disproved = verdicts.values().stream().filter(v -> "DISPROVED".equals(v.get("outcome"))).toList();
        if (asked < 2 || disproved.size() < asked) {
            return;
        }
        java.util.Set<java.util.Set<String>> fileSets = new java.util.HashSet<>();
        for (Map<String, Object> v : disproved) {
            java.util.Set<String> files = new java.util.TreeSet<>();
            for (Map<String, Object> e : Concepts.maps(v.get("evidence"))) {
                files.add(String.valueOf(e.get("file")));
            }
            fileSets.add(files);
        }
        if (fileSets.size() != 1) {
            return;
        }
        for (Map<String, Object> v : disproved) {
            v.put("outcome", "DOUBT");
            v.put("note", "uniform-disproof: " + v.get("note"));
        }
        problems.add("all\toutcome\tevery scenario was disproved with the same files " + fileSets.iterator().next()
                + "; recorded as DOUBT: a defect that stops the flow earlier is BLOCKED, not a disproof");
    }

    private static String lower(Object confidence) {
        int i = CONFIDENCE.indexOf(String.valueOf(confidence));
        return CONFIDENCE.get(Math.max(0, i - 1));
    }
}
