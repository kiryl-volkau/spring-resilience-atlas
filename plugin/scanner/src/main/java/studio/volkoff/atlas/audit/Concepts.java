package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The flow-level failure patterns of {@code audit/concepts.json}, and their match over a recorded
 * flow. A match is structure only (step kinds, transactions, durability, links,
 * acknowledgements): it tells the scenario author which patterns to try, not that one bites.
 */
public final class Concepts {

    public record Concept(String id, String name, String hypothesis, String questions, List<String> triggers,
                          String stepFix, String conceptFix, String verify) {
    }

    /** A matched pattern and the steps its structure rests on, in flow order. */
    public record Match(String concept, List<String> steps) {
    }

    private static Map<String, Concept> cache;

    private Concepts() {
    }

    public static synchronized Map<String, Concept> load() throws IOException {
        if (cache != null) {
            return cache;
        }
        try (InputStream in = Concepts.class.getResourceAsStream("/audit/concepts.json")) {
            if (in == null) {
                throw new IllegalStateException("audit/concepts.json is missing from the scanner build");
            }
            Map<String, Object> doc = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Concept> out = new LinkedHashMap<>();
            ((Map<?, ?>) doc.get("concepts")).forEach((k, v) -> {
                Map<?, ?> m = (Map<?, ?>) v;
                List<String> triggers = new ArrayList<>();
                ((List<?>) m.get("triggers")).forEach(t -> triggers.add(String.valueOf(t)));
                out.put(String.valueOf(k), new Concept(String.valueOf(k), str(m.get("name")), str(m.get("hypothesis")), str(m.get("questions")),
                        List.copyOf(triggers), str(m.get("stepFix")), str(m.get("conceptFix")), str(m.get("verify"))));
            });
            cache = out;
            return out;
        }
    }

