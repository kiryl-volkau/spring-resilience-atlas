import studio.volkoff.atlas.json.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Prints one row per {@code claude -p --output-format json} result in a directory: tokens by
 * model, list-price cost, turns and duration. Launched by {@code measure-cost.sh}.
 */
public final class CostReport {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        List<Path> results;
        try (Stream<Path> files = Files.list(dir)) {
            results = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        System.out.println("run\tmodel\tinput\toutput\tcache_write\tcache_read\tusd\tturns\tseconds");
        double total = 0;
        for (Path p : results) {
            String name = p.getFileName().toString().replace(".json", "");
            String text = Files.readString(p, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                System.out.println(name + "\t(no result: see " + name + ".err)");
                continue;
            }
            Map<String, Object> r = Json.parseObject(text.substring(text.indexOf('{')));
            double usd = num(r.get("total_cost_usd"));
            total += usd;
            String turns = String.valueOf(r.get("num_turns"));
            String seconds = String.format(Locale.ROOT, "%.0f", num(r.get("duration_ms")) / 1000);
            if (r.get("modelUsage") instanceof Map<?, ?> byModel && !byModel.isEmpty()) {
                boolean first = true;
                for (Map.Entry<?, ?> e : byModel.entrySet()) {
                    Map<?, ?> u = (Map<?, ?>) e.getValue();
                    System.out.printf(Locale.ROOT, "%s\t%s\t%.0f\t%.0f\t%.0f\t%.0f\t%.4f\t%s\t%s%n",
                            first ? name : "", e.getKey(), num(u.get("inputTokens")), num(u.get("outputTokens")),
                            num(u.get("cacheCreationInputTokens")), num(u.get("cacheReadInputTokens")),
                            num(u.get("costUSD")), first ? turns : "", first ? seconds : "");
                    first = false;
                }
            } else if (r.get("usage") instanceof Map<?, ?> u) {
                System.out.printf(Locale.ROOT, "%s\t-\t%.0f\t%.0f\t%.0f\t%.0f\t%.4f\t%s\t%s%n", name,
                        num(u.get("input_tokens")), num(u.get("output_tokens")),
                        num(u.get("cache_creation_input_tokens")), num(u.get("cache_read_input_tokens")), usd, turns, seconds);
            }
            if (Boolean.TRUE.equals(r.get("is_error"))) {
                System.out.println("\t(run ended with an error: " + r.get("subtype") + ")");
            }
        }
        System.out.printf(Locale.ROOT, "# total list-price cost: $%.2f%n", total);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
