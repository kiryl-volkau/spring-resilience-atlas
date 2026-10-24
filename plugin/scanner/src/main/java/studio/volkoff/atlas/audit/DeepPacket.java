package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.report.Places;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** The packets of the deep audit's three steps: everything an agent needs, so it rarely opens a file. */
final class DeepPacket {

    static final int SCENARIO_FILES = 10;
    static final int VERIFY_FILES = 3;
    static final int MAX_VERIFIED = 4;

    private DeepPacket() {
    }

    static String trace(AuditContext ctx, Flows flows, Map<String, Object> job, String draft) throws Exception {
        String flowId = String.valueOf(job.get("flowId"));
        String start = String.valueOf(job.get("startEp"));
        Map<String, Object> ep = ctx.inputById.get(start).ep();
        StringBuilder out = new StringBuilder();
        out.append("# trace packet for ").append(flowId).append('\n');
        out.append("# write the draft to ").append(draft).append(", then run `audit record-flow ").append(flowId)
                .append("` with the atlas CLI until it exits 0; the flow-tracer's own rules apply\n\n");
        out.append("Flow id: ").append(flowId).append('\n');
        out.append("Start: ").append(start).append(' ').append(ep.get("kind")).append(' ').append(ep.get("subkind")).append(' ')
                .append(ep.get("target")).append("  ").append(ep.get("cls")).append('.').append(ep.get("method")).append("  ")
                .append(ep.get("file")).append(':').append(ep.get("line")).append("\n\n");
        out.append("The deep audit is about these places; make sure the trace covers their code:\n");
        for (Object pid : (List<?>) job.get("places")) {
            Places.Place p = ctx.placeById.get(String.valueOf(pid));
            if (p != null) {
                out.append("  ").append(p.id()).append("  ").append(p.code()).append("  ").append(p.symbol()).append("  ")
                        .append(p.file()).append(':').append(p.line()).append('\n');
            }
        }
        out.append("\nRun `flow candidates ").append(start).append("` first and decide every candidate it prints.\n");
        return out.toString();
    }

    static String scenarios(AuditContext ctx, Flows flows, AuditStore store, String flowId, String draft) throws Exception {
        Map<String, Object> doc = Flows.read(flows.doc(flowId));
        StringBuilder out = new StringBuilder();
        out.append("# scenario packet for ").append(flowId).append(" · ").append(doc.get("title")).append('\n');
        out.append("# write your draft to ").append(draft).append(" (one JSON object per line; format at the end)\n");
        out.append("# rules: open at most ").append(SCENARIO_FILES).append(" files, only ones this packet names; never search the tree; never modify any file except your draft\n\n");
        out.append(flowTable(doc));
        List<Flows.Crossing> crossings = flows.crossings(ctx.slices, doc, ctx.places.places());
        Map<String, Map<String, Object>> rows = placeRows(store);
        out.append("\n## places on this flow: the hints and what the quick check found\n");
        Set<String> otherFiles = new TreeSet<>();
        List<Slices.Slice> placeSlices = new ArrayList<>();
        for (Flows.Crossing c : crossings) {
            Places.Place p = c.place();
            if (c.module()) {
                continue;
            }
            Packet.PlaceView view = Packet.view(ctx, p);
            placeSlices.addAll(view.evidenceSlices());
            Hypotheses.Hypothesis h = Hypotheses.load().get(p.code());
            out.append("\n### ").append(p.id()).append(" · ").append(p.code()).append(" · ").append(p.level()).append(" · ").append(p.symbol())
                    .append(" · ").append(p.file()).append(':').append(p.line())
                    .append(c.steps().isEmpty() ? " · reached by an entry of this flow" : " · at steps " + stepNumbers(doc, c.steps())).append('\n');
            if (h != null) {
                out.append("hypothesis: ").append(h.hypothesis()).append('\n');
                out.append("mitigation names (use exactly, in mitigationsChecked): ");
                List<String> names = new ArrayList<>();
                for (Hypotheses.Mitigation m : h.mitigations()) {
                    names.add(m.name() + " (" + m.kind() + ": " + m.text() + ")");
                }
                out.append(String.join("; ", names)).append('\n');
            }
            Map<String, Object> r = rows.get(p.id());
            if (r != null) {
                out.append("quick check: ").append(r.get("verdict")).append(" ").append(r.get("tier")).append(" — ").append(r.get("summary")).append('\n');
            }
            for (String[] f : view.otherFiles()) {
                otherFiles.add(f[0] + "  (" + f[1] + ")");
            }
        }
        List<String> module = new ArrayList<>();
        for (Flows.Crossing c : crossings) {
            if (c.module()) {
                module.add(c.place().id() + " " + c.place().title() + " (" + c.place().symbol() + ")");
            }
        }
        if (!module.isEmpty()) {
            out.append("\nmodule facts that reach this flow: ").append(String.join("; ", module)).append('\n');
        }
        out.append("\n## concepts whose structure this flow matches: write at least one scenario for each, HARMLESS when it does not bite\n");
        Map<String, Concepts.Concept> catalogue = Concepts.load();
        List<Concepts.Match> matches = Concepts.match(doc, webhooks(ctx), idempotent(ctx));
        if (matches.isEmpty()) {
            out.append("(none matched)\n");
        }
        for (Concepts.Match m : matches) {
            Concepts.Concept c = catalogue.get(m.concept());
            out.append("\n### ").append(c.id()).append(" · ").append(c.name()).append(" · steps ").append(stepNumbers(doc, m.steps())).append('\n');
            out.append("hypothesis: ").append(c.hypothesis()).append('\n');
            out.append("questions: ").append(c.questions()).append('\n');
            out.append("triggers to try: ").append(String.join(", ", c.triggers())).append('\n');
            out.append("closes it at step level: ").append(c.stepFix()).append("; at design level: ").append(c.conceptFix()).append('\n');
        }
        out.append(slices(ctx, doc, placeSlices));
        if (!otherFiles.isEmpty()) {
            out.append("\n## where a mitigation would live (not printed; open at most ").append(SCENARIO_FILES).append(" files in total)\n");
            otherFiles.forEach(f -> out.append("  ").append(f).append('\n'));
        }
        out.append(SCENARIO_FORMAT);
        return out.toString();
    }

