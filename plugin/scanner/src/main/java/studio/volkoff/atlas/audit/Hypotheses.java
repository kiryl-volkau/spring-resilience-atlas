package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The bundled hypothesis catalogue: what the quick check asks about each rule code. */
public final class Hypotheses {

    /**
     * @param name the key a verdict's {@code mitigationsChecked} must use, compared trimmed and case-insensitively
     * @param kind {@code refutes} or {@code mitigates}
     */
    public record Mitigation(String name, String kind, String text) {
    }

    public record Hypothesis(String code, boolean inScope, String hypothesis, String check, List<Mitigation> mitigations,
                             String cite, String fix, String verify, List<String> declares, String reason) {
    }

    private static Map<String, Hypothesis> cache;

    private Hypotheses() {
    }

    /** Every entry of {@code audit/hypotheses.json}, in file order, keyed by rule code. */
    public static synchronized Map<String, Hypothesis> load() throws IOException {
        if (cache != null) {
            return cache;
        }
        String text;
        try (InputStream in = Hypotheses.class.getResourceAsStream("/audit/hypotheses.json")) {
            if (in == null) {
                throw new IllegalStateException("audit/hypotheses.json is missing from the scanner build");
            }
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Map<String, Hypothesis> out = new LinkedHashMap<>();
        if (Json.parseObject(text).get("rules") instanceof Map<?, ?> rules) {
            rules.forEach((code, v) -> {
                Map<?, ?> m = (Map<?, ?>) v;
                List<Mitigation> mitigations = new ArrayList<>();
                if (m.get("mitigations") instanceof List<?> l) {
                    for (Object o : l) {
                        Map<?, ?> x = (Map<?, ?>) o;
                        mitigations.add(new Mitigation(str(x.get("name")), str(x.get("kind")), str(x.get("text"))));
                    }
                }
                List<String> declares = new ArrayList<>();
                if (m.get("declares") instanceof List<?> l) {
                    l.forEach(d -> declares.add(String.valueOf(d)));
                }
                out.put(String.valueOf(code), new Hypothesis(String.valueOf(code), Boolean.TRUE.equals(m.get("inScope")),
                        str(m.get("hypothesis")), str(m.get("check")), List.copyOf(mitigations), str(m.get("cite")),
                        str(m.get("fix")), str(m.get("verify")), List.copyOf(declares), str(m.get("reason"))));
            });
        }
        cache = out;
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
