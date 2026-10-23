package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * List prices of one model alias from the bundled {@code audit/models.json}, in USD per million
 * tokens. Estimates only: the plugin never sees a bill.
 */
record Pricing(String alias, String id, double input, double output, double cacheRead, double cacheWriteFactor, String asOf) {

    static Pricing of(String alias) throws IOException {
        Map<String, Object> doc;
        try (InputStream in = Pricing.class.getResourceAsStream("/audit/models.json")) {
            if (in == null) {
                throw new IllegalStateException("audit/models.json is missing from the scanner build");
            }
            doc = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        Map<?, ?> models = (Map<?, ?>) doc.get("models");
        if (!(models.get(alias) instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("--model " + alias + " is not one of " + String.join(", ", models.keySet().stream().map(String::valueOf).toList()));
        }
        return new Pricing(alias, String.valueOf(m.get("id")), num(m.get("input")), num(m.get("output")), num(m.get("cacheRead")),
                num(m.get("cacheWriteFactor")), String.valueOf(doc.get("asOf")));
    }

    double usd(long freshIn, long cachedIn, long out) {
        return usd(freshIn, cachedIn, out, cacheWriteFactor);
    }

    /** With a cache-write premium other than the model's default, for a session that caches for an hour. */
    double usd(long freshIn, long cachedIn, long out, double writeFactor) {
        return (freshIn * input * writeFactor + cachedIn * cacheRead + out * output) / 1_000_000.0;
    }

    Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alias", alias);
        m.put("id", id);
        m.put("input", input);
        m.put("output", output);
        m.put("cacheRead", cacheRead);
        m.put("cacheWriteFactor", cacheWriteFactor);
        m.put("asOf", asOf);
        return m;
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