    static String verification(AuditContext ctx, Flows flows, AuditStore store, String flowId, String draft) throws Exception {
        Map<String, Object> doc = Flows.read(flows.doc(flowId));
        List<Map<String, Object>> chosen = toVerify(store, flowId);
        Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
        for (Map<String, Object> f : findings(store)) {
            findings.put(String.valueOf(f.get("id")), f);
        }
        StringBuilder out = new StringBuilder();
        out.append("# verification packet for ").append(flowId).append(" · ").append(chosen.size()).append(" scenarios\n");
        out.append("# write your draft to ").append(draft).append(" (one JSON object per line; format at the end)\n");
        out.append("# rules: open at most ").append(VERIFY_FILES).append(" files beyond the cited ones, only ones this packet names; never modify any file except your draft\n\n");
        out.append("Your job is to disprove. For each scenario: re-read every cited line; look for the mitigation it says is missing in the files named;\n"
                + "check the story against the transactions, the durable flags and the acknowledgements in the flow table; then decide.\n\n");
        for (Map<String, Object> sc : chosen) {
            out.append("## ").append(sc.get("id")).append(" · ").append(sc.get("title")).append(" · ").append(sc.get("severity"))
                    .append(" · ").append(sc.get("confidence")).append('\n');
            Map<?, ?> t = (Map<?, ?>) sc.get("trigger");
            out.append("trigger: ").append(t.get("kind")).append(" at step ").append(stepNumbers(doc, List.of(String.valueOf(t.get("at"))))).append(" — ").append(t.get("detail")).append('\n');
            for (Map<String, Object> st : Concepts.maps(sc.get("steps"))) {
                out.append("  ").append(st.get("n")).append(". [").append(st.get("state")).append("] ")
                        .append(st.get("stepRef") == null ? "" : "step " + stepNumbers(doc, List.of(String.valueOf(st.get("stepRef")))) + ": ")
                        .append(st.get("happens")).append('\n');
            }
            out.append("because: ").append(sc.get("because")).append('\n');
            if (sc.get("mitigation") instanceof Map<?, ?> m) {
                out.append("mitigation: ").append(m.get("name")).append(" — ").append(Boolean.TRUE.equals(m.get("present")) ? "present, covers " + m.get("covers") : "missing").append('\n');
            }
            for (Map<String, Object> e : Concepts.maps(sc.get("evidence"))) {
                out.append("  evidence ").append(e.get("file")).append(':').append(e.get("line")).append("  ").append(e.get("snippet")).append("  — ").append(e.get("claim")).append('\n');
            }
            Map<String, Object> f = findings.get(String.valueOf(sc.get("findingId")));
            if (f != null) {
                out.append("finding ").append(f.get("id")).append(": fix — ").append(f.get("recommendation")).append('\n');
            }
            out.append('\n');
        }
        out.append(flowTable(doc));
        Set<String> otherFiles = new TreeSet<>();
        List<Slices.Slice> placeSlices = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        chosen.forEach(sc -> named.add(String.valueOf(sc.get("placeId"))));
        for (Flows.Crossing c : flows.crossings(ctx.slices, doc, ctx.places.places())) {
            if (!c.module() && named.contains(c.place().id())) {
                Packet.PlaceView view = Packet.view(ctx, c.place());
                placeSlices.addAll(view.evidenceSlices());
                for (String[] f : view.otherFiles()) {
                    otherFiles.add(f[0] + "  (" + f[1] + ")");
                }
            }
        }
        out.append(slices(ctx, doc, placeSlices));
        if (!otherFiles.isEmpty()) {
            out.append("\n## where a missing mitigation would live\n");
            otherFiles.forEach(x -> out.append("  ").append(x).append('\n'));
        }
        out.append(VERIFY_FORMAT);
        return out.toString();
    }

