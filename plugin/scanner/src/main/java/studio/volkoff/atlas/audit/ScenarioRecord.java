package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.EvidenceVerifier;
import studio.volkoff.atlas.report.Places;

import java.io.IOException;
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
 * {@code audit record-step <flow>:SCENARIOS}: checks the scenario author's draft against the
 * recorded flow, stores the accepted scenarios in {@code scenarios/<flow>.jsonl}, derives one
 * finding per rule or concept, flow and broken step, and writes a DEEP verdict row for every place
 * a scenario explains. A rejected row is named and dropped; the rest are kept.
 */
final class ScenarioRecord {

    static final Set<String> TRIGGERS = Set.of("PROVIDER_DOWN", "PROVIDER_SLOW", "DB_DOWN", "COMMIT_FAILS", "CRASH_BEFORE_COMMIT",
            "CRASH_AFTER_COMMIT", "RESTART_DEPLOY", "REDELIVERY", "DUPLICATE_DELIVERY", "CONCURRENT_DELIVERY", "OUT_OF_ORDER",
            "POISON_MESSAGE", "TIMEOUT_RETRY", "PARTIAL_FAILURE_IN_FANOUT", "SECOND_REPLICA");
    static final Set<String> STATES = Set.of("OK", "BROKEN", "LOST", "DUPLICATE", "SKIPPED", "HUNG", "INJECTED");
    static final Set<String> BREAKS = Set.of("BROKEN", "LOST", "DUPLICATE", "HUNG");
    static final Set<String> OUTCOMES = Set.of("LOST", "DUPLICATED", "INCONSISTENT", "HUNG", "DELAYED", "NONE");
    static final Set<String> COVERS = Set.of("FULL", "PARTIAL", "NONE");
    static final List<String> SEVERITIES = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    /** Fields the scanner stamps; a draft row that writes one is rejected. */
    static final Set<String> STAMPED = Set.of("id", "flowId", "flowFingerprint", "status", "findingId", "model", "batch", "commit",
            "recordedAt", "verifiedBy");
    static final int MAX_SCENARIOS = 6;

    private ScenarioRecord() {
    }

    static Path file(AuditStore store, String flowId) {
        return store.dir.resolve("scenarios").resolve(flowId + ".jsonl");
    }

    /** The patterns the flow matched at the last SCENARIOS step, and those no accepted scenario covers. */
    static Path conceptsFile(AuditStore store, String flowId) {
        return store.dir.resolve("scenarios").resolve(flowId + ".concepts.json");
    }

    static List<Map<String, Object>> stored(AuditStore store, String flowId) throws IOException {
        Path f = file(store, flowId);
        return Files.exists(f) ? AtlasStore.readJsonl(f) : new ArrayList<>();
    }

    static void store(AuditStore store, String flowId, List<Map<String, Object>> rows) throws IOException {
        StringBuilder s = new StringBuilder();
        for (Map<String, Object> r : rows) {
            s.append(Json.write(r)).append('\n');
        }
        AuditStore.write(file(store, flowId), s.toString());
    }

