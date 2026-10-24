package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.FlowPage;
import studio.volkoff.atlas.flow.FlowRecord;
import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.flow.History;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.Args;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.scan.Scanner;
import studio.volkoff.atlas.scan.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit deep plan|next} and {@code audit record-step}: the deep audit of whole flows.
 * One plan at a time, gated like the quick check: the plan prints its estimate and spends
 * nothing; {@code next} issues one step of one flow job (trace, scenarios, verify), counting its
 * estimate as spent; {@code record-step} stores what the step's agent wrote, never failing for a
 * bad draft.
 */
final class Deep {

    static final List<String> STEPS = List.of("TRACE", "SCENARIOS", "VERIFY");
    private static final Map<String, String> AGENTS = Map.of("TRACE", "resilience-audit:flow-tracer",
            "SCENARIOS", "resilience-audit:scenario-author", "VERIFY", "resilience-audit:flow-verifier");
    private static final int DEFAULT_MAX_FLOWS = 3;
    private static final int DEFAULT_TOP = 5;
    private static final int DEFAULT_WAVES = 12;
    private static final int MAX_SEGMENTS = 4;
    /** Assumed sizes until runs measure them: a trace reads about a dozen files over many turns. */
    private static final long TRACE_FRESH = 70_000;
    private static final long TRACE_CACHED = 700_000;
    private static final long TRACE_OUT = 16_000;
    private static final long AGENT_BASE = 25_000;
    /**
     * Fitted to two runs on Fable (2026-10-24): a 19-step flow wrote 85k tokens to the cache and 33k
     * out, a 33-step flow 130k and 43k, thinking included.
     */
    private static final long SCENARIO_READ = 70_000;
    private static final long SCENARIO_OUT = 40_000;
    private static final long VERIFY_READ = 15_000;
    private static final long VERIFY_TURNS = 6;
    private static final long VERIFY_OUT = 8_000;
    private static final int DEFAULT_PACKET_TOKENS = 35_000;

    private Deep() {
    }

