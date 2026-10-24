package studio.volkoff.atlas.report;

import studio.volkoff.atlas.effects.Signals;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.Query;

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
 * file of the rule's first evidence and the method enclosing it. For a rule about a transaction
 * the place is the method that opened the transaction instead, since the fix moves that
 * boundary, and evidence under different boundaries forms different places. Every reason forms
 * places, LOW ones too; an analysed operation with effects and no reason gets one
 * {@value #NO_HINT} place of its own, so a sweep can look for what the rules missed. The
 * evidence line is an attribute, not part of the key, so a shifted line keeps the place and its id.
 */
public final class Places {

    /** The rule code of an operation's sweep place: effects on its path, no rule fired. */
    public static final String NO_HINT = "no-hint";
    /** Rules whose evidence only matters inside a transaction: their place is the transaction's boundary. */
    public static final Set<String> TX_RULES = Set.of("async-in-tx", "dual-write-in-tx", "remote-in-tx", "notification-in-tx", "event-in-tx",
            "lock-held-across-remote-call");
    private static final Set<String> CONTEXT_SIGNALS = Set.of(Signals.TX, Signals.IDEMPOTENCY, Signals.LOCK, Signals.RETRY,
            Signals.SWALLOWED, Signals.NO_TIMEOUT, Signals.UNBOUNDED_POOL, Signals.CONFIG);

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

        /** The levels a quick check plans unless asked for more. */
        public boolean defaultScope() {
            return level == Risk.Level.HIGH || level == Risk.Level.MEDIUM;
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
            List<Risk.Reason> reasons = in.assessment().reasons();
            if (reasons.isEmpty() && !stateSignals(in.effects()).isEmpty()) {
                List<Integer> all = new ArrayList<>();
                for (int i = 0; i < in.effects().size(); i++) {
                    all.add(i);
                }
                reasons = List.of(new Risk.Reason(NO_HINT, Risk.Level.NONE, all));
            }
            List<Risk.Reason> all = new ArrayList<>(reasons);
            all.addAll(in.assessment().moduleReasons());
            for (Risk.Reason r : all) {
                for (List<Integer> evidence : groups(r, in.effects())) {
                    if (evidence.isEmpty()) {
                        continue;
                    }
                    int first = evidence.get(0);
                    Map<String, Object> e = in.effects().get(first);
                    String file = String.valueOf(e.get("file"));
                    String symbol = String.valueOf(e.get("symbol"));
                    int line = line(e);
                    String owner = TX_RULES.contains(r.code()) && e.get("txOwner") != null ? String.valueOf(e.get("txOwnerAt")) : "";
                    if (r.code().equals(NO_HINT)) {
                        file = String.valueOf(in.ep().get("file"));
                        symbol = Query.symbol(String.valueOf(in.ep().get("cls")), String.valueOf(in.ep().get("method")));
                        line = in.ep().get("line") instanceof Number n ? n.intValue() : 0;
                    } else if (owner.lastIndexOf(':') > 0) {
                        file = owner.substring(0, owner.lastIndexOf(':'));
                        symbol = String.valueOf(e.get("txOwner"));
                        line = Integer.parseInt(owner.substring(owner.lastIndexOf(':') + 1));
                    }
                    String key = key(r.code(), file, symbol);
                    String f = file;
                    String s = symbol;
                    Builder b = byKey.computeIfAbsent(key, k -> new Builder(r.code(), f, s, in.id(), first));
                    b.add(in, r.level(), evidence, line);
                    if (!keys.contains(key)) {
                        keys.add(key);
                    }
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

    /** The signals on a path that change something: writes, sends, calls, hand-offs, state; not transactions, checks, locks or retries. */
    public static Set<String> stateSignals(List<Map<String, Object>> effects) {
        Set<String> out = new java.util.TreeSet<>();
        for (Map<String, Object> e : effects) {
            out.add(String.valueOf(e.get("signal")));
        }
        out.removeAll(CONTEXT_SIGNALS);
        return out;
    }

    /**
     * The reason's evidence, split by transaction boundary for a {@link #TX_RULES} rule; out-of-range
     * indexes dropped. Evidence outside every transaction is left out once some lies inside one, and a
     * dual write inside a transaction keeps only the boundaries that hold a send. An atlas without
     * owners gives one group, as before.
     */
    private static List<List<Integer>> groups(Risk.Reason r, List<Map<String, Object>> effects) {
        Map<String, List<Integer>> byOwner = new LinkedHashMap<>();
        for (int i : r.evidence()) {
            if (i < 0 || i >= effects.size()) {
                continue;
            }
            Object owner = TX_RULES.contains(r.code()) ? effects.get(i).get("txOwnerAt") : null;
            byOwner.computeIfAbsent(owner == null ? "" : owner.toString(), k -> new ArrayList<>()).add(i);
        }
        if (byOwner.size() > 1) {
            byOwner.remove("");
            if (r.code().equals("dual-write-in-tx")) {
                byOwner.values().removeIf(g -> g.stream().noneMatch(i -> Signals.MESSAGE.equals(effects.get(i).get("signal"))));
            }
            if (byOwner.isEmpty()) {
                return List.of(r.evidence().stream().filter(i -> i >= 0 && i < effects.size()).toList());
            }
        }
        return new ArrayList<>(byOwner.values());
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

        void add(Input in, Risk.Level reasonLevel, List<Integer> evidence, int firstLine) {
            if (reasonLevel.ordinal() < level.ordinal()) {
                level = reasonLevel;
            }
            line = Math.min(line, firstLine);
            maxScore = Math.max(maxScore, in.assessment().score());
            if (!ops.contains(in.id())) {
                ops.add(in.id());
            }
            for (int i : evidence) {
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