    static Deep.Outcome record(AuditContext ctx, AuditStore store, Flows flows, Map<String, Object> plan, Map<String, Object> job,
                               Map<String, Object> step, String batch) throws Exception {
        String flowId = String.valueOf(job.get("flowId"));
        if (!Files.exists(flows.doc(flowId))) {
            return new Deep.Outcome(false, "no-flow", "# no recorded flow " + flowId + "\n");
        }
        Map<String, Object> doc = Flows.read(flows.doc(flowId));
        Path draft = Deep.draft(store, flows, flowId, "SCENARIOS");
        if (!Files.exists(draft) || AuditStore.read(draft).isBlank()) {
            return new Deep.Outcome(false, "no-draft", "# the author wrote no draft at " + Plan.shown(draft) + "\n");
        }
        FlowFacts facts = new FlowFacts(doc);
        Map<String, Places.Place> onFlow = new LinkedHashMap<>();
        for (Flows.Crossing c : flows.crossings(ctx.slices, doc, ctx.places.places())) {
            onFlow.put(c.place().id(), c.place());
        }
        List<Concepts.Match> matches = Concepts.match(doc, DeepPacket.webhooks(ctx), DeepPacket.idempotent(ctx));
        Map<String, Concepts.Concept> catalogue = Concepts.load();
        EvidenceVerifier verifier = new EvidenceVerifier(ctx.repo);
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String commit = String.valueOf(((Map<?, ?>) plan.get("atlas")).get("commit"));
        String model = String.valueOf(step.get("modelId"));
        Object fingerprint = doc.get("fingerprint") instanceof Map<?, ?> fp ? fp.get("core") : null;
        int limit = MAX_SCENARIOS + matches.size();

        List<Map<String, Object>> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        List<String> longText = new ArrayList<>();
        int lines = 0;
        String[] all = AuditStore.read(draft).split("\\R");
        for (int i = 0; i < all.length; i++) {
            String line = all[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            lines++;
            String at = "line " + (i + 1);
            Map<String, Object> d;
            try {
                d = Json.parseObject(line);
            } catch (IllegalArgumentException e) {
                rejected.add(at + "\t-\tnot a JSON object (" + e.getMessage() + ")");
                continue;
            }
            Check c = new Check();
            Places.Place place = resolvePlace(d, onFlow, c);
            validate(d, facts, place, ctx.hypotheses, catalogue, verifier, c);
            if (!c.problems.isEmpty()) {
                c.problems.forEach(p -> rejected.add(at + "\t" + p));
                continue;
            }
            c.notes.forEach(n -> longText.add(at + "\t" + n));
            Map<String, Object> row = scenario(d, flowId, fingerprint, place, model, batch, commit, now);
            if (accepted.stream().anyMatch(r -> r.get("id").equals(row.get("id")))) {
                rejected.add(at + "\t-\ta second scenario with the same trigger, trigger step and broken step as " + row.get("id"));
            } else if (accepted.size() >= limit) {
                rejected.add(at + "\t-\tover the limit of " + limit + " scenarios for this flow");
            } else {
                accepted.add(row);
            }
        }
        StringBuilder out = new StringBuilder();
        if (accepted.isEmpty()) {
            out.append("# no scenario accepted for ").append(flowId).append(": ").append(rejected.size()).append(" rejected\n");
            rejected.forEach(r -> out.append("REJECTED\t").append(r).append('\n'));
            return new Deep.Outcome(false, lines == 0 ? "no-draft" : "rejected", out.toString());
        }

        List<Map<String, Object>> findings = findings(accepted, facts, ctx.slices, catalogue, ctx.hypotheses, commit, model, batch, now);
        List<Map<String, Object>> placeRows = new ArrayList<>();
        for (Places.Place p : ctx.places.places()) {
            List<Map<String, Object>> naming = accepted.stream().filter(r -> p.id().equals(r.get("placeId"))).toList();
            if (!naming.isEmpty()) {
                placeRows.add(placeRow(ctx, p, naming, flowId, plan, job, model, commit, now));
            }
        }
        Set<Object> rejudged = new LinkedHashSet<>();
        findings.forEach(f -> rejudged.add(f.get("dedupeKey")));
        List<Map<String, Object>> kept = new ArrayList<>();
        List<Map<String, Object>> carried = new ArrayList<>();
        for (Map<String, Object> f : DeepPacket.findings(store)) {
            if (!(flowId.equals(f.get("flowId")) && f.get("scenarioIds") != null)) {
                kept.add(f);
            } else if (!rejudged.contains(f.get("dedupeKey")) && unchanged(ctx, f)) {
                // Not judged this time but its code did not change: still open, not fixed.
                f.put("notRejudged", true);
                carried.add(f);
            }
        }
        Set<Object> carriedIds = new LinkedHashSet<>();
        carried.forEach(f -> carriedIds.add(f.get("id")));
        List<Map<String, Object>> scenarios = new ArrayList<>(accepted);
        for (Map<String, Object> sc : stored(store, flowId)) {
            if (carriedIds.contains(sc.get("findingId")) && scenarios.stream().noneMatch(r -> r.get("id").equals(sc.get("id")))) {
                sc.put("notRejudged", true);
                scenarios.add(sc);
            }
        }
        store(store, flowId, scenarios);
        kept.addAll(findings);
        kept.addAll(carried);
        store.writeJsonl("findings.jsonl", kept);
        store.writeRows(String.valueOf(job.get("batch")), placeRows);
        long harmless = accepted.stream().filter(r -> "HARMLESS".equals(r.get("status"))).count();
        step.put("scenarios", accepted.size());
        step.put("rejected", rejected.size());

        out.append("# scenarios recorded for ").append(flowId).append(": ").append(accepted.size()).append(" accepted")
                .append(harmless > 0 ? " (" + harmless + " harmless)" : "").append(", ").append(rejected.size()).append(" rejected · ")
                .append(findings.size()).append(findings.size() == 1 ? " finding · " : " findings · ")
                .append(placeRows.size()).append(placeRows.size() == 1 ? " place" : " places").append(" judged deep\n");
        out.append("scenario\tstatus\tseverity\tconfidence\tscope\tfinding\ttitle\n");
        for (Map<String, Object> r : accepted) {
            Object scope = r.get("concept") instanceof Map<?, ?> m ? m.get("scope") : null;
            out.append(r.get("id")).append('\t').append(r.get("status")).append('\t').append(r.get("severity")).append('\t')
                    .append(r.get("confidence")).append('\t').append(scope == null ? "-" : scope).append('\t')
                    .append(r.getOrDefault("findingId", "-")).append('\t').append(r.get("title")).append('\n');
        }
        rejected.forEach(r -> out.append("REJECTED\t").append(r).append('\n'));
        longText.forEach(r -> out.append("LONG\t").append(r).append('\n'));
        if (!carried.isEmpty()) {
            out.append("# kept ").append(carried.size()).append(carried.size() == 1 ? " finding" : " findings")
                    .append(" of an earlier run that this one did not judge; their code is unchanged\n");
        }
        List<Map<String, Object>> matched = new ArrayList<>();
        List<Map<String, Object>> uncovered = new ArrayList<>();
        for (Concepts.Match m : matches) {
            Map<String, Object> c = Map.of("id", m.concept(), "name", catalogue.get(m.concept()).name());
            matched.add(c);
            boolean covered = accepted.stream().anyMatch(r -> r.get("concept") instanceof Map<?, ?> x && m.concept().equals(x.get("pattern")));
            if (!covered) {
                uncovered.add(c);
                out.append("# concept ").append(m.concept()).append(" matched this flow and has no scenario\n");
            }
        }
        AuditStore.write(conceptsFile(store, flowId), Json.write(Map.of("matched", matched, "uncovered", uncovered)) + "\n");
        return new Deep.Outcome(true, null, out.toString());
    }

    /** The place a row explains: its {@code placeId}, or the one place on the flow with its {@code ruleId}. */
    private static Places.Place resolvePlace(Map<String, Object> d, Map<String, Places.Place> onFlow, Check c) {
        Object pid = d.get("placeId");
        Object rule = d.get("ruleId");
        if (pid != null) {
            Places.Place p = onFlow.get(String.valueOf(pid));
            if (p == null) {
                c.problem("placeId", pid + " is not a place on this flow");
            } else if (rule != null && !p.code().equals(rule)) {
                c.problem("ruleId", rule + " is not the rule of " + pid + " (" + p.code() + ")");
            }
            return p;
        }
        if (rule == null) {
            return null;
        }
        List<Places.Place> same = onFlow.values().stream().filter(p -> p.code().equals(rule)).toList();
        if (same.size() > 1) {
            c.problem("ruleId", rule + " matches " + same.size() + " places on this flow; name placeId");
        }
        return same.size() == 1 ? same.get(0) : null;
    }

    private static void validate(Map<String, Object> d, FlowFacts facts, Places.Place place, Map<String, Hypotheses.Hypothesis> hypotheses,
                                 Map<String, Concepts.Concept> catalogue, EvidenceVerifier verifier, Check c) throws IOException {
        for (String f : STAMPED) {
            if (d.containsKey(f)) {
                c.problem(f, "only the scanner writes it");
            }
        }
        c.limit("title", d.get("title"), 60, true);
        c.limit("because", d.get("because"), 200, true);
        boolean harmless = d.get("outcome") instanceof Map<?, ?> o && "NONE".equals(o.get("kind"));

        if (d.get("trigger") instanceof Map<?, ?> t) {
            c.oneOf("trigger.kind", t.get("kind"), TRIGGERS);
            if (!facts.steps.containsKey(String.valueOf(t.get("at")))) {
                c.problem("trigger.at", t.get("at") + " is not a step of this flow");
            }
            c.limit("trigger.detail", t.get("detail"), 80, true);
        } else {
            c.problem("trigger", "is missing");
        }

        Set<String> refs = new LinkedHashSet<>();
        Set<String> broken = new LinkedHashSet<>();
        List<Map<String, Object>> story = Concepts.maps(d.get("steps"));
        if (story.size() < 2 || story.size() > 8) {
            c.problem("steps", "has " + story.size() + " items; a story has 2 to 8");
        }
        for (int i = 0; i < story.size(); i++) {
            Map<String, Object> s = story.get(i);
            String where = "steps[" + i + "]";
            c.limit(where + ".happens", s.get("happens"), 140, true);
            String state = String.valueOf(s.get("state"));
            c.oneOf(where + ".state", s.get("state"), STATES);
            Object ref = s.get("stepRef");
            if (state.equals("INJECTED")) {
                if (ref != null) {
                    c.problem(where + ".stepRef", "the trigger step has no stepRef");
                }
            } else if (ref == null || !facts.steps.containsKey(String.valueOf(ref))) {
                c.problem(where + ".stepRef", ref == null ? "is missing" : ref + " is not a step of this flow");
            } else {
                refs.add(String.valueOf(ref));
                if (BREAKS.contains(state)) {
                    broken.add(String.valueOf(ref));
                }
            }
        }
        Object brokenAt = d.get("brokenAt");
        if (brokenAt == null ? !harmless : !(harmless ? refs : broken).contains(String.valueOf(brokenAt))) {
            c.problem("brokenAt", brokenAt == null ? "is missing"
                    : brokenAt + " is not the stepRef of a " + (harmless ? "story step" : "BROKEN, LOST, DUPLICATE or HUNG story step"));
        }

        if (d.get("outcome") instanceof Map<?, ?> o) {
            c.oneOf("outcome.kind", o.get("kind"), OUTCOMES);
            c.limit("outcome.what", o.get("what"), 80, true);
            Object bp = o.get("businessPart");
            if (bp != null ? !facts.parts.contains(String.valueOf(bp)) : !harmless) {
                c.problem("outcome.businessPart", bp == null ? "is missing" : bp + " is not a business part of this flow");
            }
            if (!harmless && broken.isEmpty()) {
                c.problem("steps", "an outcome other than NONE needs a BROKEN, LOST, DUPLICATE or HUNG story step");
            }
        } else {
            c.problem("outcome", "is missing");
        }

        Hypotheses.Hypothesis h = place == null ? null : hypotheses.get(place.code());
        Set<String> names = new LinkedHashSet<>();
        if (h != null) {
            h.mitigations().forEach(m -> names.add(normal(m.name())));
        }
        if (d.get("mitigation") instanceof Map<?, ?> m) {
            if (!(m.get("present") instanceof Boolean)) {
                c.problem("mitigation.present", "is not true or false");
            }
            c.limit("mitigation.name", m.get("name"), 80, true);
            c.oneOf("mitigation.covers", m.get("covers"), COVERS);
            if (h != null && Boolean.TRUE.equals(m.get("present")) && !names.contains(normal(String.valueOf(m.get("name"))))) {
                c.problem("mitigation.name", m.get("name") + " is not a mitigation of " + place.code());
            }
            if (m.get("evidence") instanceof Map<?, ?> e) {
                c.evidence("mitigation.evidence", e, verifier);
            }
        } else {
            c.problem("mitigation", "is missing");
        }
        if (h != null) {
            Set<String> checked = new LinkedHashSet<>();
            for (Map<String, Object> m : Concepts.maps(d.get("mitigationsChecked"))) {
                String name = normal(String.valueOf(m.get("name")));
                if (!names.contains(name)) {
                    c.problem("mitigationsChecked", "names " + m.get("name") + ", which is not a mitigation of " + place.code());
                }
                c.oneOf("mitigationsChecked[" + m.get("name") + "].result", m.get("result"), Record.RESULTS);
                checked.add(name);
            }
            for (Hypotheses.Mitigation m : h.mitigations()) {
                if (!checked.contains(normal(m.name()))) {
                    c.problem("mitigationsChecked", "lacks \"" + m.name() + "\"");
                }
            }
        }

        Map<?, ?> concept = d.get("concept") instanceof Map<?, ?> m ? m : null;
        Object pattern = concept == null ? null : concept.get("pattern");
        if (pattern != null && !catalogue.containsKey(String.valueOf(pattern))) {
            c.problem("concept.pattern", pattern + " is not a concept id");
        }
        if (!harmless) {
            if (place == null && pattern == null) {
                c.problem("ruleId", "a scenario that breaks something names the place it explains (placeId or ruleId) or a concept.pattern");
            }
            List<Map<String, Object>> closes = concept == null ? List.of() : Concepts.maps(concept.get("closes"));
            if (closes.isEmpty()) {
                c.problem("concept.closes", "is missing: name at least one change that closes the scenario");
            }
            for (int i = 0; i < closes.size(); i++) {
                Map<String, Object> x = closes.get(i);
                c.limit("concept.closes[" + i + "].change", x.get("change"), 100, true);
                c.oneOf("concept.closes[" + i + "].level", x.get("level"), Set.of("STEP", "CONCEPT"));
                List<?> steps = x.get("steps") instanceof List<?> l ? l : List.of();
                if (steps.isEmpty()) {
                    c.problem("concept.closes[" + i + "].steps", "is missing: the story steps the change closes");
                }
                for (Object s : steps) {
                    if (!refs.contains(String.valueOf(s))) {
                        c.problem("concept.closes[" + i + "].steps", s + " is not a stepRef of the story");
                    }
                }
            }
            if (!(d.get("finding") instanceof Map<?, ?> f) || !(f.get("recommendation") instanceof String r) || r.isBlank()) {
                c.problem("finding.recommendation", "is missing");
            }
        }
        c.oneOf("severity", d.get("severity"), Set.copyOf(SEVERITIES));
        c.oneOf("confidence", d.get("confidence"), Record.CONFIDENCES);
        List<?> evidence = d.get("evidence") instanceof List<?> l ? l : List.of();
        if (evidence.isEmpty()) {
            c.problem("evidence", "needs at least one {file, line, snippet, claim}");
        }
        for (int i = 0; i < evidence.size(); i++) {
            if (evidence.get(i) instanceof Map<?, ?> e) {
                c.evidence("evidence[" + i + "]", e, verifier);
                c.limit("evidence[" + i + "].claim", e.get("claim"), 120, true);
            } else {
                c.problem("evidence[" + i + "]", "is not an object");
            }
        }
        if (d.containsKey("filesOpened") && !(d.get("filesOpened") instanceof List<?>)) {
            c.problem("filesOpened", "is not a list");
        }
    }

    /** The stored scenario: the author's fields in a fixed order, plus what the scanner stamps and computes. */
    private static Map<String, Object> scenario(Map<String, Object> d, String flowId, Object fingerprint, Places.Place place, String model,
                                                String batch, String commit, String now) {
        Map<?, ?> t = (Map<?, ?>) d.get("trigger");
        boolean harmless = "NONE".equals(((Map<?, ?>) d.get("outcome")).get("kind"));
        String brokenAt = d.get("brokenAt") == null ? null : String.valueOf(d.get("brokenAt"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", "SC-" + Flows.sha1Short(flowId + "|" + t.get("kind") + "|" + t.get("at") + "|" + (brokenAt == null ? "-" : brokenAt)));
        r.put("flowId", flowId);
        r.put("flowFingerprint", fingerprint);
        for (String k : List.of("title", "trigger", "steps")) {
            r.put(k, d.get(k));
        }
        r.put("brokenAt", brokenAt);
        for (String k : List.of("outcome", "because", "mitigation")) {
            r.put(k, d.get(k));
        }
        if (place != null) {
            r.put("ruleId", place.code());
            r.put("placeId", place.id());
            r.put("mitigationsChecked", d.get("mitigationsChecked"));
        }
        if (d.get("concept") instanceof Map<?, ?> m) {
            Map<String, Object> concept = new LinkedHashMap<>();
            if (m.get("pattern") != null) {
                concept.put("pattern", m.get("pattern"));
            }
            if (!harmless) {
                concept.put("scope", scope(d));
            }
            if (m.get("scope") != null) {
                concept.put("proposedScope", m.get("scope"));
            }
            concept.put("closes", m.get("closes") == null ? List.of() : m.get("closes"));
            r.put("concept", concept);
        }
        r.put("severity", d.get("severity"));
        r.put("confidence", d.get("confidence"));
        r.put("evidence", d.get("evidence"));
        if (d.get("finding") instanceof Map<?, ?> f) {
            r.put("finding", f);
        }
        r.put("filesOpened", d.get("filesOpened") instanceof List<?> l ? l : List.of());
        r.put("status", harmless ? "HARMLESS" : "CANDIDATE");
        r.put("model", model);
        r.put("batch", batch);
        r.put("commit", commit);
        r.put("recordedAt", now);
        return r;
    }

    /** STEP when one STEP change alone closes every broken step of the story, CONCEPT otherwise. */
    static String scope(Map<String, Object> d) {
        Set<String> broken = new LinkedHashSet<>();
        for (Map<String, Object> s : Concepts.maps(d.get("steps"))) {
            if (BREAKS.contains(String.valueOf(s.get("state"))) && s.get("stepRef") != null) {
                broken.add(String.valueOf(s.get("stepRef")));
            }
        }
        Map<?, ?> concept = (Map<?, ?>) d.get("concept");
        for (Map<String, Object> x : Concepts.maps(concept.get("closes"))) {
            Set<String> closed = new LinkedHashSet<>();
            if (x.get("steps") instanceof List<?> l) {
                l.forEach(s -> closed.add(String.valueOf(s)));
            }
            if ("STEP".equals(x.get("level")) && closed.containsAll(broken)) {
                return "STEP";
            }
        }
        return "CONCEPT";
    }

    /** One finding per rule or concept, flow and broken step; the worst scenario leads, later ones join it. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findings(List<Map<String, Object>> accepted, FlowFacts facts, Slices slices,
                                                      Map<String, Concepts.Concept> catalogue, Map<String, Hypotheses.Hypothesis> hypotheses,
                                                      String commit, String model, String batch, String now) throws Exception {
        List<Map<String, Object>> live = new ArrayList<>(accepted.stream().filter(r -> "CANDIDATE".equals(r.get("status"))).toList());
        live.sort((a, b) -> Integer.compare(SEVERITIES.indexOf(String.valueOf(b.get("severity"))), SEVERITIES.indexOf(String.valueOf(a.get("severity")))));
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> sc : live) {
            Map<?, ?> concept = sc.get("concept") instanceof Map<?, ?> m ? m : Map.of();
            String rule = sc.get("ruleId") != null ? String.valueOf(sc.get("ruleId")) : String.valueOf(concept.get("pattern"));
            String key = rule + "|" + sc.get("flowId") + "|" + sc.get("brokenAt");
            Map<String, Object> f = byKey.get(key);
            if (f == null) {
                f = new LinkedHashMap<>();
                f.put("id", "F-" + Flows.sha1Short(key));
                f.put("ruleId", rule);
                f.put("title", sc.get("title"));
                f.put("flowId", sc.get("flowId"));
                f.put("operationId", facts.entryOf(String.valueOf(sc.get("brokenAt"))));
                if (sc.get("placeId") != null) {
                    f.put("placeId", sc.get("placeId"));
                }
                f.put("commit", commit);
                f.put("severity", sc.get("severity"));
                f.put("confidence", sc.get("confidence"));
                f.put("status", "CANDIDATE");
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("pattern", concept.get("pattern"));
                c.put("scope", concept.get("scope"));
                f.put("concept", c);
                f.put("scenarioIds", new ArrayList<>());
                f.put("stepIds", new ArrayList<>());
                f.put("failureScenario", story(sc));
                f.put("evidence", sc.get("evidence"));
                f.put("mitigationsChecked", sc.get("mitigationsChecked") == null ? List.of() : sc.get("mitigationsChecked"));
                Map<?, ?> fx = (Map<?, ?>) sc.get("finding");
                f.put("recommendation", fx.get("recommendation"));
                List<String> verification = new ArrayList<>();
                if (fx.get("verification") instanceof List<?> l) {
                    l.forEach(v -> verification.add(String.valueOf(v)));
                }
                if (verification.isEmpty()) {
                    Concepts.Concept cc = catalogue.get(String.valueOf(concept.get("pattern")));
                    Hypotheses.Hypothesis h = hypotheses.get(rule);
                    verification.add(cc != null ? cc.verify() : h != null ? h.verify() : "Inject the scenario's trigger and check the outcome does not happen.");
                }
                f.put("verification", verification);
                int opened = ((List<?>) sc.get("filesOpened")).size();
                f.put("sourceBudget", Map.of("filesOpened", opened, "limit", DeepPacket.SCENARIO_FILES, "expanded", opened > DeepPacket.SCENARIO_FILES));
                f.put("dedupeKey", key);
                Map<String, Object> broken = facts.steps.get(String.valueOf(sc.get("brokenAt")));
                List<Map<String, Object>> cited = new ArrayList<>(Concepts.maps(sc.get("evidence")));
                if (broken != null && broken.get("evidence") instanceof Map<?, ?> be) {
                    Map<String, Object> at = new LinkedHashMap<>();
                    at.put("file", be.get("file"));
                    at.put("line", be.get("line"));
                    f.put("brokenAt", sc.get("brokenAt"));
                    f.put("brokenAtKey", Flows.stepKey(slices, broken));
                    f.put("brokenAtEvidence", at);
                    cited.add(at);
                }
                f.put("core", Flows.evidenceCore(slices, cited));
                f.put("tier", "DEEP");
                f.put("model", model);
                f.put("batch", batch);
                f.put("recordedAt", now);
                byKey.put(key, f);
            }
            ((List<Object>) f.get("scenarioIds")).add(sc.get("id"));
            List<Object> steps = (List<Object>) f.get("stepIds");
            for (Map<String, Object> s : Concepts.maps(sc.get("steps"))) {
                if (s.get("stepRef") != null && !steps.contains(s.get("stepRef"))) {
                    steps.add(s.get("stepRef"));
                }
            }
            sc.put("findingId", f.get("id"));
        }
        return new ArrayList<>(byKey.values());
    }

    /** Whether the methods a finding cites, and the one its broken step stands in, are as they were when it was recorded. */
    static boolean unchanged(AuditContext ctx, Map<String, Object> finding) throws Exception {
        if (!(finding.get("core") instanceof String core)) {
            return false;
        }
        List<Map<String, Object>> cited = new ArrayList<>(Concepts.maps(finding.get("evidence")));
        if (finding.get("brokenAtEvidence") instanceof Map<?, ?> at) {
            cited.add(Map.of("file", at.get("file"), "line", at.get("line")));
        }
        return core.equals(Flows.evidenceCore(ctx.slices, cited));
    }

    /** The story as one line: what the free-text {@code failureScenario} field of a finding holds. */
    static String story(Map<String, Object> sc) {
        StringBuilder s = new StringBuilder();
        for (Map<String, Object> st : Concepts.maps(sc.get("steps"))) {
            s.append(st.get("n")).append(". ").append(String.valueOf(st.get("happens")).strip()).append(' ');
        }
        Map<?, ?> o = (Map<?, ?>) sc.get("outcome");
        return s.append("→ ").append(o.get("kind")).append(": ").append(o.get("what")).toString();
    }

    private static Map<String, Object> placeRow(AuditContext ctx, Places.Place p, List<Map<String, Object>> naming, String flowId,
                                                Map<String, Object> plan, Map<String, Object> job, String model, String commit, String now)
            throws Exception {
        List<Map<String, Object>> live = naming.stream().filter(r -> "CANDIDATE".equals(r.get("status"))).toList();
        Map<String, Object> lead = live.isEmpty() ? naming.get(0) : live.stream()
                .max((a, b) -> Integer.compare(SEVERITIES.indexOf(String.valueOf(a.get("severity"))), SEVERITIES.indexOf(String.valueOf(b.get("severity")))))
                .orElseThrow();
        Packet.PlaceView view = Packet.view(ctx, p);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("placeId", p.id());
        row.put("rule", p.code());
        row.put("level", p.level().name());
        row.put("file", p.file());
        row.put("symbol", p.symbol());
        row.put("operationIds", p.ops());
        row.put("tier", "DEEP");
        row.put("model", model);
        row.put("batch", job.get("batch"));
        row.put("commit", commit);
        row.put("core", Fingerprint.core(ctx, view));
        row.put("recordedAt", now);
        row.put("verdict", live.isEmpty() ? "REFUTED" : "CONFIRMED");
        row.put("confidence", lead.get("confidence"));
        row.put("summary", lead.get("title") + ": " + lead.get("because"));
        row.put("evidence", lead.get("evidence"));
        row.put("mitigationsChecked", lead.get("mitigationsChecked"));
        row.put("flowId", flowId);
        Set<Object> findingIds = new LinkedHashSet<>();
        Set<Object> scenarioIds = new LinkedHashSet<>();
        Set<String> opened = new LinkedHashSet<>();
        for (Map<String, Object> r : naming) {
            scenarioIds.add(r.get("id"));
            if (r.get("findingId") != null) {
                findingIds.add(r.get("findingId"));
            }
            for (Object f : (List<?>) r.get("filesOpened")) {
                opened.add(String.valueOf(f).replace('\\', '/'));
            }
        }
        row.put("findingIds", new ArrayList<>(findingIds));
        row.put("scenarioIds", new ArrayList<>(scenarioIds));
        Set<String> packetFiles = view.files();
        List<String> outside = opened.stream().filter(f -> !f.startsWith(".reliability/") && !packetFiles.contains(f)).toList();
        row.put("filesOpened", new ArrayList<>(opened));
        row.put("openedFiles", outside);
        row.put("opened", Fingerprint.opened(ctx.repo, outside));
        row.put("status", "CURRENT");
        return row;
    }

    private static String normal(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }

    /** What a scenario may refer to in its flow. */
    private static final class FlowFacts {
        final Map<String, Map<String, Object>> steps = new LinkedHashMap<>();
        final Set<String> parts = new LinkedHashSet<>();
        final Map<String, String> entries = new LinkedHashMap<>();

        FlowFacts(Map<String, Object> doc) {
            Concepts.maps(doc.get("steps")).forEach(s -> steps.put(String.valueOf(s.get("id")), s));
            Concepts.maps(doc.get("businessParts")).forEach(b -> parts.add(String.valueOf(b.get("id"))));
            Concepts.maps(doc.get("entries")).forEach(e -> entries.put(String.valueOf(e.get("segment")), String.valueOf(e.get("entrypointId"))));
        }

        /** The entry point of the segment a step belongs to; the first entry for an unknown step. */
        String entryOf(String stepId) {
            Map<String, Object> s = steps.get(stepId);
            String seg = s == null ? null : String.valueOf(s.get("segment"));
            return seg != null && entries.containsKey(seg) ? entries.get(seg) : entries.values().iterator().next();
        }
    }

    /**
     * Problems of one draft row, each as {@code field<TAB>what}, and notes that do not reject it.
     * A text over its limit is kept up to half as long again, so a wordy sentence does not cost a
     * whole scenario; past that the row is dropped.
     */
    static final class Check {
        final List<String> problems = new ArrayList<>();
        final List<String> notes = new ArrayList<>();

        void problem(String where, String what) {
            problems.add(where + "\t" + what);
        }

        void limit(String where, Object value, int max, boolean required) {
            if (value == null || String.valueOf(value).isBlank()) {
                if (required) {
                    problem(where, "is missing");
                }
                return;
            }
            int len = String.valueOf(value).length();
            if (len > max * 3 / 2) {
                problem(where, "is " + len + " characters; the limit is " + max + ", and past " + max * 3 / 2 + " the row is dropped");
            } else if (len > max) {
                notes.add(where + "\tis " + len + " characters, over its limit of " + max + "; kept");
            }
        }

        void oneOf(String where, Object value, Set<String> allowed) {
            if (!allowed.contains(String.valueOf(value))) {
                problem(where, value + " is not one of " + String.join(", ", allowed.stream().sorted().toList()));
            }
        }

        void evidence(String where, Map<?, ?> e, EvidenceVerifier verifier) throws IOException {
            EvidenceVerifier.Check c = verifier.check(e);
            if (c != EvidenceVerifier.Check.OK) {
                problem(where, c + " " + e.get("file") + ":" + e.get("line"));
            }
        }
    }
}
