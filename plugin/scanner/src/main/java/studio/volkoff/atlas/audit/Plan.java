package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.query.Args;
import studio.volkoff.atlas.query.EntryPointFilter;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.report.Risk;
import studio.volkoff.atlas.scan.Scanner;
import studio.volkoff.atlas.scan.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit plan} and {@code audit next}. A plan names its places and batches once; {@code next}
 * issues the next batches only while the tree, the atlas and the store still match the plan and
 * the caps allow, and counts a batch as spent the moment it is issued.
 */
final class Plan {

    static final int DEFAULT_BATCH = 8;
    static final int MAX_BATCH_FILES = 12;
    static final int MAX_BATCH_TOKENS = 32_000;
    static final int DEFAULT_WAVES = 12;
    static final int DEFAULT_BATCHES_PER_WAVE = 4;
    static final int MAX_TURNS = 30;
    // Calibrated on the golden fixture, 2026-10-23: a Haiku checker's own context is about 30k
    // tokens before the packet, and it writes about 2.4k tokens per place, thinking included.
    /** The checker's base context: Claude Code's sub-agent prompt, tools and the agent file. */
    static final int FIXED_OVERHEAD = 30_000;
    /** One extra file opened by the checker; it is expected to open half its allowance. */
    static final int FILE_TOKENS = 1_500;
    static final int OUT_PER_PLACE = 2_400;
    static final int OUT_FIXED = 1_000;
    /** Claude Code's main session writes its prompt cache with the one-hour lifetime, at twice the input price. */
    static final double SESSION_CACHE_WRITE_FACTOR = 2.0;
    /** The orchestrating skill's own context, and what each wave adds to it. */
    static final int ORCHESTRATOR_CONTEXT = 35_000;
    private static final int SHOWN = 30;
    private static final Set<String> ENTRY_FLAGS = Set.of("kind", "subkind", "module", "package", "class", "target",
            "path-prefix", "status", "origin", "via", "file", "domain", "tag");

    private Plan() {
    }