    /** The scenarios a verifier checks: written by this run, HIGH or CRITICAL, worst first, at most {@value #MAX_VERIFIED}. */
    static List<Map<String, Object>> toVerify(AuditStore store, String flowId) throws Exception {
        List<Map<String, Object>> all = new ArrayList<>();
        for (Map<String, Object> sc : ScenarioRecord.stored(store, flowId)) {
            if ("CANDIDATE".equals(sc.get("status")) && sc.get("notRejudged") == null
                    && ("HIGH".equals(sc.get("severity")) || "CRITICAL".equals(sc.get("severity")))) {
                all.add(sc);
            }
        }
        all.sort((a, b) -> Integer.compare(rank(b.get("severity")), rank(a.get("severity"))));
        return all.subList(0, Math.min(MAX_VERIFIED, all.size()));
    }

    private static int rank(Object severity) {
        return List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").indexOf(String.valueOf(severity));
    }

    /** The flow as one table: what every step id the agent cites stands for. */
    static String flowTable(Map<String, Object> doc) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, Object> p : Concepts.maps(doc.get("participants"))) {
            names.put(String.valueOf(p.get("id")), p.get("name") + (p.get("detail") != null ? " [" + p.get("detail") + "]" : ""));
        }
        StringBuilder out = new StringBuilder("## the flow: ").append(doc.get("summary")).append('\n');
        for (Map<String, Object> e : Concepts.maps(doc.get("entries"))) {
            out.append("segment ").append(e.get("segment")).append(": ").append(e.get("entrypointId")).append(' ').append(e.get("kind")).append(' ')
                    .append(e.get("target")).append(' ').append(e.get("symbol")).append(", started by ").append(e.get("trigger")).append('\n');
        }
        out.append("n\tid\tseg\tkind\tfrom -> to\tlabel\ttx\tthread\tdurable\tevidence\n");
        for (Map<String, Object> s : Concepts.maps(doc.get("steps"))) {
            Map<?, ?> ev = (Map<?, ?>) s.get("evidence");
            out.append(s.get("n")).append('\t').append(s.get("id")).append('\t').append(s.get("segment")).append('\t').append(s.get("kind")).append('\t')
                    .append(names.get(String.valueOf(s.get("from")))).append(" -> ").append(names.get(String.valueOf(s.get("to")))).append('\t')
                    .append(s.get("label")).append('\t').append(s.get("tx") == null ? "-" : s.get("tx")).append('\t')
                    .append(s.get("thread") == null ? "-" : names.getOrDefault(String.valueOf(s.get("thread")), String.valueOf(s.get("thread")))).append('\t')
                    .append(s.get("durable") == null ? "-" : s.get("durable")).append('\t')
                    .append(ev.get("file")).append(':').append(ev.get("line")).append("  ").append(ev.get("snippet"))
                    .append(s.get("notes") != null ? "  (" + s.get("notes") + ")" : "").append('\n');
        }
        for (Map<String, Object> t : Concepts.maps(doc.get("transactions"))) {
            out.append("transaction ").append(t.get("id")).append(" opened by ").append(t.get("owner")).append(": steps ")
                    .append(stepNumbers(doc, List.of(String.valueOf(t.get("begin")), String.valueOf(t.get("commit"))))).append('\n');
        }
        for (Map<String, Object> l : Concepts.maps(doc.get("links"))) {
            boolean accepted = l.get("decision") instanceof Map<?, ?> d && Boolean.TRUE.equals(d.get("accepted"));
            out.append("link ").append(l.get("kind")).append(' ').append(l.get("key")).append(" from step ")
                    .append(stepNumbers(doc, List.of(String.valueOf(l.get("fromStep")))))
                    .append(l.get("toStep") == null ? "" : " to step " + stepNumbers(doc, List.of(String.valueOf(l.get("toStep")))))
                    .append(": ").append(accepted ? "accepted, " + l.get("confidence") : "rejected").append(" — ").append(((Map<?, ?>) l.get("decision")).get("reason")).append('\n');
        }
        for (Map<String, Object> a : Concepts.maps(doc.get("acknowledgements"))) {
            out.append("answer ").append(a.get("segment")).append(" at step ").append(stepNumbers(doc, List.of(String.valueOf(a.get("stepId")))))
                    .append(", durable before: ").append(a.get("durableBefore")).append(" — ").append(a.get("description")).append('\n');
        }
        for (Map<String, Object> bp : Concepts.maps(doc.get("businessParts"))) {
            List<String> ids = new ArrayList<>();
            ((List<?>) bp.get("steps")).forEach(x -> ids.add(String.valueOf(x)));
            out.append("part ").append(bp.get("id")).append(' ').append(bp.get("name")).append(": steps ").append(stepNumbers(doc, ids))
                    .append(" -> ").append(bp.get("outcome")).append('\n');
        }
        return out.toString();
    }

    /** The methods around every step's evidence, then the places' own methods, each printed once. */
    private static String slices(AuditContext ctx, Map<String, Object> doc, List<Slices.Slice> placeSlices) throws Exception {
        Set<String> files = new LinkedHashSet<>();
        for (Map<String, Object> s : Concepts.maps(doc.get("steps"))) {
            files.add(String.valueOf(((Map<?, ?>) s.get("evidence")).get("file")));
        }
        ctx.slices.load(files);
        Map<String, Slices.Slice> byLabel = new LinkedHashMap<>();
        Map<String, Set<Integer>> focus = new LinkedHashMap<>();
        for (Map<String, Object> s : Concepts.maps(doc.get("steps"))) {
            Map<?, ?> ev = (Map<?, ?>) s.get("evidence");
            int line = ((Number) ev.get("line")).intValue();
            Slices.Slice sl = ctx.slices.enclosing(String.valueOf(ev.get("file")), line);
            if (sl != null) {
                byLabel.putIfAbsent(sl.label(), sl);
                focus.computeIfAbsent(sl.label(), k -> new TreeSet<>()).add(line);
            }
        }
        for (Slices.Slice sl : placeSlices) {
            if (byLabel.values().stream().noneMatch(b -> b.file().equals(sl.file()) && b.from() <= sl.from() && b.to() >= sl.to())) {
                byLabel.putIfAbsent(sl.label(), sl);
                focus.putIfAbsent(sl.label(), new TreeSet<>(List.of(sl.from())));
            }
        }
        StringBuilder out = new StringBuilder("\n## code around the steps and the places (line numbers are the file's; copy snippets verbatim from here)\n");
        byLabel.forEach((label, sl) -> out.append("--- ").append(sl.file()).append("  ").append(sl.method()).append("  lines ").append(sl.from())
                .append('-').append(sl.to()).append('\n').append(Slices.render(sl, focus.get(label))));
        return out.toString();
    }

    static String stepNumbers(Map<String, Object> doc, List<String> ids) {
        Map<String, Object> n = new LinkedHashMap<>();
        for (Map<String, Object> s : Concepts.maps(doc.get("steps"))) {
            n.put(String.valueOf(s.get("id")), s.get("n"));
        }
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            out.add(n.containsKey(id) ? n.get(id) + " (" + id + ")" : id);
        }
        return String.join(", ", out);
    }

    static Set<String> webhooks(AuditContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (Places.Input in : ctx.inputs) {
            if (in.ep().get("attrs") instanceof Map<?, ?> a && a.get("webhook") instanceof Map<?, ?> w && "WEBHOOK".equals(w.get("verdict"))) {
                out.add(in.id());
            }
        }
        return out;
    }

    static Set<String> idempotent(AuditContext ctx) {
        Set<String> out = new LinkedHashSet<>();
        for (Places.Input in : ctx.inputs) {
            if (in.effects().stream().anyMatch(e -> "idempotency-check".equals(e.get("signal")))) {
                out.add(in.id());
            }
        }
        return out;
    }

    static Map<String, Map<String, Object>> placeRows(AuditStore store) throws Exception {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Path f = store.dir.resolve("places.jsonl");
        if (Files.exists(f)) {
            for (String line : AuditStore.read(f).split("\\R")) {
                if (!line.isBlank()) {
                    Map<String, Object> r = Json.parseObject(line);
                    out.put(String.valueOf(r.get("placeId")), r);
                }
            }
        }
        return out;
    }

    static List<Map<String, Object>> findings(AuditStore store) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        Path f = store.dir.resolve("findings.jsonl");
        if (Files.exists(f)) {
            for (String line : AuditStore.read(f).split("\\R")) {
                if (!line.isBlank()) {
                    out.add(Json.parseObject(line));
                }
            }
        }
        return out;
    }

    static final String SCENARIO_FORMAT = """

            ## draft format
            Write the draft file named at the top: one JSON object per line, one line per scenario, nothing else.
            {"title":"≤60 chars","trigger":{"kind":"PROVIDER_DOWN|PROVIDER_SLOW|DB_DOWN|COMMIT_FAILS|CRASH_BEFORE_COMMIT|CRASH_AFTER_COMMIT|RESTART_DEPLOY|REDELIVERY|DUPLICATE_DELIVERY|CONCURRENT_DELIVERY|OUT_OF_ORDER|POISON_MESSAGE|TIMEOUT_RETRY|PARTIAL_FAILURE_IN_FANOUT|SECOND_REPLICA","at":"S-… (the step before or during which it hits)","detail":"≤80 chars"},"steps":[{"n":1,"stepRef":"S-… or null","happens":"≤140 chars","state":"OK|BROKEN|LOST|DUPLICATE|SKIPPED|HUNG|INJECTED"}],"brokenAt":"S-…","outcome":{"kind":"LOST|DUPLICATED|INCONSISTENT|HUNG|DELAYED|NONE","what":"≤80 chars","businessPart":"BP-…"},"because":"≤200 chars: the one causal sentence","mitigation":{"present":false,"name":"a mitigation name from the place, or what would close it","covers":"FULL|PARTIAL|NONE"},"ruleId":"rule code of the place it explains, optional","placeId":"PL-… optional","mitigationsChecked":[{"name":"<exact name>","result":"FOUND|PARTIAL|NOT_FOUND|NOT_CHECKED","where":"file:line"}],"concept":{"pattern":"a concept id from above, optional","closes":[{"change":"≤100 chars","level":"STEP|CONCEPT","steps":["S-… the bad story steps it closes"]}]},"severity":"CRITICAL|HIGH|MEDIUM|LOW","confidence":"HIGH|MEDIUM|LOW","evidence":[{"file":"path as printed","line":123,"snippet":"verbatim line","claim":"what it shows"}],"finding":{"recommendation":"the fix, concept change first","verification":["a failure-injection test"]},"filesOpened":["every file you opened"]}
            - 2 to 8 story steps; the trigger itself is a step with state INJECTED and stepRef null. brokenAt is the stepRef of a BROKEN, LOST, DUPLICATE or HUNG story step.
            - At most 6 scenarios, plus one per matched concept. A concept that does not bite here gets one scenario with outcome NONE: it is kept as considered and harmless.
            - A scenario that explains a place sets placeId and ruleId and reports every mitigation name of that place in mitigationsChecked.
            - closes lists the changes that would close it: STEP for one method, annotation, key or config value; CONCEPT for a new component or state (an outbox, an intent table, a reconciliation job). The scanner decides the scope from it.
            - Every evidence snippet must appear within two lines of its line; copy it from the code above or a file you opened.
            - Short text: the page shows the story on the diagram. A text over its limit is kept up to half as long again; past that the whole row is dropped.
            """;

    static final String VERIFY_FORMAT = """

            ## draft format
            One JSON object per line, one per scenario above:
            {"scenarioId":"SC-…","outcome":"VERIFIED|DISPROVED|DOUBT|BLOCKED","note":"≤160 chars: why","evidence":[{"file":"…","line":123,"snippet":"verbatim line","claim":"what it shows"}],"blockedBy":{"what":"≤120 chars, BLOCKED only: the defect that stops the flow earlier","evidence":[{"file":"…","line":123,"snippet":"…","claim":"…"}]},"filesOpened":["…"]}
            - VERIFIED: the story happens as written. DISPROVED: a line you cite shows it cannot (a mitigation the author missed, a misread transaction or ack); it needs that evidence. DOUBT: you could not decide.
            - BLOCKED: the story's mechanism holds, but another defect stops the flow before it today (a missing column, a failing query); blockedBy names it with evidence. It counts as verified and is shown as biting once that defect is fixed.
            - Judge only the scenario as written; a different problem belongs in the note. A defect elsewhere that stops the flow
              before the broken step does not disprove the story: decide on its mechanism as if the flow runs as the table shows.
            """;
}
