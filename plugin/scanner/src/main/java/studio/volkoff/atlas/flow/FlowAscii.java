package studio.volkoff.atlas.flow;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A recorded flow as text for the terminal: segments, numbered steps, transactions, links, business parts. */
public final class FlowAscii {

    private FlowAscii() {
    }

    public static String render(Map<String, Object> doc) {
        if (!Flows.v2(doc)) {
            return legacy(doc);
        }
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> p : Flows.maps(doc.get("participants"))) {
            names.put(String.valueOf(p.get("id")), String.valueOf(p.get("name")));
        }
        Map<String, Map<String, Object>> entries = new LinkedHashMap<>();
        for (Map<String, Object> e : Flows.maps(doc.get("entries"))) {
            entries.put(String.valueOf(e.get("segment")), e);
        }
        Map<String, Integer> numberOf = new HashMap<>();
        for (Map<String, Object> s : Flows.maps(doc.get("steps"))) {
            numberOf.put(String.valueOf(s.get("id")), ((Number) s.get("n")).intValue());
        }
        Map<String, List<Map<String, Object>>> linksInto = new HashMap<>();
        for (Map<String, Object> l : Flows.maps(doc.get("links"))) {
            if (l.get("toStep") != null) {
                linksInto.computeIfAbsent(String.valueOf(l.get("toStep")), k -> new java.util.ArrayList<>()).add(l);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append(doc.get("id")).append("  ").append(doc.get("title")).append('\n');
        out.append(doc.get("summary")).append('\n');
        String segment = null;
        for (Map<String, Object> s : Flows.maps(doc.get("steps"))) {
            String seg = String.valueOf(s.get("segment"));
            for (Map<String, Object> l : linksInto.getOrDefault(String.valueOf(s.get("id")), List.of())) {
                out.append("   ~~ link ").append(l.get("kind")).append(' ').append(l.get("key") == null ? "" : l.get("key"))
                        .append(" from step ").append(numberOf.get(String.valueOf(l.get("fromStep"))))
                        .append(" (").append(l.get("basis")).append(", ").append(Boolean.TRUE.equals(((Map<?, ?>) l.get("decision")).get("accepted"))
                                ? "accepted, " + l.get("confidence") : "rejected").append(")\n");
            }
            if (!seg.equals(segment)) {
                segment = seg;
                Map<String, Object> e = entries.getOrDefault(seg, Map.of());
                out.append("== ").append(seg).append("  ").append(e.get("kind")).append(' ').append(e.get("subkind")).append(' ')
                        .append(e.get("target")).append("  ").append(e.get("symbol")).append("  started by ").append(e.get("trigger")).append('\n');
            }
            String from = names.get(String.valueOf(s.get("from")));
            String to = names.get(String.valueOf(s.get("to")));
            out.append(String.format("%3d %s  %-16s %s%s  %s", ((Number) s.get("n")).intValue(), s.get("id"), s.get("kind"),
                    from, from.equals(to) ? "" : " -> " + to, s.get("label")));
            if (s.get("tx") != null) {
                out.append("  [").append(s.get("tx")).append(']');
            }
            if (Boolean.FALSE.equals(s.get("durable"))) {
                out.append("  (not durable)");
            }
            out.append('\n');
        }
        for (Map<String, Object> t : Flows.maps(doc.get("transactions"))) {
            out.append("tx ").append(t.get("id")).append(' ').append(t.get("owner")).append(": steps ")
                    .append(numberOf.get(String.valueOf(t.get("begin")))).append('-').append(numberOf.get(String.valueOf(t.get("commit")))).append('\n');
        }
        for (Map<String, Object> bp : Flows.maps(doc.get("businessParts"))) {
            StringBuilder nums = new StringBuilder();
            if (bp.get("steps") instanceof List<?> l) {
                for (Object x : l) {
                    nums.append(nums.length() == 0 ? "" : ",").append(numberOf.get(String.valueOf(x)));
                }
            }
            out.append("part ").append(bp.get("id")).append(' ').append(bp.get("name")).append(": steps ").append(nums)
                    .append(" -> ").append(bp.get("outcome")).append('\n');
        }
        for (Map<String, Object> l : Flows.maps(doc.get("links"))) {
            if (l.get("toStep") == null) {
                boolean accepted = l.get("decision") instanceof Map<?, ?> dec && Boolean.TRUE.equals(dec.get("accepted"));
                out.append("link ").append(l.get("kind")).append(' ').append(l.get("key")).append(" from step ")
                        .append(numberOf.get(String.valueOf(l.get("fromStep")))).append(accepted ? ": closes outside the code. " : ": rejected. ")
                        .append(((Map<?, ?>) l.get("decision")).get("reason")).append('\n');
            }
        }
        for (Map<String, Object> u : Flows.maps(doc.get("candidatesUndecided"))) {
            out.append("not followed ").append(u.get("kind")).append(' ').append(u.get("key")).append(" to ").append(u.get("to"))
                    .append(" (").append(u.get("status")).append(")\n");
        }
        for (Map<String, Object> a : Flows.maps(doc.get("acknowledgements"))) {
            Object durable = a.get("durableBefore");
            out.append("ack ").append(a.get("segment")).append(" at step ").append(numberOf.get(String.valueOf(a.get("stepId"))))
                    .append(", durable before: ").append(durable == null ? "unknown" : Boolean.TRUE.equals(durable) ? "yes" : "no")
                    .append(". ").append(a.get("description")).append('\n');
        }
        return out.toString();
    }

    private static String legacy(Map<String, Object> doc) {
        StringBuilder out = new StringBuilder(doc.get("id") + "  " + doc.getOrDefault("title", "") + "  (version 1: traced before diagrams; re-trace for one)\n");
        for (Map<String, Object> h : Flows.maps(doc.get("hops"))) {
            out.append(String.format("%3s %s", h.get("order"), h.get("symbol"))).append('\n');
        }
        for (Map<String, Object> b : Flows.maps(doc.get("boundaries"))) {
            out.append(String.format("%3s %-14s %s", b.get("order"), b.get("type"), b.get("symbol"))).append('\n');
        }
        return out.toString();
    }
}
