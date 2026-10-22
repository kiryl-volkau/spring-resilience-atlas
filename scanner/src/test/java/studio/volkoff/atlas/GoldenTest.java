package studio.volkoff.atlas;

import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.Query;
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
 * {@code java -cp scanner/dist/resilience-atlas.jar scanner/src/test/java/studio/volkoff/atlas/GoldenTest.java fixtures/spring-golden}.
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
            List<String> codes = a.reasons().stream().map(Risk.Reason::code).toList();
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