    /**
     * @param webhooks   entry points tagged as webhooks: their trigger is redelivered like a message
     * @param idempotent entry points whose path the scan saw check for duplicates
     */
    public static List<Match> match(Map<String, Object> flow, Set<String> webhooks, Set<String> idempotent) {
        List<Map<String, Object>> steps = maps(flow.get("steps"));
        Map<String, Map<String, Object>> byId = new HashMap<>();
        steps.forEach(s -> byId.put(String.valueOf(s.get("id")), s));
        Map<String, String> entryOf = new HashMap<>();
        maps(flow.get("entries")).forEach(e -> entryOf.put(String.valueOf(e.get("segment")), String.valueOf(e.get("entrypointId"))));
        Map<String, Map<String, Object>> txById = new HashMap<>();
        maps(flow.get("transactions")).forEach(t -> txById.put(String.valueOf(t.get("id")), t));
        Map<String, Set<String>> found = new LinkedHashMap<>();

        for (Map<String, Object> a : maps(flow.get("acknowledgements"))) {
            if (Boolean.FALSE.equals(a.get("durableBefore"))) {
                Set<String> m = found.computeIfAbsent("ack-before-durable", k -> new LinkedHashSet<>());
                for (Map<String, Object> s : steps) {
                    if (a.get("segment").equals(s.get("segment")) && Boolean.FALSE.equals(s.get("durable"))) {
                        m.add(id(s));
                    }
                }
                m.add(String.valueOf(a.get("stepId")));
            }
        }
        for (Map<String, Object> s : steps) {
            if ("EXTERNAL_CALL".equals(s.get("kind")) && s.get("tx") != null && !checkedBefore(steps, s)) {
                Set<String> m = found.computeIfAbsent("external-effect-inside-local-tx", k -> new LinkedHashSet<>());
                Map<String, Object> tx = txById.get(String.valueOf(s.get("tx")));
                if (tx != null) {
                    m.add(String.valueOf(tx.get("begin")));
                }
                m.add(id(s));
                if (tx != null) {
                    m.add(String.valueOf(tx.get("commit")));
                }
            }
        }
        Map<String, Map<String, Object>> firstTarget = new HashMap<>();
        for (Map<String, Object> s : steps) {
            String kind = String.valueOf(s.get("kind"));
            if ((kind.equals("MESSAGE_SEND") || kind.equals("EXTERNAL_CALL")) && s.get("tx") != null) {
                String tx = String.valueOf(s.get("tx"));
                Map<String, Object> first = firstTarget.putIfAbsent(tx, s);
                if (first != null && !first.get("to").equals(s.get("to"))) {
                    Set<String> m = found.computeIfAbsent("fan-out-without-outbox", k -> new LinkedHashSet<>());
                    m.add(id(first));
                    m.add(id(s));
                }
            }
        }
        Map<String, List<Map<String, Object>>> bySegment = new LinkedHashMap<>();
        steps.forEach(s -> bySegment.computeIfAbsent(String.valueOf(s.get("segment")), k -> new ArrayList<>()).add(s));
        bySegment.forEach((seg, list) -> {
            Map<String, Object> start = list.get(0);
            String entry = entryOf.get(seg);
            boolean redelivered = "MESSAGE_RECEIVE".equals(start.get("kind")) || "TRIGGER".equals(start.get("kind")) && webhooks.contains(entry);
            boolean checked = idempotent.contains(entry) || list.stream().anyMatch(s -> "IDEMPOTENCY_CHECK".equals(s.get("kind")));
            List<String> sinks = new ArrayList<>();
            for (Map<String, Object> s : list) {
                if ("DB_WRITE".equals(s.get("kind")) || "EXTERNAL_CALL".equals(s.get("kind"))) {
                    sinks.add(id(s));
                }
            }
            if (redelivered && !checked && !sinks.isEmpty()) {
                Set<String> m = found.computeIfAbsent("at-least-once-without-idempotent-sink", k -> new LinkedHashSet<>());
                m.add(id(start));
                m.addAll(sinks);
            }
        });
        for (Map<String, Object> l : maps(flow.get("links"))) {
            boolean accepted = l.get("decision") instanceof Map<?, ?> d && Boolean.TRUE.equals(d.get("accepted"));
            if (!accepted || !"OUTBOUND_WEBHOOK".equals(l.get("kind")) || l.get("toStep") == null) {
                continue;
            }
            Map<String, Object> from = byId.get(String.valueOf(l.get("fromStep")));
            Map<String, Object> to = byId.get(String.valueOf(l.get("toStep")));
            if (from == null || to == null) {
                continue;
            }
            if (!"CORRELATION_FIELD".equals(l.get("basis")) && !looksUpBeforeWriting(bySegment.get(String.valueOf(to.get("segment"))))) {
                Set<String> m = found.computeIfAbsent("uncorrelated-callback", k -> new LinkedHashSet<>());
                m.add(id(from));
                m.add(id(to));
            }
            Map<String, Object> tx = from.get("tx") == null ? null : txById.get(String.valueOf(from.get("tx")));
            Map<String, Object> commit = tx == null ? null : byId.get(String.valueOf(tx.get("commit")));
            if (commit != null && n(commit) > n(from)) {
                Set<String> m = found.computeIfAbsent("callback-before-commit", k -> new LinkedHashSet<>());
                m.add(id(from));
                m.add(id(commit));
                m.add(id(to));
            }
        }
        List<Match> out = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        steps.forEach(s -> order.put(id(s), n(s)));
        found.forEach((k, v) -> {
            List<String> sorted = new ArrayList<>(v);
            sorted.sort((x, y) -> Integer.compare(order.getOrDefault(x, 0), order.getOrDefault(y, 0)));
            out.add(new Match(k, sorted));
        });
        return out;
    }

    /** An idempotency check earlier in the same segment. */
    private static boolean checkedBefore(List<Map<String, Object>> steps, Map<String, Object> at) {
        for (Map<String, Object> s : steps) {
            if (n(s) < n(at) && at.get("segment").equals(s.get("segment")) && "IDEMPOTENCY_CHECK".equals(s.get("kind"))) {
                return true;
            }
        }
        return false;
    }

    /** The segment reads or checks something before its first write: where a lookup of the request would be. */
    private static boolean looksUpBeforeWriting(List<Map<String, Object>> segment) {
        if (segment == null) {
            return false;
        }
        for (Map<String, Object> s : segment) {
            String kind = String.valueOf(s.get("kind"));
            if (kind.equals("DB_WRITE") || kind.equals("EXTERNAL_CALL") || kind.equals("MESSAGE_SEND")) {
                return false;
            }
            if (kind.equals("IDEMPOTENCY_CHECK") || kind.equals("DB_READ")) {
                return true;
            }
        }
        return false;
    }

    private static String id(Map<String, Object> s) {
        return String.valueOf(s.get("id"));
    }

    private static int n(Map<String, Object> s) {
        return s.get("n") instanceof Number x ? x.intValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
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