    static String plan(Path repo, Path atlasDir, AuditStore store, Flows flows, Args args) throws Exception {
        StringBuilder out = new StringBuilder();
        String treeHash = Scanner.sourcesHash(Workspace.load(repo));
        Map<String, Object> manifest = Files.exists(atlasDir.resolve("manifest.json")) ? Json.parseObject(AuditStore.read(atlasDir.resolve("manifest.json"))) : null;
        if (manifest == null || !treeHash.equals(manifest.get("sourcesHash")) || !Files.exists(atlasDir.resolve("links.jsonl"))) {
            new Scanner().scan(repo, atlasDir);
            out.append("# rescanned: ").append(manifest == null ? "no atlas yet" : "the atlas was older than the sources").append('\n');
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        int closed = Record.closeUnrecorded(ctx, store, store.plan());
        if (closed > 0) {
            out.append("# closed ").append(closed).append(closed == 1 ? " batch" : " batches").append(" of the previous plan that were issued and never recorded\n");
        }
        Map<String, Map<String, Object>> recorded = flows.all();
        Map<String, Boolean> stale = new LinkedHashMap<>();
        Slices slices = new Slices(repo);
        for (Map.Entry<String, Map<String, Object>> f : recorded.entrySet()) {
            if (Flows.v2(f.getValue())) {
                stale.put(f.getKey(), flows.stale(slices, f.getValue()));
            }
        }
        List<Map<String, Object>> jobs = new ArrayList<>();
        int maxFlows = args.getInt("max-flows", DEFAULT_MAX_FLOWS, 1, 20);
        if (args.get("flow") != null) {
            String id = args.get("flow");
            if (!stale.containsKey(id)) {
                throw new IllegalArgumentException("no recorded version 2 flow " + id + "; flow list shows the recorded ones");
            }
            Map<String, Object> doc = recorded.get(id);
            List<String> places = new ArrayList<>();
            for (Flows.Crossing c : flows.crossings(slices, doc, ctx.places.places())) {
                if (!c.module()) {
                    places.add(c.place().id());
                }
            }
            jobs.add(job(id, String.valueOf(Concepts.maps(doc.get("entries")).get(0).get("entrypointId")), places, stale.get(id)));
        } else {
            List<Places.Place> chosen = choose(ctx, store, args);
            if (chosen.isEmpty() && args.get("places") == null && args.get("ops") == null && !store.hasRows()) {
                int top = args.getInt("top", DEFAULT_TOP, 1, 50);
                chosen = ctx.places.places().stream().filter(p -> p.level() == studio.volkoff.atlas.report.Risk.Level.HIGH).limit(top).toList();
                if (!chosen.isEmpty()) {
                    out.append("# no quick check has run: the first ").append(chosen.size())
                            .append(" HIGH places in the report's order are planned (--top N to change)\n");
                }
            }
            if (chosen.isEmpty()) {
                return out + "# nothing to audit deeply: no HIGH place is CONFIRMED or UNCERTAIN by the quick check without a current deep verdict; "
                        + "run the quick check first, or name --flow, --places or --ops\n";
            }
            List<Map<String, Object>> links = flows.links();
            for (Places.Place p : chosen) {
                Map<String, Object> home = null;
                for (Map<String, Object> j : jobs) {
                    if (((List<?>) j.get("reach")).stream().anyMatch(p.ops()::contains)) {
                        home = j;
                    }
                }
                if (home != null) {
                    ((List<String>) home.get("places")).add(p.id());
                    continue;
                }
                if (jobs.size() >= maxFlows) {
                    continue;
                }
                String start = p.sampleOp();
                String flowId = null;
                for (Map.Entry<String, Map<String, Object>> f : recorded.entrySet()) {
                    if (stale.containsKey(f.getKey()) && Concepts.maps(f.getValue().get("entries")).stream().anyMatch(e -> start.equals(e.get("entrypointId")))) {
                        flowId = f.getKey();
                    }
                }
                List<String> places = new ArrayList<>(List.of(p.id()));
                Map<String, Object> j = job(flowId != null ? flowId : newFlowId(ctx, start, recorded.keySet()), start, places,
                        flowId == null || stale.get(flowId));
                j.put("reach", reach(links, start));
                jobs.add(j);
            }
            jobs.forEach(j -> j.remove("reach"));
        }

        Pricing trace = Pricing.of(args.get("trace-model", "opus"));
        Pricing author = Pricing.of(args.get("deep-model", "fable"));
        Pricing verifier = Pricing.of(args.get("verify-model", "opus"));
        String id = "R" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        double total = 0;
        int n = 0;
        for (Map<String, Object> j : jobs) {
            j.put("batch", "B-" + id + "-" + String.format("%02d", ++n));
            int packet = DEFAULT_PACKET_TOKENS;
            if (recorded.containsKey(String.valueOf(j.get("flowId"))) && !Boolean.TRUE.equals(j.get("retrace"))) {
                packet = DeepPacket.scenarios(ctx, flows, store, String.valueOf(j.get("flowId")), "<draft>").length() / 4;
            }
            List<Map<String, Object>> steps = new ArrayList<>();
            boolean retrace = Boolean.TRUE.equals(j.remove("retrace"));
            steps.add(step("TRACE", retrace ? "PLANNED" : "SKIPPED", trace, retrace ? trace.usd(TRACE_FRESH, TRACE_CACHED, TRACE_OUT) : 0));
            long fresh = AGENT_BASE + packet + SCENARIO_READ;
            steps.add(step("SCENARIOS", "PLANNED", author, author.usd(fresh, fresh / 3, SCENARIO_OUT)));
            long vfresh = AGENT_BASE + packet * 7L / 10 + VERIFY_READ;
            steps.add(step("VERIFY", "PLANNED", verifier, verifier.usd(vfresh, vfresh * VERIFY_TURNS, VERIFY_OUT)));
            double usd = 0;
            for (Map<String, Object> s : steps) {
                usd += Plan.num(((Map<?, ?>) s.get("estimate")).get("usd"));
            }
            j.put("steps", steps);
            j.put("usd", Plan.round(usd, 4));
            total += usd;
        }
        long plannedSteps = jobs.stream().flatMap(j -> Concepts.maps(j.get("steps")).stream()).filter(s -> "PLANNED".equals(s.get("status"))).count();
        Pricing loop = Pricing.of(args.get("orchestrator-model", "haiku"));
        double orchestrator = Plan.orchestrator(plannedSteps, loop).usd();
        total += orchestrator;
        double maxUsd = args.get("max-usd") != null ? Double.parseDouble(args.get("max-usd")) : Plan.round(Math.max(0.5, total * 2), 2);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", id);
        plan.put("kind", "deep");
        plan.put("createdAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        plan.put("tier", "DEEP");
        plan.put("maxUsd", maxUsd);
        plan.put("waves", args.getInt("waves", DEFAULT_WAVES, 1, 60));
        plan.put("wavesIssued", 0);
        plan.put("spent", 0.0);
        Map<String, Object> atlas = new LinkedHashMap<>();
        for (String k : List.of("commit", "scannedAt", "sourcesHash")) {
            atlas.put(k, ctx.atlas.manifest.get(k));
        }
        plan.put("atlas", atlas);
        plan.put("estimate", Map.of("usd", Plan.round(total, 4)));
        plan.put("jobs", jobs);
        store.writePlan(plan);

        out.append("flow\tstart\tplaces\tsteps\test. USD\n");
        int reused = 0;
        for (Map<String, Object> j : jobs) {
            List<String> planned = new ArrayList<>();
            for (Map<String, Object> s : Concepts.maps(j.get("steps"))) {
                if ("PLANNED".equals(s.get("status"))) {
                    planned.add(String.valueOf(s.get("name")));
                } else {
                    reused++;
                }
            }
            out.append(j.get("flowId")).append('\t').append(j.get("startEp")).append('\t').append(String.join(",", (List<String>) j.get("places")))
                    .append('\t').append(String.join(",", planned)).append('\t').append(Plan.money(Plan.num(j.get("usd")))).append('\n');
        }
        out.append("# deep plan ").append(id).append(": ").append(jobs.size()).append(jobs.size() == 1 ? " flow" : " flows")
                .append(reused > 0 ? "; " + reused + " already traced and current, so their trace is skipped" : "").append('\n');
        out.append("# estimate ≈ $").append(Plan.money(total)).append(" (trace on ").append(trace.alias()).append(", scenarios on ").append(author.alias())
                .append(", verification on ").append(verifier.alias()).append(", $").append(Plan.money(orchestrator)).append(" of it for the orchestrator on ")
                .append(loop.alias()).append(")\n");
        out.append("# caps: usd ").append(Plan.money(maxUsd)).append(args.get("max-usd") != null ? "" : " (2 × estimate; --max-usd to change)")
                .append(", ").append(plan.get("waves")).append(" steps\n");
        out.append("# nothing has been spent. To run exactly this plan: /resilience-audit:audit --deep --yes ").append(id).append('\n');
        return out.toString();
    }

    /** Quick-check places worth a deep audit: CONFIRMED or UNCERTAIN at HIGH, or the ones the filters name; worst first. */
    private static List<Places.Place> choose(AuditContext ctx, AuditStore store, Args args) throws Exception {
        Set<String> named = new LinkedHashSet<>();
        if (args.get("places") != null) {
            for (String p : args.get("places").split(",")) {
                named.add(p.strip());
            }
        }
        Set<String> ops = new LinkedHashSet<>();
        if (args.get("ops") != null) {
            for (String o : args.get("ops").split(",")) {
                ops.add(o.strip());
            }
        }
        Map<String, String> cores = new LinkedHashMap<>();
        for (Places.Place p : ctx.places.places()) {
            cores.put(p.id(), Fingerprint.core(ctx, Packet.view(ctx, p)));
        }
        Map<String, AuditStore.Resolved> resolved = AuditStore.resolve(store.rows(), cores, ctx.repo);
        List<Places.Place> out = new ArrayList<>();
        for (Places.Place p : ctx.places.places()) {
            AuditStore.Resolved r = resolved.get(p.id());
            boolean deepDone = r != null && r.status().equals("CURRENT") && !"TRIAGE".equals(r.row().get("tier"));
            if (deepDone && !args.flag("include-current")) {
                continue;
            }
            if (!named.isEmpty() || !ops.isEmpty()) {
                if (named.contains(p.id()) || p.ops().stream().anyMatch(ops::contains)) {
                    out.add(p);
                }
            } else if (p.level() == studio.volkoff.atlas.report.Risk.Level.HIGH && r != null && r.status().equals("CURRENT")
                    && ("CONFIRMED".equals(r.verdict()) || "UNCERTAIN".equals(r.verdict()))) {
                out.add(p);
            }
        }
        return out;
    }

    /** The entry points within reach of {@code start} over the scanner's candidate links, start included. */
    private static List<String> reach(List<Map<String, Object>> links, String start) {
        Set<String> seen = new LinkedHashSet<>(List.of(start));
        Deque<String> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && seen.size() < MAX_SEGMENTS) {
            String at = queue.poll();
            for (Map<String, Object> l : links) {
                if (at.equals(((Map<?, ?>) l.get("from")).get("entrypointId"))) {
                    String to = String.valueOf(((Map<?, ?>) l.get("to")).get("entrypointId"));
                    if (seen.size() < MAX_SEGMENTS && seen.add(to)) {
                        queue.add(to);
                    }
                }
            }
        }
        return new ArrayList<>(seen);
    }

    private static Map<String, Object> job(String flowId, String startEp, List<String> places, boolean retrace) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("flowId", flowId);
        j.put("startEp", startEp);
        j.put("places", places);
        j.put("retrace", retrace);
        return j;
    }

