package studio.volkoff.atlas.query;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * The entry-point filters of the {@code entrypoints} command, shared with commands that select
 * operations the same way.
 *
 * @param applied the flags that took part, with their values as matched, for the output header
 */
public record EntryPointFilter(Predicate<Map<String, Object>> predicate, Map<String, String> applied) {

    /** Rows of {@code entrypoints.jsonl} with the fields filters and tables use: {@code symbol}, {@code loc} and, with domains, {@code domain}. */
    public static List<Map<String, Object>> rows(AtlasStore atlas) throws IOException {
        boolean hasDomains = !atlas.domains().isEmpty();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : atlas.entrypoints()) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("symbol", Query.symbol(str(r, "cls"), str(r, "method")));
            row.put("params", Query.conditions(r));
            row.put("loc", str(r, "file") + ":" + r.get("line"));
            if (hasDomains) {
                String d = atlas.domainOf(str(r, "cls"));
                row.put("domain", d == null ? "(unassigned)" : d);
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * The conjunction of every filter flag present in {@code args}.
     *
     * @throws IllegalArgumentException for {@code --domain} when the atlas has no domains
     */
    public static EntryPointFilter of(Args args, boolean hasDomains) {
        Map<String, String> applied = new LinkedHashMap<>();
        Predicate<Map<String, Object>> filter = r -> true;
        filter = filter.and(exactAny(args, "kind", "kind", applied));
        filter = filter.and(containsAny(args, "subkind", "subkind", applied));
        filter = filter.and(prefix(args, "module", "module", applied));
        filter = filter.and(prefix(args, "package", "cls", applied));
        filter = filter.and(contains(args, "class", "symbol", applied));
        filter = filter.and(contains(args, "target", "target", applied));
        filter = filter.and(prefix(args, "path-prefix", "target", applied));
        filter = filter.and(exactAny(args, "status", "status", applied));
        filter = filter.and(exactAny(args, "origin", "origin", applied));
        filter = filter.and(contains(args, "via", "via", applied));
        filter = filter.and(contains(args, "file", "file", applied));
        if (args.get("domain") != null && !hasDomains) {
            throw new IllegalArgumentException("--domain needs domains.jsonl; run the domain-cartographer skill first, or filter by --package");
        }
        filter = filter.and(exactAny(args, "domain", "domain", applied));
        String tag = args.get("tag");
        if (tag != null) {
            applied.put("tag", tag);
            Set<String> verdicts = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String t : tag.split(",")) {
                verdicts.add(t.trim().replace('-', '_'));
            }
            filter = filter.and(r -> r.get("attrs") instanceof Map<?, ?> attrs && attrs.get("webhook") instanceof Map<?, ?> w
                    && verdicts.contains(String.valueOf(w.get("verdict"))));
        }
        return new EntryPointFilter(filter, applied);
    }

    static Predicate<Map<String, Object>> exactAny(Args args, String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        applied.put(flag, v);
        Set<String> wanted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        wanted.addAll(List.of(v.split(",")));
        return r -> wanted.contains(Query.cell(r.get(field)));
    }

    static Predicate<Map<String, Object>> contains(Args args, String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        String needle = Query.normalizeMsysPath(v);
        applied.put(flag, needle);
        String lower = needle.toLowerCase(Locale.ROOT);
        return r -> Query.cell(r.get(field)).toLowerCase(Locale.ROOT).contains(lower);
    }

    static Predicate<Map<String, Object>> containsAny(Args args, String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        applied.put(flag, v);
        List<String> needles = new ArrayList<>();
        for (String n : v.split(",")) {
            needles.add(n.trim().toLowerCase(Locale.ROOT));
        }
        return r -> {
            String value = Query.cell(r.get(field)).toLowerCase(Locale.ROOT);
            return needles.stream().anyMatch(value::contains);
        };
    }

    static Predicate<Map<String, Object>> prefix(Args args, String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        String p = Query.normalizeMsysPath(v);
        applied.put(flag, p);
        return r -> Query.cell(r.get(field)).startsWith(p);
    }

    private static String str(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? "" : v.toString();
    }
}
