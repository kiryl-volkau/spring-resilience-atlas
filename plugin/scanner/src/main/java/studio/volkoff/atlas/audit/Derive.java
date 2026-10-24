package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.flow.History;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.Query;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.report.ReportBuilder;
import studio.volkoff.atlas.report.Risk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Rebuilds what the page and the reader consume from the stored rows: the resolved verdict per
 * place, the per-operation verdicts the report colours, the coverage ledger and the backlog.
 * Everything is derived; nothing here is edited by hand or by a model.
 */
public final class Derive {

    /** Worst first: the verdict that speaks for an operation with several places. */
    private static final List<String> SEVERITY = List.of("CONFIRMED", "UNCERTAIN", "MITIGATED", "REFUTED");

    private Derive() {
    }

    /** For the report: refreshes the derived files when the store has rows. */
    public static void refresh(Path repo, Path atlasDir, Path auditDir) throws Exception {
        AuditStore store = new AuditStore(auditDir);
        if (store.hasRows()) {
            run(AuditContext.open(repo, atlasDir), store);
        }
    }

    static void run(AuditContext ctx, AuditStore store) throws Exception {
        List<Map<String, Object>> rows = store.rows();
        Set<String> withRows = new LinkedHashSet<>();
        Set<String> retired = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.get("placeId"));
            (ctx.placeById.containsKey(id) ? withRows : retired).add(id);
        }
        Map<String, String> cores = new LinkedHashMap<>();
        for (String id : withRows) {
            cores.put(id, Fingerprint.core(ctx, Packet.view(ctx, ctx.placeById.get(id))));
        }
        Map<String, AuditStore.Resolved> resolved = AuditStore.resolve(rows, cores, ctx.repo);
        // HIGH and MEDIUM places always count; a LOW or no-hint place counts once someone chose to check it.
        Set<String> counted = new LinkedHashSet<>();
        for (Places.Place p : ctx.places.places()) {
            if (p.defaultScope() || resolved.containsKey(p.id())) {
                counted.add(p.id());
            }
        }

        List<Map<String, Object>> placeRows = new ArrayList<>();
        for (Places.Place p : ctx.places.places()) {
            AuditStore.Resolved r = resolved.get(p.id());
            if (r == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("placeId", p.id());
            m.put("rule", p.code());
            m.put("file", p.file());
            m.put("symbol", p.symbol());
            m.put("level", p.level().name());
            m.put("verdict", r.verdict());
            m.put("confidence", r.row().get("confidence"));
            m.put("status", r.status());
            m.put("tier", r.row().get("tier"));
            m.put("summary", r.row().get("summary"));
            if (r.status().equals("UNJUDGED")) {
                m.put("reason", r.row().get("reason"));
            }
            for (String k : List.of("needs", "remaining", "foundRule", "draftVerdict", "downgraded")) {
                if (r.row().get(k) != null) {
                    m.put(k, r.row().get(k));
                }
            }
            for (String k : List.of("flowId", "findingIds", "scenarioIds")) {
                if (r.row().get(k) != null) {
                    m.put(k, r.row().get(k));
                }
            }
            m.put("batch", r.row().get("batch"));
            m.put("model", r.row().get("model"));
            placeRows.add(m);
        }
        store.writeJsonl("places.jsonl", placeRows);

        Map<String, Map<String, Object>> previous = new LinkedHashMap<>();
        Path oldVerdicts = store.dir.resolve("verdicts.jsonl");
        if (Files.exists(oldVerdicts)) {
            for (String line : AuditStore.read(oldVerdicts).split("\\R")) {
                if (!line.isBlank()) {
                    Map<String, Object> v = Json.parseObject(line);
                    previous.put(String.valueOf(v.get("id")), v);
                }
            }
        }
        List<Map<String, Object>> verdicts = new ArrayList<>();
        int covered = 0;
        int withHint = 0;
        for (Places.Input in : ctx.inputs) {
            if (ctx.placeIdsOf(in.id()).stream().anyMatch(id -> ctx.placeById.get(id).defaultScope())) {
                withHint++;
            }
            List<String> ids = ctx.placeIdsOf(in.id()).stream().filter(counted::contains).toList();
            if (ids.isEmpty()) {
                continue;
            }
            List<AuditStore.Resolved> judged = new ArrayList<>();
            boolean stale = false;
            int current = 0;
            for (String id : ids) {
                AuditStore.Resolved r = resolved.get(id);
                if (r == null || r.status().equals("UNJUDGED")) {
                    continue;
                }
                judged.add(r);
                if (r.status().equals("STALE")) {
                    stale = true;
                } else {
                    current++;
                }
            }
            if (judged.isEmpty()) {
                continue;
            }
            if (current == ids.size()) {
                covered++;
            }
            AuditStore.Resolved worst = judged.get(0);
            for (AuditStore.Resolved r : judged) {
                if (SEVERITY.indexOf(r.verdict()) < SEVERITY.indexOf(worst.verdict())) {
                    worst = r;
                }
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", in.id());
            v.put("tier", "TRIAGE");
            v.put("triage", true);
            v.put("status", stale ? "STALE" : "CURRENT");
            String colour;
            String summary;
            String checked = current + " of " + ids.size() + " places checked";
            if (judged.stream().anyMatch(r -> "CONFIRMED".equals(r.verdict()))) {
                // One confirmed place is enough: an unchecked one cannot make the operation safe.
                colour = "RED";
                summary = (current < ids.size() ? checked + "; " : "") + worst.row().get("summary");
            } else if (current < ids.size()) {
                colour = "GREY";
                summary = checked + (worst.row().get("summary") != null ? "; " + worst.row().get("summary") : "");
            } else if (judged.stream().anyMatch(r -> "UNCERTAIN".equals(r.verdict()))) {
                colour = "GREY";
                Object needs = openQuestion(worst.row());
                summary = "uncertain" + (needs != null ? ": needs " + needs : "") + "; " + worst.row().get("summary");
            } else {
                colour = "GREEN";
                summary = String.valueOf(worst.row().get("summary"));
            }
            v.put("colour", colour);
            Map<String, Object> old = previous.get(in.id());
            if (old != null) {
                Object prev = "STALE".equals(old.get("status")) ? old.get("previousColour") : old.get("colour");
                if (prev != null) {
                    v.put("previousColour", prev);
                }
            }
            v.put("confidence", minConfidence(judged));
            v.put("summary", summary);
            v.put("placeIds", ids);
            v.put("model", worst.row().get("model"));
            List<AuditStore.Resolved> deep = judged.stream().filter(r -> !"TRIAGE".equals(r.row().get("tier"))).toList();
            if (!deep.isEmpty()) {
                deepVerdict(v, deep, judged, ids.size() - current);
            }
            verdicts.add(v);
        }
        store.writeJsonl("verdicts.jsonl", verdicts);

        Map<String, Object> plan = store.plan();
        store.write(store.dir.resolve("coverage.json"), Json.write(coverage(ctx, store, resolved, retired, counted, plan, withHint, covered)) + "\n");
        writeBacklog(ctx, store, resolved, counted);
    }

    /**
     * An operation with a deep row: its colour comes from the findings, so it states none unless a
     * place only the quick check judged still suspects something (YELLOW) or a place is unchecked (GREY).
     */
    private static void deepVerdict(Map<String, Object> v, List<AuditStore.Resolved> deep, List<AuditStore.Resolved> judged, int unchecked) {
        String tier = deep.stream().map(r -> String.valueOf(r.row().get("tier"))).max(java.util.Comparator.comparingInt(AuditStore.TIERS::indexOf)).orElseThrow();
        AuditStore.Resolved worst = deep.stream().filter(r -> "CONFIRMED".equals(r.verdict())).findFirst().orElse(deep.get(0));
        Set<Object> findingIds = new LinkedHashSet<>();
        for (AuditStore.Resolved r : deep) {
            if (r.row().get("findingIds") instanceof List<?> l) {
                findingIds.addAll(l);
            }
        }
        long quickSuspects = judged.stream().filter(r -> "TRIAGE".equals(r.row().get("tier"))
                && ("CONFIRMED".equals(r.verdict()) || "UNCERTAIN".equals(r.verdict()))).count();
        v.put("tier", tier);
        v.remove("triage");
        v.remove("colour");
        if (quickSuspects > 0) {
            v.put("colour", "YELLOW");
        } else if (unchecked > 0) {
            v.put("colour", "GREY");
        }
        v.put("summary", worst.row().get("summary") + (quickSuspects > 0 ? "; " + quickSuspects + (quickSuspects == 1 ? " place" : " places")
                + " only quick-checked" : "") + (unchecked > 0 ? "; " + unchecked + " not checked" : ""));
        v.put("confidence", worst.row().get("confidence"));
        v.put("findingIds", new ArrayList<>(findingIds));
        v.put("flowId", worst.row().get("flowId"));
    }

    /** What an uncertain verdict waits for: its {@code needs}, else what its check could not rule out. */
    private static Object openQuestion(Map<String, Object> row) {
        Object needs = row.get("needs") instanceof Map<?, ?> n ? n.get("what") : null;
        return needs != null ? needs : row.get("remaining");
    }

    /** Operations without a hint by what their path shows: nothing that changes state, only their own database, or more. */
    static Map<String, Integer> unhinted(AuditContext ctx) {
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("readOnly", 0);
        out.put("ownDatabase", 0);
        out.put("other", 0);
        for (Places.Input in : ctx.inputs) {
            Map<String, Object> fx = ctx.effectRowById.get(in.id());
            if (fx == null || !Boolean.TRUE.equals(fx.get("analysed")) || !in.assessment().reasons().isEmpty()) {
                continue;
            }
            Set<String> signals = Places.stateSignals(in.effects());
            String bucket = signals.isEmpty() ? "readOnly" : signals.equals(Set.of("db-write")) ? "ownDatabase" : "other";
            out.merge(bucket, 1, Integer::sum);
        }
        return out;
    }

    private static Map<String, Object> coverage(AuditContext ctx, AuditStore store, Map<String, AuditStore.Resolved> resolved,
                                                Set<String> retired, Set<String> counted, Map<String, Object> plan, int withHint,
                                                int covered) throws Exception {
        Map<String, Integer> places = new LinkedHashMap<>();
        for (String k : List.of("inScope", "judged", "confirmed", "refuted", "mitigated", "uncertain", "unjudged", "stale", "retired",
                "downgraded", "overBudget")) {
            places.put(k, 0);
        }
        places.put("inScope", counted.size());
        places.put("retired", retired.size());
        for (AuditStore.Resolved r : resolved.values()) {
            switch (r.status()) {
                case "CURRENT" -> {
                    places.merge("judged", 1, Integer::sum);
                    places.merge(r.verdict().toLowerCase(java.util.Locale.ROOT), 1, Integer::sum);
                    if (r.row().get("downgraded") != null) {
                        places.merge("downgraded", 1, Integer::sum);
                    }
                    if (r.row().get("overBudget") != null) {
                        places.merge("overBudget", 1, Integer::sum);
                    }
                }
                case "STALE" -> places.merge("stale", 1, Integer::sum);
                default -> places.merge("unjudged", 1, Integer::sum);
            }
        }
        int notAnalysed = 0;
        int lowOnly = 0;
        int lowUnchecked = 0;
        int sweepable = 0;
        int sweepUnchecked = 0;
        for (Places.Input in : ctx.inputs) {
            Map<String, Object> fx = ctx.effectRowById.get(in.id());
            if (fx == null || !Boolean.TRUE.equals(fx.get("analysed"))) {
                notAnalysed++;
                continue;
            }
            List<Places.Place> mine = ctx.placeIdsOf(in.id()).stream().map(ctx.placeById::get).toList();
            if (mine.isEmpty() || mine.stream().anyMatch(Places.Place::defaultScope)) {
                continue;
            }
            boolean sweep = mine.stream().anyMatch(p -> p.code().equals(Places.NO_HINT));
            boolean unchecked = mine.stream().anyMatch(p -> !counted.contains(p.id()));
            if (sweep) {
                sweepable++;
                sweepUnchecked += unchecked ? 1 : 0;
            } else {
                lowOnly++;
                lowUnchecked += unchecked ? 1 : 0;
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        boolean complete = places.get("inScope") > 0 && places.get("judged").equals(places.get("inScope"));
        c.put("status", complete ? "COMPLETE" : "PARTIAL");
        c.put("commit", ctx.manifestString("commit"));
        Map<String, Object> budget = new LinkedHashMap<>();
        if (plan != null) {
            budget.put("usdSpent", plan.get("spent"));
            budget.put("usdCap", plan.get("maxUsd"));
            budget.put("estimated", true);
            if (plan.get("stoppedBecause") != null) {
                budget.put("stoppedBecause", switch (String.valueOf(plan.get("stoppedBecause"))) {
                    case "usd" -> "stopped at the spending cap";
                    case "waves" -> "stopped after the plan's waves";
                    default -> "every batch of the plan was issued";
                });
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", plan.get("id"));
            p.put("filters", plan.get("filters"));
            p.put("batchSize", plan.get("batchSize"));
            p.put("model", plan.get("modelId"));
            c.put("plan", p);
        }
        c.put("budget", budget);
        c.put("places", places);
        Map<String, Object> ops = new LinkedHashMap<>();
        ops.put("total", ctx.inputs.size());
        ops.put("notAnalysed", notAnalysed);
        ops.put("withHint", withHint);
        ops.put("covered", covered);
        ops.put("lowOnly", lowOnly);
        ops.put("noHint", unhinted(ctx));
        c.put("operations", ops);

        Map<String, Map<String, Object>> estimates = new LinkedHashMap<>();
        List<String> pending = new ArrayList<>();
        if (plan != null) {
            for (Map<String, Object> b : Plan.maps(plan.get("batches"))) {
                estimates.put(String.valueOf(b.get("id")), b);
                if (!"RECORDED".equals(b.get("status"))) {
                    for (Object pid : (List<?>) b.get("places")) {
                        Places.Place p = ctx.placeById.get(String.valueOf(pid));
                        if (p != null) {
                            for (String op : p.ops()) {
                                if (!pending.contains(op)) {
                                    pending.add(op);
                                }
                            }
                        }
                    }
                }
            }
        }
        Map<String, int[]> perBatch = new TreeMap<>();
        Map<String, String> modelOf = new LinkedHashMap<>();
        for (Map<String, Object> r : store.rows()) {
            if (r.get("tier") != null && !"TRIAGE".equals(r.get("tier"))) {
                continue;
            }
            String b = String.valueOf(r.get("batch"));
            int[] n = perBatch.computeIfAbsent(b, k -> new int[2]);
            n["UNJUDGED".equals(r.get("status")) ? 1 : 0]++;
            modelOf.put(b, String.valueOf(r.get("model")));
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        perBatch.forEach((b, n) -> {
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("id", b);
            run.put("tier", "TRIAGE");
            run.put("agent", "place-checker");
            run.put("model", modelOf.get(b));
            Map<String, Object> est = estimates.get(b);
            run.put("usd", est == null ? null : ((Map<?, ?>) est.get("estimate")).get("usd"));
            run.put("status", n[1] == 0 ? "RECORDED" : n[0] == 0 ? "UNJUDGED" : "PARTIAL");
            run.put("reason", n[0] + " judged" + (n[1] > 0 ? ", " + n[1] + " unjudged" : ""));
            runs.add(run);
        });
        if (plan != null && "deep".equals(plan.get("kind"))) {
            for (Map<String, Object> j : Plan.maps(plan.get("jobs"))) {
                for (Map<String, Object> s : Plan.maps(j.get("steps"))) {
                    if (!"RECORDED".equals(s.get("status")) && !"UNJUDGED".equals(s.get("status"))) {
                        continue;
                    }
                    Map<String, Object> run = new LinkedHashMap<>();
                    run.put("id", j.get("batch") + "-" + String.valueOf(s.get("name")).toLowerCase(java.util.Locale.ROOT));
                    run.put("tier", "DEEP");
                    run.put("agent", String.valueOf(s.get("agent")).replace("resilience-audit:", ""));
                    run.put("model", s.get("modelId"));
                    run.put("usd", ((Map<?, ?>) s.get("estimate")).get("usd"));
                    run.put("status", s.get("status"));
                    run.put("reason", j.get("flowId") + ":" + s.get("name") + (s.get("reason") != null ? " " + s.get("reason") : ""));
                    runs.add(run);
                }
            }
        }
        c.put("runs", runs);
        c.put("pending", pending);
        c.put("deep", deepCounts(store));

        List<String> limits = new ArrayList<>();
        if (ctx.atlas.manifest.get("warnings") instanceof List<?> w) {
            w.forEach(x -> limits.add(String.valueOf(x)));
        }
        Map<String, Integer> noHint = unhinted(ctx);
        if (lowUnchecked > 0) {
            limits.add(lowUnchecked + " of " + lowOnly + " operations with only LOW hints were not checked (--level LOW adds them)");
        }
        if (sweepUnchecked > 0) {
            limits.add(sweepUnchecked + " of " + sweepable + " operations without a hint that still write, send or call were not swept (--level NONE adds them)");
        }
        limits.add(noHint.get("readOnly") + " operations without a hint change nothing on the path the scan read; the quick check has nothing to ask about them");
        limits.add(notAnalysed + " operations not analysed: Kotlin, Groovy or unparsed code, not checkable by the quick check");
        c.put("limits", limits);
        return c;
    }

    /** Flows with scenarios and how their scenarios and findings stand. */
    private static Map<String, Object> deepCounts(AuditStore store) throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        int flows = 0;
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String k : List.of("CANDIDATE", "VERIFIED", "DISPROVED", "HARMLESS")) {
            byStatus.put(k, 0);
        }
        Path dir = store.dir.resolve("scenarios");
        if (Files.isDirectory(dir)) {
            try (java.util.stream.Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(x -> x.toString().endsWith(".jsonl")).sorted().toList()) {
                    flows++;
                    for (Map<String, Object> sc : studio.volkoff.atlas.query.AtlasStore.readJsonl(f)) {
                        byStatus.merge(String.valueOf(sc.get("status")), 1, Integer::sum);
                    }
                }
            }
        }
        out.put("flows", flows);
        out.put("scenarios", byStatus.values().stream().mapToInt(Integer::intValue).sum());
        byStatus.forEach((k, n) -> out.put(k.toLowerCase(java.util.Locale.ROOT), n));
        out.put("findings", DeepPacket.findings(store).stream().filter(f -> f.get("scenarioIds") != null).count());
        return out;
    }

    private static String minConfidence(List<AuditStore.Resolved> judged) {
        List<String> order = List.of("LOW", "MEDIUM", "HIGH");
        String min = "HIGH";
        for (AuditStore.Resolved r : judged) {
            String c = String.valueOf(r.row().get("confidence"));
            if (order.indexOf(c) >= 0 && order.indexOf(c) < order.indexOf(min)) {
                min = c;
            }
        }
        return min;
    }

    @SuppressWarnings("unchecked")
    private static void writeBacklog(AuditContext ctx, AuditStore store, Map<String, AuditStore.Resolved> resolved, Set<String> counted)
            throws Exception {
        Map<String, String> areas = ReportBuilder.areas(ctx.atlas);
        Map<String, List<Object>> tasksByArea = new LinkedHashMap<>();
        List<Places.Place> uncertain = new ArrayList<>();
        List<Places.Place> cleared = new ArrayList<>();
        Map<String, Map<String, Object>> findings = new LinkedHashMap<>();
        for (Map<String, Object> f : DeepPacket.findings(store)) {
            if (f.get("scenarioIds") != null) {
                findings.put(String.valueOf(f.get("id")), f);
            }
        }
        Map<String, Map<String, Object>> scenarios = new LinkedHashMap<>();
        for (Map<String, Object> f : findings.values()) {
            for (Map<String, Object> sc : ScenarioRecord.stored(store, String.valueOf(f.get("flowId")))) {
                scenarios.put(String.valueOf(sc.get("id")), sc);
            }
        }
        Map<String, Object> since = findings.isEmpty() ? null : History.since(new Flows(ctx.repo, ctx.atlasDir, store.dir,
                store.dir.toAbsolutePath().getParent().resolve("flows")));
        Map<String, String> tags = new LinkedHashMap<>();
        String sinceDate = null;
        if (since != null) {
            sinceDate = String.valueOf(((Map<?, ?>) since.get("from")).get("takenAt")).substring(0, 10);
            Map<?, ?> buckets = (Map<?, ?>) since.get("findings");
            for (Map<String, Object> f : Plan.maps(buckets.get("new"))) {
                tags.put(String.valueOf(f.get("id")), "new since " + sinceDate);
            }
            for (Map<String, Object> f : Plan.maps(buckets.get("notRechecked"))) {
                tags.put(String.valueOf(f.get("id")), "changed since " + sinceDate + ", not audited again");
            }
        }
        Set<String> filed = new LinkedHashSet<>();
        List<Map<String, Object>> doubtful = new ArrayList<>();
        List<Map<String, Object>> disproved = new ArrayList<>();
        int checked = 0;
        String quickModel = null;
        String deepModel = null;
        for (Places.Place p : ctx.places.places()) {
            AuditStore.Resolved r = resolved.get(p.id());
            if (r == null || !r.status().equals("CURRENT")) {
                continue;
            }
            checked++;
            if ("TRIAGE".equals(r.row().get("tier"))) {
                quickModel = String.valueOf(r.row().get("model"));
            } else {
                deepModel = String.valueOf(r.row().get("model"));
            }
            String area = areas.getOrDefault(p.ops().get(0), "(root)");
            List<Map<String, Object>> mine = new ArrayList<>();
            if (!"TRIAGE".equals(r.row().get("tier")) && r.row().get("findingIds") instanceof List<?> ids) {
                ids.forEach(id -> {
                    if (findings.containsKey(String.valueOf(id))) {
                        mine.add(findings.get(String.valueOf(id)));
                    }
                });
            }
            if (!mine.isEmpty()) {
                for (Map<String, Object> f : mine) {
                    if (filed.add(String.valueOf(f.get("id")))) {
                        fileFinding(f, area, tasksByArea, doubtful, disproved);
                    }
                }
                continue;
            }
            switch (r.verdict()) {
                case "CONFIRMED" -> tasksByArea.computeIfAbsent(area, k -> new ArrayList<>()).add(p);
                case "UNCERTAIN" -> uncertain.add(p);
                default -> cleared.add(p);
            }
        }
        for (Map<String, Object> f : findings.values()) {
            if (filed.add(String.valueOf(f.get("id")))) {
                fileFinding(f, areas.getOrDefault(String.valueOf(f.get("operationId")), "(root)"), tasksByArea, doubtful, disproved);
            }
        }
        int notAnalysed = 0;
        int unchecked = 0;
        for (Places.Input in : ctx.inputs) {
            Map<String, Object> fx = ctx.effectRowById.get(in.id());
            if (fx == null || !Boolean.TRUE.equals(fx.get("analysed"))) {
                notAnalysed++;
            } else if (ctx.placeIdsOf(in.id()).stream().noneMatch(counted::contains)) {
                unchecked++;
            }
        }
        int tasks = tasksByArea.values().stream().mapToInt(List::size).sum();
        String commit = ctx.manifestString("commit");
        StringBuilder md = new StringBuilder();
        md.append("# Reliability backlog · ").append(ctx.repo.getFileName()).append(commit == null ? "" : " @" + commit.substring(0, Math.min(7, commit.length())))
                .append(" · ").append(LocalDate.now(ZoneOffset.UTC)).append("\n\n");
        md.append(tasks).append(tasks == 1 ? " task" : " tasks").append(" from ").append(checked).append(" of ").append(counted.size())
                .append(" places checked").append(checkedBy(quickModel, deepModel)).append(".\n");
        md.append("Not covered: ").append(unchecked).append(" operations with no checked place (LOW hints and operations without a hint are checked only on request); ")
                .append(notAnalysed).append(" operations not analysed (Kotlin, Groovy, unparsed); places not checked yet: ")
                .append(counted.size() - checked).append(".\n");
        md.append("Severity says how bad it is if true; confidence says how sure the check is. SUSPECTED means the quick check found the pattern and no mitigation; a deep audit has not verified it.");
        md.append(findings.isEmpty() ? "\n" : " VERIFIED means a deep audit wrote the failure story on the traced flow and a second model could not disprove it.\n");
        int rank = 1;
        for (Map.Entry<String, List<Object>> area : tasksByArea.entrySet()) {
            md.append("\n## ").append(area.getKey()).append('\n');
            for (Object o : area.getValue()) {
                md.append('\n').append(o instanceof Places.Place p ? task(ctx, p, resolved.get(p.id()).row(), rank++)
                        : deepTask(ctx, (Map<String, Object>) o, scenarios, tags, rank++));
            }
        }
        if (!uncertain.isEmpty() || !doubtful.isEmpty()) {
            md.append("\n## Needs a closer look\n\n");
            for (Map<String, Object> f : doubtful) {
                md.append("- ").append(f.get("id")).append(" · ").append(f.get("title")).append(" (").append(f.get("flowId")).append(", ")
                        .append(f.get("severity")).append(", confidence ").append(f.get("confidence")).append("): ").append(f.get("failureScenario")).append('\n');
            }
            for (Places.Place p : uncertain) {
                Map<String, Object> row = resolved.get(p.id()).row();
                Object needs = openQuestion(row);
                md.append("- ").append(p.id()).append(" · ").append(p.title()).append(" at `").append(p.symbol()).append("`: ")
                        .append(row.get("summary")).append(needs != null ? " Needs: " + needs + "." : "").append('\n');
            }
        }
        if (!cleared.isEmpty() || !disproved.isEmpty()) {
            md.append("\n## Checked and found mitigated or refuted\n\n");
            for (Map<String, Object> f : disproved) {
                Object note = f.get("verifiedBy") instanceof Map<?, ?> v ? v.get("note") : null;
                md.append("- ").append(f.get("id")).append(" · ").append(f.get("title")).append(" (").append(f.get("flowId")).append("): disproved")
                        .append(note == null ? "" : ". " + note).append('\n');
            }
            for (Places.Place p : cleared) {
                Map<String, Object> row = resolved.get(p.id()).row();
                md.append("- ").append(p.id()).append(" · ").append(p.title()).append(" at `").append(p.symbol()).append("`: ")
                        .append(String.valueOf(row.get("verdict")).toLowerCase(java.util.Locale.ROOT)).append(". ").append(row.get("summary")).append('\n');
            }
        }
        if (since != null && !Plan.maps(((Map<?, ?>) since.get("findings")).get("fixed")).isEmpty()) {
            md.append("\n## Fixed since ").append(sinceDate).append("\n\n");
            for (Map<String, Object> f : Plan.maps(((Map<?, ?>) since.get("findings")).get("fixed"))) {
                md.append("- ").append(f.get("id")).append(" · ").append(f.get("title")).append(" (").append(f.get("flowId"))
                        .append("): a later deep audit of the changed code found no such story\n");
            }
        }
        AuditStore.write(store.dir.toAbsolutePath().getParent().resolve("backlog.md"), md.toString());
    }

    private static String checkedBy(String quickModel, String deepModel) {
        List<String> by = new ArrayList<>();
        if (quickModel != null) {
            by.add("quick check on " + quickModel);
        }
        if (deepModel != null) {
            by.add("deep audit by " + deepModel);
        }
        return by.isEmpty() ? "" : " (" + String.join("; ", by) + ")";
    }

    /** A verified finding, or a HIGH-confidence one, is a task; a disproved one is cleared; any other waits for a closer look. */
    private static void fileFinding(Map<String, Object> f, String area, Map<String, List<Object>> tasks, List<Map<String, Object>> doubtful,
                                    List<Map<String, Object>> disproved) {
        String status = String.valueOf(f.get("status"));
        if (status.equals("DISPROVED") || status.equals("DUPLICATE")) {
            disproved.add(f);
        } else if (status.equals("VERIFIED") || "HIGH".equals(f.get("confidence"))) {
            tasks.computeIfAbsent(area, k -> new ArrayList<>()).add(f);
        } else {
            doubtful.add(f);
        }
    }

    private static String deepTask(AuditContext ctx, Map<String, Object> f, Map<String, Map<String, Object>> scenarios, Map<String, String> tags,
                                   int rank) {
        Map<?, ?> concept = f.get("concept") instanceof Map<?, ?> c ? c : Map.of();
        Map<String, Object> lead = f.get("scenarioIds") instanceof List<?> ids && !ids.isEmpty() ? scenarios.get(String.valueOf(ids.get(0))) : null;
        boolean design = "CONCEPT".equals(concept.get("scope"));
        StringBuilder t = new StringBuilder();
        t.append("### ").append(rank).append(" · ").append(f.get("id")).append(" · ").append(f.get("title")).append('\n');
        t.append(Risk.RULES.containsKey(String.valueOf(f.get("ruleId"))) ? "rule `" : "concept `").append(f.get("ruleId")).append('`')
                .append(" · severity ").append(f.get("severity")).append(" · confidence ").append(f.get("confidence"))
                .append(" · status ").append("VERIFIED".equals(f.get("status")) ? "VERIFIED" : "SUSPECTED (deep audit, not verified)")
                .append(" · ").append(design ? "Design-level" : "Step-level")
                .append(tags.containsKey(String.valueOf(f.get("id"))) ? " · " + tags.get(String.valueOf(f.get("id"))) : "")
                .append(Boolean.TRUE.equals(f.get("notRejudged")) ? " · kept from an earlier run" : "").append('\n');
        String brokenAt = lead == null || lead.get("brokenAt") == null ? null : String.valueOf(lead.get("brokenAt"));
        t.append("Flow: [").append(f.get("flowId")).append("](flows/").append(f.get("flowId")).append(".html").append(brokenAt == null ? "" : "#" + brokenAt)
                .append("), steps ").append(String.join(", ", ((List<?>) f.get("stepIds")).stream().map(String::valueOf).toList())).append('\n');
        Places.Place p = ctx.placeById.get(String.valueOf(f.get("placeId")));
        if (p != null) {
            t.append("Place: ").append(p.id()).append(" `").append(p.symbol()).append("` in ").append(p.file()).append('\n');
        }
        if (lead != null) {
            t.append("Story:\n");
            for (Map<String, Object> s : Plan.maps(lead.get("steps"))) {
                t.append(s.get("n")).append(". ").append(s.get("happens")).append(" [").append(s.get("state")).append("]\n");
            }
            Map<?, ?> o = (Map<?, ?>) lead.get("outcome");
            t.append("→ ").append(o.get("kind")).append(": ").append(o.get("what")).append('\n');
            t.append("Because: ").append(lead.get("because")).append('\n');
        } else {
            t.append("Failure: ").append(f.get("failureScenario")).append('\n');
        }
        if (f.get("evidence") instanceof List<?> ev && !ev.isEmpty()) {
            t.append("Evidence:\n");
            for (Object x : ev) {
                Map<?, ?> e = (Map<?, ?>) x;
                t.append("- ").append(e.get("file")).append(':').append(e.get("line")).append(" — `").append(String.valueOf(e.get("snippet")).strip()).append('`')
                        .append(e.get("claim") != null ? " — " + e.get("claim") : "").append('\n');
            }
        }
        List<String> found = new ArrayList<>();
        for (Map<String, Object> m : Plan.maps(f.get("mitigationsChecked"))) {
            found.add(m.get("name") + " — " + m.get("result"));
        }
        if (lead != null && lead.get("mitigation") instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("present"))) {
            found.add(0, m.get("name") + " — present, covers " + m.get("covers"));
        }
        t.append("Existing mitigations: ").append(found.isEmpty() ? "none found" : String.join("; ", found)).append('\n');
        t.append("Fix: ").append(f.get("recommendation")).append('\n');
        List<Map<String, Object>> closes = new ArrayList<>();
        if (lead != null && lead.get("concept") instanceof Map<?, ?> lc) {
            closes.addAll(Plan.maps(lc.get("closes")));
        }
        // Design-level: the concept change first, the step changes only as interim measures.
        closes.sort(java.util.Comparator.comparingInt((Map<String, Object> x) -> design == "CONCEPT".equals(x.get("level")) ? 0 : 1));
        for (Map<String, Object> x : closes) {
            boolean interim = design && "STEP".equals(x.get("level"));
            t.append("- ").append(interim ? "interim, " : "").append(String.valueOf(x.get("level")).toLowerCase(java.util.Locale.ROOT)).append(": ")
                    .append(x.get("change")).append('\n');
        }
        t.append("Verify: ").append(String.join(" ", ((List<?>) f.get("verification")).stream().map(String::valueOf).toList())).append('\n');
        if (f.get("blockedBy") instanceof Map<?, ?> b) {
            t.append("Blocked for now: ").append(b.get("what")).append("; the story happens once that is fixed\n");
        }
        if (f.get("verifiedBy") instanceof Map<?, ?> v) {
            t.append("Checked by ").append(v.get("model")).append(": ").append(v.get("outcome")).append(". ").append(v.get("note")).append('\n');
        }
        return t.toString();
    }

    private static String task(AuditContext ctx, Places.Place p, Map<String, Object> row, int rank) {
        // A sweep that found something is filed under the rule it named.
        String code = p.code().equals(Places.NO_HINT) && ctx.hypotheses.containsKey(String.valueOf(row.get("foundRule"))) ? String.valueOf(row.get("foundRule")) : p.code();
        Hypotheses.Hypothesis h = ctx.hypotheses.get(code);
        String title = Risk.RULES.get(code)[0];
        StringBuilder t = new StringBuilder();
        t.append("### ").append(rank).append(" · ").append(p.id()).append(" · ").append(title).append('\n');
        t.append("rule `").append(code).append('`').append(code.equals(p.code()) ? "" : " (found by a sweep; no rule fired)")
                .append(" · severity ").append(p.level() == Risk.Level.NONE ? "unrated" : p.level())
                .append(" · confidence ").append(row.get("confidence")).append(" · status SUSPECTED\n");
        if (row.get("downgraded") != null) {
            t.append("Recorded as suspected although the check said ").append(row.get("draftVerdict")).append(": ").append(row.get("downgraded")).append('\n');
        }
        t.append(Places.TX_RULES.contains(p.code()) ? "Transaction opened in: `" : "Place: `").append(p.symbol()).append("` in ").append(p.file()).append('\n');
        t.append("Reached from ").append(p.ops().size()).append(p.ops().size() == 1 ? " operation: " : " operations: ");
        List<String> ops = new ArrayList<>();
        for (String op : p.ops().subList(0, Math.min(5, p.ops().size()))) {
            Map<String, Object> ep = ctx.inputById.get(op).ep();
            ops.add("`" + ep.get("subkind") + " " + Query.target(ep) + "` (" + Query.symbol(String.valueOf(ep.get("cls")), String.valueOf(ep.get("method"))) + ")");
        }
        t.append(String.join(", ", ops)).append(p.ops().size() > 5 ? ", +" + (p.ops().size() - 5) + " more" : "").append('\n');
        if (row.get("evidence") instanceof List<?> ev && !ev.isEmpty()) {
            t.append("Evidence:\n");
            for (Object o : ev) {
                Map<?, ?> e = (Map<?, ?>) o;
                t.append("- ").append(e.get("file")).append(':').append(e.get("line")).append(" — `").append(String.valueOf(e.get("snippet")).strip()).append('`');
                if (e.get("claim") != null) {
                    t.append(" — ").append(e.get("claim"));
                }
                t.append('\n');
            }
        }
        List<String> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        if (row.get("mitigationsChecked") instanceof List<?> mc) {
            for (Object o : mc) {
                Map<?, ?> m = (Map<?, ?>) o;
                String result = String.valueOf(m.get("result"));
                (result.equals("FOUND") || result.equals("PARTIAL") ? found : notFound)
                        .add(m.get("name") + " — " + result + (result.equals("PARTIAL") && m.get("where") != null ? " (" + m.get("where") + ")" : ""));
            }
        }
        t.append("Existing mitigations: ").append(found.isEmpty() ? "none found" : String.join("; ", found))
                .append(notFound.isEmpty() ? "" : " (" + String.join("; ", notFound) + ")").append('\n');
        t.append("Failure: ").append(row.get("summary")).append('\n');
        if (row.get("remaining") != null) {
            t.append("Still open: ").append(row.get("remaining")).append('\n');
        }
        t.append("Fix: ").append(h.fix()).append('\n');
        t.append("Verify: ").append(h.verify()).append('\n');
        return t.toString();
    }
}