    private static Map<String, Object> step(String name, String status, Pricing pricing, double usd) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", name);
        s.put("status", status);
        s.put("agent", AGENTS.get(name));
        s.put("model", pricing.alias());
        s.put("modelId", pricing.id());
        s.put("estimate", Map.of("usd", Plan.round(usd, 4)));
        return s;
    }

    /** FLOW- plus the start's class and method in kebab case, unique among the recorded flows. */
    private static String newFlowId(AuditContext ctx, String start, Set<String> taken) {
        Map<String, Object> ep = ctx.inputById.get(start).ep();
        String cls = String.valueOf(ep.get("cls"));
        String base = "FLOW-" + kebab(cls.substring(cls.lastIndexOf('.') + 1).replaceAll("(Controller|Listener|Consumer|Service|Endpoint|Job)$", ""))
                + "-" + kebab(String.valueOf(ep.get("method")));
        base = base.length() > 60 ? base.substring(0, 60).replaceAll("-$", "") : base;
        String id = base;
        for (int i = 2; taken.contains(id); i++) {
            id = base + "-" + i;
        }
        return id;
    }

    private static String kebab(String s) {
        return s.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static String next(Path repo, Path atlasDir, AuditStore store, Flows flows, Map<String, Object> plan, String id) throws Exception {
        Map<?, ?> atlas = (Map<?, ?>) plan.get("atlas");
        if (!Scanner.sourcesHash(Workspace.load(repo)).equals(atlas.get("sourcesHash"))) {
            throw new IllegalArgumentException("sources changed since plan " + id + "; run the audit skill again to plan");
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        for (String k : List.of("commit", "scannedAt", "sourcesHash")) {
            if (!String.valueOf(atlas.get(k)).equals(String.valueOf(ctx.atlas.manifest.get(k)))) {
                throw new IllegalArgumentException("the atlas was rebuilt since plan " + id + "; run the audit skill again to plan");
            }
        }
        double spent = Plan.num(plan.get("spent"));
        double maxUsd = Plan.num(plan.get("maxUsd"));
        int waves = ((Number) plan.get("waves")).intValue();
        int issued = ((Number) plan.get("wavesIssued")).intValue();
        Map<String, Object> job = null;
        Map<String, Object> step = null;
        for (Map<String, Object> j : Concepts.maps(plan.get("jobs"))) {
            boolean failed = false;
            for (Map<String, Object> s : Concepts.maps(j.get("steps"))) {
                String status = String.valueOf(s.get("status"));
                if (status.equals("UNJUDGED")) {
                    failed = true;
                } else if (status.equals("PLANNED") && failed) {
                    s.put("status", "SKIPPED");
                    s.put("reason", "an earlier step of this flow was not judged");
                } else if (status.equals("ISSUED")) {
                    store.writePlan(plan);
                    return "# " + j.get("flowId") + ":" + s.get("name") + " was issued and not recorded: run audit record-step "
                            + j.get("flowId") + ":" + s.get("name") + " first\n";
                } else if (status.equals("PLANNED") && step == null) {
                    if ("VERIFY".equals(s.get("name")) && DeepPacket.toVerify(store, String.valueOf(j.get("flowId"))).isEmpty()) {
                        s.put("status", "SKIPPED");
                        s.put("reason", "no HIGH or CRITICAL scenario to verify");
                        continue;
                    }
                    job = j;
                    step = s;
                }
            }
        }
        String stopped = step == null ? "done" : issued >= waves ? "waves"
                : spent + Plan.num(((Map<?, ?>) step.get("estimate")).get("usd")) > maxUsd + 1e-9 ? "usd" : null;
        if (stopped != null) {
            plan.put("stoppedBecause", stopped);
            String snapshot = "";
            if (stopped.equals("done") && plan.get("snapshot") == null) {
                Path p = History.snapshot(flows, "deep-done", History.DEFAULT_KEEP);
                plan.put("snapshot", p.getFileName().toString());
                snapshot = "# snapshot: " + Plan.shown(p) + "\n";
            }
            store.writePlan(plan);
            return progress(plan) + "stoppedBecause: " + stopped + "\n" + switch (stopped) {
                case "done" -> "# every step of this plan was issued; run report\n";
                case "waves" -> "# the plan's " + waves + " steps are used; re-run the audit skill to plan the rest\n";
                default -> "# the next step would pass the cap of $" + Plan.money(maxUsd) + "; plan again with --max-usd to go on\n";
            } + snapshot;
        }
        String flowId = String.valueOf(job.get("flowId"));
        String name = String.valueOf(step.get("name"));
        String stepBatch = job.get("batch") + "-" + name.toLowerCase(Locale.ROOT);
        Path draft = draft(store, flows, flowId, name);
        Files.createDirectories(draft.toAbsolutePath().getParent());
        Files.deleteIfExists(draft);
        String packet = switch (name) {
            case "TRACE" -> DeepPacket.trace(ctx, flows, job, Plan.shown(draft));
            case "SCENARIOS" -> DeepPacket.scenarios(ctx, flows, store, flowId, Plan.shown(draft));
            default -> DeepPacket.verification(ctx, flows, store, flowId, Plan.shown(draft));
        };
        AuditStore.write(store.packet(stepBatch), packet);
        step.put("status", "ISSUED");
        step.put("issuedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        plan.put("spent", Plan.round(spent + Plan.num(((Map<?, ?>) step.get("estimate")).get("usd")), 4));
        plan.put("wavesIssued", issued + 1);
        plan.remove("stoppedBecause");
        store.writePlan(plan);
        return progress(plan) + "packet\t" + Plan.shown(store.packet(stepBatch)) + "\tdraft\t" + Plan.shown(draft) + "\tstep\t" + flowId + ":" + name
                + "\tagent\t" + step.get("agent") + "\tmodel\t" + step.get("model") + "\n";
    }

    static Path draft(AuditStore store, Flows flows, String flowId, String step) {
        return switch (step) {
            case "TRACE" -> flows.draft(flowId);
            case "SCENARIOS" -> store.dir.resolve("drafts").resolve(flowId + ".scenarios.jsonl");
            default -> store.dir.resolve("drafts").resolve(flowId + ".verification.jsonl");
        };
    }

    private static String progress(Map<String, Object> plan) {
        int done = 0;
        int all = 0;
        for (Map<String, Object> j : Concepts.maps(plan.get("jobs"))) {
            for (Map<String, Object> s : Concepts.maps(j.get("steps"))) {
                if (!"SKIPPED".equals(s.get("status")) || s.get("reason") != null) {
                    all++;
                }
                if ("RECORDED".equals(s.get("status")) || "UNJUDGED".equals(s.get("status"))) {
                    done++;
                }
            }
        }
        return "# deep " + plan.get("id") + " · steps recorded " + done + "/" + all + " · est. $" + Plan.money(Plan.num(plan.get("spent")))
                + " of cap $" + Plan.money(Plan.num(plan.get("maxUsd"))) + "\n";
    }

    /** Stores what one step's agent wrote; a missing or rejected draft leaves the step UNJUDGED and the flow's later steps unplanned. */
    static String recordStep(Path repo, Path atlasDir, AuditStore store, Flows flows, String ref) throws Exception {
        Map<String, Object> plan = store.plan();
        if (plan == null || !"deep".equals(plan.get("kind"))) {
            throw new IllegalArgumentException("the current plan is not a deep plan; record-step records its steps");
        }
        int colon = ref.lastIndexOf(':');
        String flowId = colon < 0 ? ref : ref.substring(0, colon);
        String name = colon < 0 ? "" : ref.substring(colon + 1);
        Map<String, Object> job = null;
        Map<String, Object> step = null;
        for (Map<String, Object> j : Concepts.maps(plan.get("jobs"))) {
            if (flowId.equals(j.get("flowId"))) {
                job = j;
                for (Map<String, Object> s : Concepts.maps(j.get("steps"))) {
                    if (name.equals(s.get("name"))) {
                        step = s;
                    }
                }
            }
        }
        if (step == null) {
            throw new IllegalArgumentException(ref + " is not a step of plan " + plan.get("id") + "; use <FLOW-id>:<TRACE|SCENARIOS|VERIFY> as audit next printed it");
        }
        if ("RECORDED".equals(step.get("status"))) {
            return "# " + ref + " was already recorded; nothing changed\n";
        }
        if (!"ISSUED".equals(step.get("status"))) {
            throw new IllegalArgumentException(ref + " was never issued; audit next issues steps");
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        String batch = job.get("batch") + "-" + name.toLowerCase(Locale.ROOT);
        Outcome o = switch (name) {
            case "TRACE" -> {
                FlowRecord.Result r = Files.exists(flows.draft(flowId)) ? FlowRecord.record(flows, flowId, String.valueOf(step.get("modelId")))
                        : new FlowRecord.Result("# the tracer wrote no draft at " + Plan.shown(flows.draft(flowId)) + "\n", false);
                yield new Outcome(r.ok(), r.ok() ? null : "trace-rejected", r.report());
            }
            case "SCENARIOS" -> ScenarioRecord.record(ctx, store, flows, plan, job, step, batch);
            default -> VerificationRecord.record(ctx, store, flows, plan, job, step, batch);
        };
        step.put("status", o.ok() ? "RECORDED" : "UNJUDGED");
        step.put("recordedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        if (o.reason() != null) {
            step.put("reason", o.reason());
        }
        store.writePlan(plan);
        Derive.run(AuditContext.open(repo, atlasDir), store);
        if (Files.exists(flows.doc(flowId))) {
            flows.writeIndex();
            FlowPage.write(flows, flowId);
        }
        return o.report() + "# " + ref + " " + (o.ok() ? "recorded" : "not judged: " + o.reason()) + "\n"
                + (Files.exists(flows.page(flowId)) ? "# page: " + flows.page(flowId).toUri() + "\n" : "");
    }

    /** What recording one step did: whether it counts as judged, why not, and the lines to print. */
    record Outcome(boolean ok, String reason, String report) {
    }
}
