package studio.volkoff.atlas.report;

import studio.volkoff.atlas.query.AtlasStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places in the code where one fix covers every operation that reaches them: a rule code, the
 * file of the rule's first evidence and the method enclosing it. Only HIGH and MEDIUM reasons
 * form places. The evidence line is an attribute, not part of the key, so a shifted line keeps
 * the place and its id.
 */
public final class Places {

    /** One operation with its effects and the assessment computed from them. */
    public record Input(Map<String, Object> ep, List<Map<String, Object>> effects, Risk.Assessment assessment) {
        public String id() {
            return String.valueOf(ep.get("id"));
        }
    }

    /**
     * @param ops           operation ids in atlas order
     * @param reasonEffects the effects the rule itself cited, from every operation, deduplicated
     * @param sampleOp      the first operation reaching the place, whose effect {@code sampleEffect} is the evidence to show
     */
    public record Place(String id, String code, Risk.Level level, String file, String symbol, int line,
                        List<String> ops, List<Map<String, Object>> reasonEffects, int maxScore,
                        String sampleOp, int sampleEffect) {
        public String key() {
            return Places.key(code, file, symbol);
        }

        public String title() {
            return Risk.RULES.get(code)[0];
        }
    }

    public record Result(List<Place> places, Map<String, List<String>> placeIdsByOp) {
    }

    private Places() {
    }

    public static String key(String code, String file, String symbol) {
        return code + "|" + file + "|" + symbol;
    }

    /** Every operation of the atlas, assessed as the report assesses it. */
    public static List<Input> inputs(AtlasStore atlas) throws IOException {
        Map<String, Map<String, Object>> effectsById = new HashMap<>();
        for (Map<String, Object> row : atlas.effects()) {
            effectsById.put(String.valueOf(row.get("id")), row);
        }
        List<Map<String, Object>> eps = atlas.entrypoints();
        Risk.Context context = Risk.Context.from(eps);
        List<Input> out = new ArrayList<>();
        for (Map<String, Object> ep : eps) {
            Map<String, Object> fx = effectsById.get(String.valueOf(ep.get("id")));
            List<Map<String, Object>> effects = fx == null ? List.of() : maps(fx.get("effects"));
            Object returns = fx == null ? null : fx.get("entryReturns");
            out.add(new Input(ep, effects, Risk.assess(ep, effects, returns == null ? null : returns.toString(), context)));
        }
        return out;
    }

    /** Places ordered like the page's cards: HIGH first, then the worst operation score, then reach, then id. */
    public static Result derive(List<Input> inputs) {
        Map<String, Builder> byKey = new LinkedHashMap<>();
        Map<String, List<String>> keysByOp = new LinkedHashMap<>();
        for (Input in : inputs) {
            List<String> keys = new ArrayList<>();
            for (Risk.Reason r : in.assessment().reasons()) {
                if (r.level() != Risk.Level.HIGH && r.level() != Risk.Level.MEDIUM || r.evidence().isEmpty()) {
                    continue;
                }
                int first = r.evidence().get(0);
                if (first < 0 || first >= in.effects().size()) {
                    continue;
                }
                Map<String, Object> e = in.effects().get(first);
                String key = key(r.code(), String.valueOf(e.get("file")), String.valueOf(e.get("symbol")));
                Builder b = byKey.computeIfAbsent(key, k -> new Builder(r.code(), String.valueOf(e.get("file")),
                        String.valueOf(e.get("symbol")), in.id(), first));
                b.add(in, r, line(e));
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
            keysByOp.put(in.id(), keys);
        }
        Map<String, String> ids = assignIds(byKey.keySet());
        List<Place> places = new ArrayList<>();
        byKey.forEach((key, b) -> places.add(b.build(ids.get(key))));
        places.sort(Comparator.comparingInt((Place p) -> p.level().ordinal())
                .thenComparing(Comparator.comparingInt(Place::maxScore).reversed())
                .thenComparing(Comparator.comparingInt((Place p) -> p.ops().size()).reversed())
                .thenComparing(Place::id));
        Map<String, List<String>> placeIdsByOp = new LinkedHashMap<>();
        keysByOp.forEach((op, keys) -> placeIdsByOp.put(op, keys.stream().map(ids::get).toList()));
        return new Result(places, placeIdsByOp);
    }

    private static final class Builder {
        final String code;
        final String file;
        final String symbol;
        final String sampleOp;
        final int sampleEffect;
        Risk.Level level = Risk.Level.NONE;
        int line = Integer.MAX_VALUE;
        int maxScore;
        final List<String> ops = new ArrayList<>();
        final List<Map<String, Object>> reasonEffects = new ArrayList<>();
        final Set<String> seenEffects = new HashSet<>();

        Builder(String code, String file, String symbol, String sampleOp, int sampleEffect) {
            this.code = code;
            this.file = file;
            this.symbol = symbol;
            this.sampleOp = sampleOp;
            this.sampleEffect = sampleEffect;
        }

        void add(Input in, Risk.Reason r, int firstLine) {
            if (r.level().ordinal() < level.ordinal()) {
                level = r.level();
            }
            line = Math.min(line, firstLine);
            maxScore = Math.max(maxScore, in.assessment().score());
            if (!ops.contains(in.id())) {
                ops.add(in.id());
            }
            for (int i : r.evidence()) {
                if (i < 0 || i >= in.effects().size()) {
                    continue;
                }
                Map<String, Object> e = in.effects().get(i);
                if (seenEffects.add(e.get("file") + ":" + e.get("line") + ":" + e.get("detail"))) {
                    reasonEffects.add(e);
                }
            }
        }

        Place build(String id) {
            return new Place(id, code, level, file, symbol, line == Integer.MAX_VALUE ? 0 : line, List.copyOf(ops),
                    List.copyOf(reasonEffects), maxScore, sampleOp, sampleEffect);
        }
    }

    /** {@code PL-} and the first 8 hex digits of the key's SHA-1, 12 when two keys share 8, like entry-point ids. */
    private static Map<String, String> assignIds(Set<String> keys) {
        Map<String, String> full = new LinkedHashMap<>();
        Map<String, Integer> shortCount = new HashMap<>();
        for (String k : keys) {
            String hex = sha1(k);
            full.put(k, hex);
            shortCount.merge(hex.substring(0, 8), 1, Integer::sum);
        }
        Map<String, String> out = new LinkedHashMap<>();
        full.forEach((k, hex) -> out.put(k, "PL-" + (shortCount.get(hex.substring(0, 8)) > 1 ? hex.substring(0, 12) : hex.substring(0, 8))));
        return out;
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte x : d) {
                hex.append(String.format("%02x", x));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int line(Map<String, Object> e) {
        return e.get("line") instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }
}
