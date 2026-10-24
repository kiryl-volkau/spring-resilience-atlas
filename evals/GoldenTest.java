import studio.volkoff.atlas.audit.Audit;
import studio.volkoff.atlas.audit.Hypotheses;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.Args;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.Query;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.report.Risk;
import studio.volkoff.atlas.scan.Scanner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans a fixture and compares the entry points with its hand-written {@code expected/} files.
 * No test framework, so it runs without a dependency download:
 * {@code java -cp dist/resilience-atlas.jar evals/GoldenTest.java fixtures/spring-golden}.
 * Exits 1 on any difference.
 */
public final class GoldenTest {

    public static void main(String[] args) throws Exception {
        Path fixture = Path.of(args.length > 0 ? args[0] : "fixtures/spring-golden").toAbsolutePath().normalize();
        Path atlas = Files.createTempDirectory("atlas-golden");
        new Scanner().scan(fixture, atlas);

        Map<String, Integer> actual = new TreeMap<>();
        Map<String, List<String>> actualBySymbol = new HashMap<>();
        Map<String, Integer> actualWebhooks = new TreeMap<>();
        for (String line : Files.readAllLines(atlas.resolve("entrypoints.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> r = Json.parseObject(line);
            String symbol = Query.symbol(String.valueOf(r.get("cls")), String.valueOf(r.get("method")));
            String row = String.join("\t", String.valueOf(r.get("kind")), String.valueOf(r.get("subkind")),
                    String.valueOf(r.get("target")), symbol, String.valueOf(r.get("status")));
            actual.merge(row, 1, Integer::sum);
            actualBySymbol.computeIfAbsent(symbol, k -> new ArrayList<>()).add(row);
            if (r.get("attrs") instanceof Map<?, ?> attrs && attrs.get("webhook") instanceof Map<?, ?> w) {
                Object confidence = w.get("confidence");
                actualWebhooks.merge(String.join("\t", String.valueOf(r.get("target")), symbol, String.valueOf(w.get("verdict")),
                        confidence == null ? "" : confidence.toString()), 1, Integer::sum);
            }
        }

        Map<String, Integer> expected = new TreeMap<>();
        List<String> lines = Files.readAllLines(fixture.resolve("expected/entrypoints.tsv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank()) {
                expected.merge(line.strip(), 1, Integer::sum);
            }
        }

        int problems = 0;
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            int missing = e.getValue() - actual.getOrDefault(e.getKey(), 0);
            if (missing > 0) {
                problems++;
                String symbol = e.getKey().split("\t")[3];
                System.out.println("MISSING  " + e.getKey() + (missing > 1 ? "  x" + missing : ""));
                for (String near : actualBySymbol.getOrDefault(symbol, List.of())) {
                    if (!expected.containsKey(near)) {
                        System.out.println("   got   " + near);
                    }
                }
            }
        }
        for (Map.Entry<String, Integer> e : actual.entrySet()) {
            int extra = e.getValue() - expected.getOrDefault(e.getKey(), 0);
            if (extra > 0) {
                problems++;
                System.out.println("EXTRA    " + e.getKey() + (extra > 1 ? "  x" + extra : ""));
            }
        }
        Path webhooks = fixture.resolve("expected/webhooks.tsv");
        if (Files.exists(webhooks)) {
            Map<String, Integer> expectedWebhooks = new TreeMap<>();
            List<String> rows = Files.readAllLines(webhooks, StandardCharsets.UTF_8);
            for (String row : rows.subList(1, rows.size())) {
                if (!row.isBlank()) {
                    String[] cols = row.split("\t", -1);
                    String confidence = cols.length > 3 ? cols[3].strip() : "";
                    expectedWebhooks.merge(String.join("\t", cols[0], cols[1], cols[2], confidence), 1, Integer::sum);
                }
            }
            for (Map.Entry<String, Integer> e : expectedWebhooks.entrySet()) {
                if (e.getValue() > actualWebhooks.getOrDefault(e.getKey(), 0)) {
                    problems++;
                    System.out.println("WEBHOOK MISSING  " + e.getKey());
                }
            }
            for (Map.Entry<String, Integer> e : actualWebhooks.entrySet()) {
                if (e.getValue() > expectedWebhooks.getOrDefault(e.getKey(), 0)) {
                    problems++;
                    System.out.println("WEBHOOK EXTRA    " + e.getKey());
                }
            }
        }
        Path forbidden = fixture.resolve("expected/not-entrypoints.txt");
        if (Files.exists(forbidden)) {
            for (String symbol : Files.readAllLines(forbidden, StandardCharsets.UTF_8)) {
                String s = symbol.strip();
                if (!s.isEmpty() && !s.startsWith("#") && actualBySymbol.containsKey(s)) {
                    problems++;
                    System.out.println("FORBIDDEN " + s + " -> " + actualBySymbol.get(s));
                }
            }
        }
        problems += checkSignals(fixture.resolve("expected/signals.tsv"), atlas);
        problems += checkRisk(fixture.resolve("expected/risk.tsv"), atlas);
        problems += checkPlaces(fixture.resolve("expected/places.tsv"), fixture, atlas);
        problems += checkAudit(fixture, atlas);
        int total = actual.values().stream().mapToInt(Integer::intValue).sum();
        System.out.println("# golden: " + total + " actual, " + expected.values().stream().mapToInt(Integer::intValue).sum()
                + " expected, " + problems + " differences");
        System.exit(problems == 0 ? 0 : 1);
    }

    /**
     * Rows of {@code target, symbol, level, required, forbidden}: the static attention level and
     * comma-separated rule codes that must or must not fire.
     */
    private static int checkRisk(Path expectedFile, Path atlas) throws Exception {
        if (!Files.exists(expectedFile)) {
            return 0;
        }
        List<Map<String, Object>> eps = new ArrayList<>();
        for (String line : Files.readAllLines(atlas.resolve("entrypoints.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                eps.add(Json.parseObject(line));
            }
        }
        Map<String, Map<String, Object>> effectsById = new HashMap<>();
        for (String line : Files.readAllLines(atlas.resolve("effects.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                Map<String, Object> r = Json.parseObject(line);
                effectsById.put(String.valueOf(r.get("id")), r);
            }
        }
        Risk.Context context = Risk.Context.from(eps);
        Map<String, Risk.Assessment> byKey = new HashMap<>();
        for (Map<String, Object> ep : eps) {
            Map<String, Object> fx = effectsById.getOrDefault(String.valueOf(ep.get("id")), Map.of());
            List<Map<String, Object>> effects = new ArrayList<>();
            if (fx.get("effects") instanceof List<?> l) {
                for (Object o : l) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) o;
                    effects.add(m);
                }
            }
            Object returns = fx.get("entryReturns");
            byKey.put(ep.get("target") + "\t" + Query.symbol(String.valueOf(ep.get("cls")), String.valueOf(ep.get("method"))),
                    Risk.assess(ep, effects, returns == null ? null : returns.toString(), context));
        }
        int problems = 0;
        List<String> rows = Files.readAllLines(expectedFile, StandardCharsets.UTF_8);
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) {
                continue;
            }
            String[] cols = row.split("\t", -1);
            Risk.Assessment a = byKey.get(cols[0] + "\t" + cols[1]);
            if (a == null) {
                problems++;
                System.out.println("RISK     no entry point " + cols[0] + " " + cols[1]);
                continue;
            }
            List<String> codes = java.util.stream.Stream.concat(a.reasons().stream(), a.moduleReasons().stream()).map(Risk.Reason::code).toList();
            if (!a.level().name().equals(cols[2])) {
                problems++;
                System.out.println("RISK LEVEL        " + cols[1] + " expected " + cols[2] + " got " + a.level() + " " + codes);
            }
            for (String c : cols.length > 3 ? cols[3].split(",") : new String[0]) {
                if (!c.isBlank() && !codes.contains(c.strip())) {
                    problems++;
                    System.out.println("RISK MISSING      " + cols[1] + " " + c.strip() + "  got " + codes);
                }
            }
            for (String c : cols.length > 4 ? cols[4].split(",") : new String[0]) {
                if (!c.isBlank() && codes.contains(c.strip())) {
                    problems++;
                    System.out.println("RISK FORBIDDEN    " + cols[1] + " " + c.strip());
                }
            }
        }
        return problems;
    }

    /**
     * Rows of {@code rule, file, symbol, level, operations}: every place the report groups by, with
     * the sorted {@code Class.method} list of the operations reaching it. The set must match exactly.
     */
    private static int checkPlaces(Path expectedFile, Path fixture, Path atlas) throws Exception {
        if (!Files.exists(expectedFile)) {
            return 0;
        }
        List<Places.Input> inputs = Places.inputs(AtlasStore.open(fixture, atlas));
        Map<String, String> symbolById = new HashMap<>();
        for (Places.Input in : inputs) {
            symbolById.put(in.id(), Query.symbol(String.valueOf(in.ep().get("cls")), String.valueOf(in.ep().get("method"))));
        }
        java.util.Set<String> actual = new java.util.TreeSet<>();
        for (Places.Place p : Places.derive(inputs).places()) {
            List<String> ops = new ArrayList<>(p.ops().stream().map(symbolById::get).toList());
            java.util.Collections.sort(ops);
            actual.add(String.join("\t", p.code(), p.file(), p.symbol(), p.level().name(), String.join(",", ops)));
        }
        java.util.Set<String> expected = new java.util.TreeSet<>();
        List<String> rows = Files.readAllLines(expectedFile, StandardCharsets.UTF_8);
        for (String row : rows.subList(1, rows.size())) {
            if (!row.isBlank()) {
                expected.add(row.strip());
            }
        }
        int problems = 0;
        for (String e : expected) {
            if (!actual.contains(e)) {
                problems++;
                System.out.println("PLACE MISSING  " + e);
            }
        }
        for (String a : actual) {
            if (!expected.contains(a)) {
                problems++;
                System.out.println("PLACE EXTRA    " + a);
            }
        }
        return problems;
    }

    /**
     * The quick check's mechanical half, without a model: the hypothesis catalogue covers every
     * rule; plan, next and record handle a good draft, a bad one and a missing one as the plan
     * says; the full plan's size and estimate match {@code expected/audit/plan.tsv}.
     */
    private static int checkAudit(Path fixture, Path atlas) throws Exception {
        Path expected = fixture.resolve("expected/audit");
        if (!Files.exists(expected)) {
            return 0;
        }
        int problems = 0;
        Map<String, Hypotheses.Hypothesis> hypotheses = Hypotheses.load();
        if (!hypotheses.keySet().equals(Risk.RULES.keySet())) {
            problems++;
            System.out.println("AUDIT hypotheses.json rules " + hypotheses.keySet() + " differ from Risk.RULES " + Risk.RULES.keySet());
        }
        for (Hypotheses.Hypothesis h : hypotheses.values()) {
            if (h.inScope() && h.mitigations().isEmpty() && !h.code().equals(Places.NO_HINT)) {
                problems++;
                System.out.println("AUDIT " + h.code() + " is in scope but names no mitigation");
            }
        }
        Path audit = Files.createTempDirectory("audit-golden").resolve(".reliability").resolve("audit");

        String plan1 = audit(fixture, atlas, audit, "plan", "--places", "PL-2ba53763,PL-55683365,PL-99da8683,PL-a39aeb08,PL-d0e0c22d,PL-4e2d7e2c,PL-6a66916e,PL-6f821a0f");
        String id1 = planId(plan1);
        try {
            audit(fixture, atlas, audit, "next", "R00000000T000000Z");
            problems++;
            System.out.println("AUDIT next accepted a plan id that is not the current plan");
        } catch (IllegalArgumentException expectedRefusal) {
            // The refusal is the check.
        }
        String batch1 = batchOf(audit(fixture, atlas, audit, "next", id1));
        Files.createDirectories(audit.resolve("drafts"));
        Files.copy(expected.resolve("draft-ok.jsonl"), audit.resolve("drafts").resolve(batch1 + ".jsonl"));
        audit(fixture, atlas, audit, "record", batch1);
        Map<String, Map<String, Object>> places = byKey(audit.resolve("places.jsonl"), "placeId");
        problems += expectPlace(places, "PL-2ba53763", "CONFIRMED", "CURRENT", null);
        problems += expectPlace(places, "PL-55683365", "CONFIRMED", "CURRENT", null);
        problems += expectPlace(places, "PL-99da8683", "CONFIRMED", "CURRENT", null);
        problems += expectPlace(places, "PL-a39aeb08", "MITIGATED", "CURRENT", null);
        problems += expectPlace(places, "PL-d0e0c22d", "REFUTED", "CURRENT", null);
        problems += expectPlace(places, "PL-4e2d7e2c", null, "UNJUDGED", "partial");
        problems += expectPlace(places, "PL-6a66916e", "CONFIRMED", "CURRENT", null);
        problems += expectPlace(places, "PL-6f821a0f", "REFUTED", "CURRENT", null);
        if (places.containsKey("PL-6a66916e") && !"MITIGATED".equals(places.get("PL-6a66916e").get("draftVerdict"))) {
            problems++;
            System.out.println("AUDIT a MITIGATED draft that names a remaining failure was not recorded as CONFIRMED from MITIGATED");
        }
        if (places.containsKey("PL-55683365") && !"MEDIUM".equals(places.get("PL-55683365").get("confidence"))) {
            problems++;
            System.out.println("AUDIT a NOT_CHECKED mitigation did not cap HIGH confidence at MEDIUM");
        }
        Map<String, Map<String, Object>> stored = byKey(audit.resolve("places").resolve(batch1 + ".jsonl"), "placeId");
        problems += expectOpened(stored, "PL-a39aeb08", List.of("src/main/java/com/acme/care/billing/persistence/PaypalReceipt.java"));
        problems += expectOpened(stored, "PL-2ba53763", List.of());
        Map<String, String> idBySymbol = new HashMap<>();
        for (String line : Files.readAllLines(atlas.resolve("entrypoints.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                Map<String, Object> r = Json.parseObject(line);
                idBySymbol.put(Query.symbol(String.valueOf(r.get("cls")), String.valueOf(r.get("method"))), String.valueOf(r.get("id")));
            }
        }
        Map<String, Map<String, Object>> verdicts = byKey(audit.resolve("verdicts.jsonl"), "id");
        problems += expectVerdict(verdicts, idBySymbol.get("BillingWebhookController.receiveStripeEvent"), "RED");
        problems += expectVerdict(verdicts, idBySymbol.get("PatternsController.txTemplate"), "GREEN");
        problems += expectVerdict(verdicts, idBySymbol.get("PatternsController.fire"), null);
        if (!Files.exists(audit.getParent().resolve("backlog.md"))
                || !Files.readString(audit.getParent().resolve("backlog.md"), StandardCharsets.UTF_8).contains("PL-2ba53763")) {
            problems++;
            System.out.println("AUDIT backlog.md does not list the confirmed place PL-2ba53763");
        }

        String id2 = planId(audit(fixture, atlas, audit, "plan", "--places", "PL-4e2d7e2c,PL-076fa0b4,PL-828ebd1f,PL-711bd529,PL-77298363"));
        String batch2 = batchOf(audit(fixture, atlas, audit, "next", id2));
        Files.copy(expected.resolve("draft-bad.jsonl"), audit.resolve("drafts").resolve(batch2 + ".jsonl"));
        audit(fixture, atlas, audit, "record", batch2);
        Map<String, Map<String, Object>> bad = byKey(audit.resolve("places").resolve(batch2 + ".jsonl"), "placeId");
        problems += expectRejected(bad, "PL-4e2d7e2c", "MISMATCH");
        problems += expectRejected(bad, "PL-076fa0b4", "lacks");
        problems += expectRejected(bad, "PL-828ebd1f", "BROKEN");
        problems += expectRejected(bad, "PL-711bd529", "needs");
        problems += expectRejected(bad, "PL-77298363", "sets tier");

        String id3 = planId(audit(fixture, atlas, audit, "plan", "--places", "PL-e4f53a00"));
        String batch3 = batchOf(audit(fixture, atlas, audit, "next", id3));
        audit(fixture, atlas, audit, "record", batch3);
        problems += expectPlace(byKey(audit.resolve("places.jsonl"), "placeId"), "PL-e4f53a00", null, "UNJUDGED", "no-draft");

        Path fresh = Files.createTempDirectory("audit-golden-plan").resolve("audit");
        audit(fixture, atlas, fresh, "plan", "--model", "haiku");
        Map<String, Object> plan = Json.parseObject(Files.readString(fresh.resolve("plan.json"), StandardCharsets.UTF_8));
        int tokens = 0;
        for (Object b : (List<?>) plan.get("batches")) {
            tokens += ((Number) ((Map<?, ?>) b).get("bodyTokens")).intValue();
        }
        String got = String.join("\t", String.valueOf(((List<?>) plan.get("places")).size()), String.valueOf(((List<?>) plan.get("batches")).size()),
                String.valueOf(tokens), String.format(java.util.Locale.ROOT, "%.2f", ((Number) ((Map<?, ?>) plan.get("estimate")).get("usd")).doubleValue()));
        List<String> pinned = Files.readAllLines(expected.resolve("plan.tsv"), StandardCharsets.UTF_8);
        if (pinned.size() < 2 || !pinned.get(1).strip().equals(got)) {
            problems++;
            System.out.println("AUDIT plan (places, batches, packet tokens, usd) expected " + (pinned.size() < 2 ? "?" : pinned.get(1).strip()) + " got " + got);
        }
        return problems;
    }

    private static String audit(Path fixture, Path atlas, Path audit, String... args) throws Exception {
        String[] argv = new String[args.length + 1];
        argv[0] = "audit";
        System.arraycopy(args, 0, argv, 1, args.length);
        return Audit.run(fixture, atlas, audit, new Args(argv));
    }

    private static String planId(String planOutput) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("--yes (R\\d{8}T\\d{6}Z)").matcher(planOutput);
        if (!m.find()) {
            throw new IllegalStateException("audit plan printed no plan id:\n" + planOutput);
        }
        return m.group(1);
    }

    private static String batchOf(String nextOutput) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\tbatch\t(B-\\S+)").matcher(nextOutput);
        if (!m.find()) {
            throw new IllegalStateException("audit next issued no batch:\n" + nextOutput);
        }
        return m.group(1);
    }

    private static Map<String, Map<String, Object>> byKey(Path jsonl, String key) throws Exception {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (Files.exists(jsonl)) {
            for (String line : Files.readAllLines(jsonl, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    Map<String, Object> r = Json.parseObject(line);
                    out.put(String.valueOf(r.get(key)), r);
                }
            }
        }
        return out;
    }

    private static int expectPlace(Map<String, Map<String, Object>> places, String id, String verdict, String status, String reason) {
        Map<String, Object> p = places.get(id);
        if (p == null || !java.util.Objects.equals(verdict, p.get("verdict")) || !status.equals(p.get("status"))
                || reason != null && !reason.equals(p.get("reason"))) {
            System.out.println("AUDIT PLACE " + id + " expected " + verdict + "/" + status + (reason == null ? "" : "/" + reason) + " got "
                    + (p == null ? "nothing" : p.get("verdict") + "/" + p.get("status") + "/" + p.get("reason")));
            return 1;
        }
        return 0;
    }

    private static int expectOpened(Map<String, Map<String, Object>> rows, String id, List<String> files) {
        Object got = rows.containsKey(id) ? rows.get(id).get("openedFiles") : null;
        if (!files.equals(got)) {
            System.out.println("AUDIT OPENED " + id + " expected " + files + " got " + got);
            return 1;
        }
        return 0;
    }

    private static int expectVerdict(Map<String, Map<String, Object>> verdicts, String opId, String colour) {
        Map<String, Object> v = verdicts.get(opId);
        boolean ok = colour == null ? v == null : v != null && colour.equals(v.get("colour")) && Boolean.TRUE.equals(v.get("triage"));
        if (!ok) {
            System.out.println("AUDIT VERDICT " + opId + " expected " + colour + " got " + (v == null ? "no row" : v.get("colour")));
            return 1;
        }
        return 0;
    }

    private static int expectRejected(Map<String, Map<String, Object>> rows, String id, String problem) {
        Map<String, Object> r = rows.get(id);
        boolean ok = r != null && "UNJUDGED".equals(r.get("status")) && "rejected".equals(r.get("reason"))
                && String.valueOf(r.get("problems")).contains(problem);
        if (!ok) {
            System.out.println("AUDIT REJECT " + id + " expected rejected for '" + problem + "' got "
                    + (r == null ? "nothing" : r.get("status") + "/" + r.get("reason") + " " + r.get("problems")));
            return 1;
        }
        return 0;
    }

    /**
     * Rows of {@code target, symbol, required, forbidden}: comma-separated signals of the entry
     * point's effects; {@code signal@tx} also requires the effect to be inside a transaction.
     */
    private static int checkSignals(Path expectedFile, Path atlas) throws Exception {
        if (!Files.exists(expectedFile)) {
            return 0;
        }
        Map<String, String> idByKey = new HashMap<>();
        for (String line : Files.readAllLines(atlas.resolve("entrypoints.jsonl"), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                Map<String, Object> r = Json.parseObject(line);
                idByKey.put(r.get("target") + "\t" + Query.symbol(String.valueOf(r.get("cls")), String.valueOf(r.get("method"))),
                        String.valueOf(r.get("id")));
            }
        }
        Map<String, List<String>> signalsById = new HashMap<>();
        for (String line : Files.readAllLines(atlas.resolve("effects.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> r = Json.parseObject(line);
            List<String> signals = new ArrayList<>();
            if (r.get("effects") instanceof List<?> effects) {
                for (Object o : effects) {
                    Map<?, ?> e = (Map<?, ?>) o;
                    signals.add(String.valueOf(e.get("signal")));
                    if (Boolean.TRUE.equals(e.get("inTx"))) {
                        signals.add(e.get("signal") + "@tx");
                    }
                }
            }
            signalsById.put(String.valueOf(r.get("id")), signals);
        }
        int problems = 0;
        List<String> rows = Files.readAllLines(expectedFile, StandardCharsets.UTF_8);
        for (String row : rows.subList(1, rows.size())) {
            if (row.isBlank()) {
                continue;
            }
            String[] cols = row.split("\t", -1);
            String id = idByKey.get(cols[0] + "\t" + cols[1]);
            if (id == null) {
                problems++;
                System.out.println("SIGNALS  no entry point " + cols[0] + " " + cols[1]);
                continue;
            }
            List<String> got = signalsById.getOrDefault(id, List.of());
            for (String s : cols[2].split(",")) {
                if (!s.isBlank() && !got.contains(s.strip())) {
                    problems++;
                    System.out.println("SIGNAL MISSING    " + cols[1] + " " + s.strip() + "  got " + new java.util.TreeSet<>(got));
                }
            }
            for (String s : cols.length > 3 ? cols[3].split(",") : new String[0]) {
                if (!s.isBlank() && got.contains(s.strip())) {
                    problems++;
                    System.out.println("SIGNAL FORBIDDEN  " + cols[1] + " " + s.strip());
                }
            }
        }
        return problems;
    }
}