    record Estimate(long freshIn, long cachedIn, long out, double usd) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("freshIn", freshIn);
            m.put("cachedIn", cachedIn);
            m.put("out", out);
            m.put("usd", round(usd, 4));
            return m;
        }
    }

    /** One checker agent: it reads the packet, opens a few files and writes one draft. */
    static Estimate estimate(int bodyTokens, int places, Pricing pricing) {
        long turns = 3L + places / 4;
        long reading = (long) places * FILE_TOKENS / 2;
        long fresh = bodyTokens + FIXED_OVERHEAD + reading;
        long cached = (turns - 1) * (bodyTokens + FIXED_OVERHEAD) + reading * (turns - 1) / 2;
        long out = (long) OUT_PER_PLACE * places + OUT_FIXED;
        return new Estimate(fresh, cached, out, pricing.usd(fresh, cached, out));
    }

    /** The skill's own turns: about four per wave, each rereading its growing context. */
    static Estimate orchestrator(long waves, Pricing pricing) {
        long turns = 4 + 4 * waves;
        long fresh = ORCHESTRATOR_CONTEXT + 2_000 * waves;
        long cached = turns * (ORCHESTRATOR_CONTEXT + 1_000 * waves);
        long out = 1_500 + 1_500 * waves;
        return new Estimate(fresh, cached, out, pricing.usd(fresh, cached, out, SESSION_CACHE_WRITE_FACTOR));
    }

    static String plan(Path repo, Path atlasDir, AuditStore store, Args args) throws Exception {
        StringBuilder out = new StringBuilder();
        String treeHash = Scanner.sourcesHash(Workspace.load(repo));
        Map<String, Object> manifest = Files.exists(atlasDir.resolve("manifest.json"))
                ? studio.volkoff.atlas.json.Json.parseObject(AuditStore.read(atlasDir.resolve("manifest.json"))) : null;
        String rescan = manifest == null ? "no atlas yet"
                : !treeHash.equals(manifest.get("sourcesHash")) ? "sources changed since the last scan"
                : !Files.exists(atlasDir.resolve("effects.jsonl")) ? "the atlas has no effects" : null;
        if (rescan != null) {
            new Scanner().scan(repo, atlasDir);
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        String commit = ctx.manifestString("commit");
        out.append("# atlas @").append(commit == null ? "(no git commit)" : commit.substring(0, Math.min(7, commit.length())))
                .append(rescan == null ? " matches the sources" : " rescanned: " + rescan).append('\n');

        int closed = Record.closeUnrecorded(ctx, store, store.plan());
        if (closed > 0) {
            out.append("# closed ").append(closed).append(" batches of the previous plan that were issued but never recorded (UNJUDGED, not-recorded)\n");
        }

        List<Places.Place> candidates = select(ctx, args);
        Map<String, Packet.PlaceView> views = new LinkedHashMap<>();
        Map<String, String> cores = new LinkedHashMap<>();
        for (Places.Place p : candidates) {
            Packet.PlaceView v = Packet.view(ctx, p);
            views.put(p.id(), v);
            cores.put(p.id(), Fingerprint.core(ctx, v));
        }
        Map<String, AuditStore.Resolved> resolved = AuditStore.resolve(store.rows(), cores, repo);
        boolean includeCurrent = args.flag("include-current");
        List<Places.Place> planned = new ArrayList<>();
        Map<String, String> previous = new LinkedHashMap<>();
        int skippedCurrent = 0;
        int skippedUnjudged = 0;
        int stale = 0;
        int unjudged = 0;
        for (Places.Place p : candidates) {
            AuditStore.Resolved r = resolved.get(p.id());
            String prev = r == null ? "new" : r.status().toLowerCase(Locale.ROOT);
            if (r != null && r.status().equals("CURRENT")) {
                if (!includeCurrent || !"TRIAGE".equals(r.row().get("tier"))) {
                    skippedCurrent++;
                    continue;
                }
                prev = "current " + r.verdict();
            } else if (r != null && r.status().equals("UNJUDGED")) {
                if (r.unjudged() >= 2 && !r.hasVerdict() && !includeCurrent) {
                    skippedUnjudged++;
                    continue;
                }
                unjudged++;
            } else if (r != null) {
                stale++;
            }
            planned.add(p);
            previous.put(p.id(), prev);
        }
        if (planned.isEmpty()) {
            out.append("# nothing to check: ").append(candidates.size()).append(" places match")
                    .append(skippedCurrent > 0 ? ", " + skippedCurrent + " already have a current verdict" : "")
                    .append(skippedUnjudged > 0 ? ", " + skippedUnjudged + " stayed unjudged twice (--include-current re-offers them)" : "")
                    .append(". No plan was written and nothing was spent.\n");
            return out.toString();
        }

        Pricing pricing = Pricing.of(args.get("model", "haiku"));
        int batchSize = args.getInt("batch", DEFAULT_BATCH, 1, 20);
        String id = "R" + ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
        List<List<Packet.PlaceView>> batches = batch(ctx, planned, views, batchSize);

        List<Map<String, Object>> batchRows = new ArrayList<>();
        List<Map<String, Object>> placeRows = new ArrayList<>();
        long fresh = 0;
        long cached = 0;
        long outTokens = 0;
        double usd = 0;
        for (int i = 0; i < batches.size(); i++) {
            String batchId = String.format("B-%s-%02d", id, i + 1);
            List<Packet.PlaceView> b = batches.get(i);
            String text = Packet.render(ctx, batchId, shown(repo, store.draft(batchId)), b);
            int tokens = Packet.bodyBytes(text) / 4;
            Estimate e = estimate(tokens, b.size(), pricing);
            fresh += e.freshIn();
            cached += e.cachedIn();
            outTokens += e.out();
            usd += e.usd();
            Set<String> files = new LinkedHashSet<>();
            List<String> ids = new ArrayList<>();
            for (Packet.PlaceView v : b) {
                files.addAll(v.files());
                ids.add(v.place().id());
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("id", v.place().id());
                pr.put("key", v.place().key());
                pr.put("core", cores.get(v.place().id()));
                pr.put("batch", batchId);
                placeRows.add(pr);
            }
            Map<String, Object> br = new LinkedHashMap<>();
            br.put("id", batchId);
            br.put("places", ids);
            br.put("files", files.size());
            br.put("bodyTokens", tokens);
            br.put("estimate", e.toMap());
            br.put("status", "PLANNED");
            batchRows.add(br);
        }
        double maxUsd = args.get("max-usd") != null ? parseUsd(args.get("max-usd")) : Math.max(0.01, Math.ceil(usd * 2 * 100) / 100);
        int waves = args.getInt("waves", DEFAULT_WAVES, 1, 1000);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("id", id);
        plan.put("createdAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        plan.put("tier", "TRIAGE");
        plan.put("filters", filters(args));
        plan.put("model", pricing.alias());
        plan.put("modelId", pricing.id());
        plan.put("batchSize", batchSize);
        plan.put("maxUsd", round(maxUsd, 2));
        plan.put("waves", waves);
        plan.put("wavesIssued", 0);
        plan.put("spent", 0.0);
        Map<String, Object> atlas = new LinkedHashMap<>();
        atlas.put("commit", commit);
        atlas.put("scannedAt", ctx.manifestString("scannedAt"));
        atlas.put("sourcesHash", ctx.manifestString("sourcesHash"));
        plan.put("atlas", atlas);
        plan.put("pricing", pricing.toMap());
        plan.put("estimate", new Estimate(fresh, cached, outTokens, usd).toMap());
        plan.put("places", placeRows);
        plan.put("batches", batchRows);
        store.writePlan(plan);

        out.append("id\trule\tlevel\tfile\tsymbol\tline\toperations\tprevious\n");
        int high = 0;
        for (int i = 0; i < planned.size(); i++) {
            Places.Place p = planned.get(i);
            if (p.level() == Risk.Level.HIGH) {
                high++;
            }
            if (i < SHOWN) {
                out.append(p.id()).append('\t').append(p.code()).append('\t').append(p.level()).append('\t').append(p.file()).append('\t')
                        .append(p.symbol()).append('\t').append(p.line()).append('\t').append(p.ops().size()).append('\t')
                        .append(previous.get(p.id())).append('\n');
            }
        }
        if (planned.size() > SHOWN) {
            out.append("# ").append(planned.size() - SHOWN).append(" more places are listed in plan.json\n");
        }
        long orchestratorWaves = Math.min(waves, (batches.size() + DEFAULT_BATCHES_PER_WAVE - 1) / DEFAULT_BATCHES_PER_WAVE);
        double orchestrator = orchestrator(orchestratorWaves, Pricing.of("haiku")).usd();
        out.append("# plan ").append(id).append(": ").append(planned.size()).append(" places in scope (HIGH ").append(high)
                .append(", MEDIUM ").append(planned.size() - high).append(")");
        if (skippedCurrent > 0) {
            out.append("; ").append(skippedCurrent).append(" with a current verdict skipped");
        }
        if (stale > 0) {
            out.append("; ").append(stale).append(" stale re-planned");
        }
        if (unjudged > 0) {
            out.append("; ").append(unjudged).append(" unjudged re-offered");
        }
        if (skippedUnjudged > 0) {
            out.append("; ").append(skippedUnjudged).append(" unjudged twice skipped");
        }
        out.append('\n');
        out.append("# ").append(batches.size()).append(" batches of ≤").append(batchSize).append(" on ").append(pricing.id()).append(": ~")
                .append(tokens(fresh + cached + outTokens)).append(" tokens (").append(tokens(fresh)).append(" fresh, ").append(tokens(cached))
                .append(" cached, ").append(tokens(outTokens)).append(" out) ≈ $").append(money(usd)).append(" est. (+ orchestrator ≈ $")
                .append(money(orchestrator)).append(")\n");
        out.append("# caps: usd ").append(money(maxUsd)).append(args.get("max-usd") != null ? " (--max-usd)" : " (2 × estimate; --max-usd to change)")
                .append(", waves ").append(waves).append(" of up to ").append(DEFAULT_BATCHES_PER_WAVE).append(" batches")
                .append(batches.size() > waves * DEFAULT_BATCHES_PER_WAVE ? " (covers " + waves * DEFAULT_BATCHES_PER_WAVE + " of " + batches.size() + " batches; re-run to continue)" : "")
                .append(", turns ").append(MAX_TURNS).append(" per batch\n");
        out.append("# nothing has been spent. To run exactly this plan: /resilience-audit:audit --yes ").append(id).append('\n');
        return out.toString();
    }

    /** Places in selection order: filtered by place flags, rule, level and the entry-point filters of any of their operations. */
    private static List<Places.Place> select(AuditContext ctx, Args args) throws Exception {
        Set<String> ids = csv(args.get("places"));
        Set<String> ops = csv(args.get("ops"));
        Set<String> rules = csv(args.get("rule"));
        for (String r : rules) {
            if (!Risk.RULES.containsKey(r)) {
                throw new IllegalArgumentException("--rule " + r + " is unknown; rules: " + String.join(", ", Risk.RULES.keySet()));
            }
        }
        Set<String> levels = csv(args.get("level", "HIGH,MEDIUM").toUpperCase(Locale.ROOT));
        for (String l : levels) {
            if (!l.equals("HIGH") && !l.equals("MEDIUM")) {
                throw new IllegalArgumentException("--level " + l + ": the quick check covers HIGH and MEDIUM hints only");
            }
        }
        Set<String> matchingOps = null;
        if (args.flags().keySet().stream().anyMatch(ENTRY_FLAGS::contains)) {
            EntryPointFilter f = EntryPointFilter.of(args, !ctx.atlas.domains().isEmpty());
            matchingOps = new HashSet<>();
            for (Map<String, Object> row : EntryPointFilter.rows(ctx.atlas)) {
                if (f.predicate().test(row)) {
                    matchingOps.add(String.valueOf(row.get("id")));
                }
            }
        }
        List<Places.Place> out = new ArrayList<>();
        for (Places.Place p : ctx.places.places()) {
            if (!ids.isEmpty() && !ids.contains(p.id())) {
                continue;
            }
            if (!ops.isEmpty() && p.ops().stream().noneMatch(ops::contains)) {
                continue;
            }
            if (!rules.isEmpty() && !rules.contains(p.code())) {
                continue;
            }
            if (!levels.contains(p.level().name())) {
                continue;
            }
            if (matchingOps != null && p.ops().stream().noneMatch(matchingOps::contains)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /** Greedy in selection order: a new batch at {@code size} places, more than 12 packet files, or a packet body over 32k tokens. */
    private static List<List<Packet.PlaceView>> batch(AuditContext ctx, List<Places.Place> planned, Map<String, Packet.PlaceView> views,
                                                     int size) throws Exception {
        List<List<Packet.PlaceView>> out = new ArrayList<>();
        List<Packet.PlaceView> current = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        for (Places.Place p : planned) {
            Packet.PlaceView v = views.get(p.id());
            if (!current.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(files);
                merged.addAll(v.files());
                List<Packet.PlaceView> trial = new ArrayList<>(current);
                trial.add(v);
                boolean fits = current.size() < size && merged.size() <= MAX_BATCH_FILES
                        && Packet.bodyBytes(Packet.render(ctx, "B-trial", "", trial)) / 4 <= MAX_BATCH_TOKENS;
                if (!fits) {
                    out.add(current);
                    current = new ArrayList<>();
                    files = new LinkedHashSet<>();
                }
            }
            current.add(v);
            files.addAll(v.files());
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    static String next(Path repo, Path atlasDir, AuditStore store, String id, Args args) throws Exception {
        Map<String, Object> plan = store.plan();
        if (plan == null) {
            throw new IllegalArgumentException("no plan in " + store.dir + "; run the audit skill without --yes to make one");
        }
        if (!String.valueOf(plan.get("id")).equals(id)) {
            throw new IllegalArgumentException("plan " + id + " is not the current plan (" + plan.get("id") + "); run the audit skill again to plan and confirm");
        }
        Map<?, ?> atlas = (Map<?, ?>) plan.get("atlas");
        String treeHash = Scanner.sourcesHash(Workspace.load(repo));
        if (!treeHash.equals(atlas.get("sourcesHash"))) {
            throw new IllegalArgumentException("sources changed since plan " + id + "; run the audit skill again to plan");
        }
        AuditContext ctx = AuditContext.open(repo, atlasDir);
        for (String k : List.of("commit", "scannedAt", "sourcesHash")) {
            if (!String.valueOf(atlas.get(k)).equals(String.valueOf(ctx.atlas.manifest.get(k)))) {
                throw new IllegalArgumentException("the atlas was rebuilt since plan " + id + "; run the audit skill again to plan");
            }
        }
        List<Map<String, Object>> batches = maps(plan.get("batches"));
        Map<String, String> plannedCore = new LinkedHashMap<>();
        for (Map<String, Object> pr : maps(plan.get("places"))) {
            plannedCore.put(String.valueOf(pr.get("id")), String.valueOf(pr.get("core")));
        }
        List<Map<String, Object>> open = new ArrayList<>();
        Map<String, String> openCores = new LinkedHashMap<>();
        Map<String, Packet.PlaceView> views = new LinkedHashMap<>();
        for (Map<String, Object> b : batches) {
            if (!"PLANNED".equals(b.get("status"))) {
                continue;
            }
            open.add(b);
            for (Object pid : (List<?>) b.get("places")) {
                Places.Place p = ctx.placeById.get(String.valueOf(pid));
                if (p == null) {
                    throw new IllegalArgumentException("place " + pid + " no longer derives from the atlas; run the audit skill again to plan");
                }
                Packet.PlaceView v = Packet.view(ctx, p);
                String core = Fingerprint.core(ctx, v);
                if (!core.equals(plannedCore.get(p.id()))) {
                    throw new IllegalArgumentException("the code behind " + p.id() + " changed since plan " + id + "; run the audit skill again to plan");
                }
                views.put(p.id(), v);
                openCores.put(p.id(), core);
            }
        }
        Map<String, AuditStore.Resolved> resolved = AuditStore.resolve(store.rows(), openCores, repo);
        for (AuditStore.Resolved r : resolved.values()) {
            if (r.status().equals("CURRENT") && !String.valueOf(r.row().get("batch")).startsWith("B-" + id + "-")) {
                throw new IllegalArgumentException(r.placeId() + " got a verdict from another plan (" + r.row().get("batch") + ") since plan " + id
                        + "; run the audit skill again to plan");
            }
        }

        StringBuilder out = new StringBuilder();
        double spent = num(plan.get("spent"));
        double maxUsd = num(plan.get("maxUsd"));
        int waves = ((Number) plan.get("waves")).intValue();
        int wavesIssued = ((Number) plan.get("wavesIssued")).intValue();
        String stopped = null;
        List<Map<String, Object>> take = new ArrayList<>();
        if (open.isEmpty()) {
            stopped = "done";
        } else if (wavesIssued >= waves) {
            stopped = "waves";
        } else {
            int n = args.getInt("batches", DEFAULT_BATCHES_PER_WAVE, 1, 20);
            double sum = spent;
            for (Map<String, Object> b : open) {
                if (take.size() >= n) {
                    break;
                }
                double usd = num(((Map<?, ?>) b.get("estimate")).get("usd"));
                if (sum + usd > maxUsd + 1e-9) {
                    break;
                }
                sum += usd;
                take.add(b);
            }
            if (take.isEmpty()) {
                stopped = "usd";
            }
        }
        if (stopped != null) {
            plan.put("stoppedBecause", stopped);
            store.writePlan(plan);
            out.append(progress(plan, store, batches, 0, 0)).append('\n');
            out.append("stoppedBecause: ").append(stopped).append('\n');
            out.append(switch (stopped) {
                case "done" -> "# every batch of this plan was issued; record any batch still open, then run report\n";
                case "waves" -> "# the plan's " + waves + " waves are used; re-run the audit skill to plan the rest\n";
                default -> "# the next batch would pass the cap of $" + money(maxUsd) + "; re-run the audit skill to plan the rest, or plan again with --max-usd\n";
            });
            return out.toString();
        }
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        int first = batches.indexOf(take.get(0)) + 1;
        for (Map<String, Object> b : take) {
            String batchId = String.valueOf(b.get("id"));
            List<Packet.PlaceView> vs = new ArrayList<>();
            for (Object pid : (List<?>) b.get("places")) {
                vs.add(views.get(String.valueOf(pid)));
            }
            AuditStore.write(store.packet(batchId), Packet.render(ctx, batchId, shown(repo, store.draft(batchId)), vs));
            Files.deleteIfExists(store.draft(batchId));
            b.put("status", "ISSUED");
            b.put("issuedAt", now);
            spent += num(((Map<?, ?>) b.get("estimate")).get("usd"));
        }
        plan.put("spent", round(spent, 4));
        plan.put("wavesIssued", wavesIssued + 1);
        plan.remove("stoppedBecause");
        store.writePlan(plan);
        out.append(progress(plan, store, batches, first, first + take.size() - 1)).append('\n');
        for (Map<String, Object> b : take) {
            String batchId = String.valueOf(b.get("id"));
            out.append("packet\t").append(shown(repo, store.packet(batchId))).append("\tdraft\t").append(shown(repo, store.draft(batchId)))
                    .append("\tbatch\t").append(batchId).append("\tmodel\t").append(plan.get("model")).append('\n');
        }
        return out.toString();
    }

    private static String progress(Map<String, Object> plan, AuditStore store, List<Map<String, Object>> batches, int from, int to) throws Exception {
        String prefix = "B-" + plan.get("id") + "-";
        int judged = 0;
        int confirmed = 0;
        for (Map<String, Object> r : store.rows()) {
            if (String.valueOf(r.get("batch")).startsWith(prefix) && !"UNJUDGED".equals(r.get("status"))) {
                judged++;
                if ("CONFIRMED".equals(r.get("verdict"))) {
                    confirmed++;
                }
            }
        }
        int places = maps(plan.get("places")).size();
        return "# wave " + plan.get("wavesIssued") + " of " + plan.get("waves")
                + (from > 0 ? " · batches " + from + "-" + to + " of " + batches.size() : " · " + batches.size() + " batches")
                + " · judged " + judged + "/" + places + " · confirmed " + confirmed
                + " · est. $" + money(num(plan.get("spent"))) + " of cap $" + money(num(plan.get("maxUsd")));
    }

    static String shown(Path repo, Path p) {
        Path abs = p.toAbsolutePath().normalize();
        return (abs.startsWith(repo) ? repo.relativize(abs) : abs).toString().replace('\\', '/');
    }

    private static Map<String, Object> filters(Args args) {
        Map<String, Object> f = new LinkedHashMap<>();
        args.flags().forEach((k, v) -> {
            if (!Set.of("repo", "atlas", "audit", "cache").contains(k)) {
                f.put(k, v);
            }
        });
        return f;
    }

    private static Set<String> csv(String v) {
        Set<String> out = new LinkedHashSet<>();
        if (v != null) {
            for (String s : v.split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static double parseUsd(String v) {
        try {
            return Double.parseDouble(v.replace("$", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--max-usd expects a number, got '" + v + "'");
        }
    }

    static String money(double usd) {
        return String.format(Locale.ROOT, "%.2f", usd);
    }

    static String tokens(long n) {
        return n >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM", n / 1e6) : n >= 1_000 ? String.format(Locale.ROOT, "%.1fk", n / 1e3) : String.valueOf(n);
    }

    static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
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
