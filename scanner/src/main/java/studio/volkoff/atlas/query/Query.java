package studio.volkoff.atlas.query;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Atlas queries. Output contract: {@code #} comment lines first (staleness, match count, paging),
 * then TSV with a header row, JSON for single records, or a bare number with {@code --format count}.
 */
public final class Query {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;
    private static final List<String> EP_DEFAULT_FIELDS = List.of("id", "kind", "subkind", "target", "symbol", "loc", "status");
    private static final List<String> EP_ALL_FIELDS = List.of("id", "kind", "subkind", "target", "symbol", "loc", "status",
            "domain", "module", "cls", "method", "file", "line", "anno", "origin", "via", "placeholder", "attrs");
    private static final List<String> TYPE_DEFAULT_FIELDS = List.of("fqn", "kind", "stereotypes", "supers", "loc");

    private final AtlasStore atlas;
    private final Args args;
    private final StringBuilder out = new StringBuilder();

    public Query(AtlasStore atlas, Args args) {
        this.atlas = atlas;
        this.args = args;
    }

    public String run() throws IOException {
        switch (args.command) {
            case "entrypoints" -> entrypoints();
            case "types" -> types();
            case "record" -> record();
            case "stats" -> stats();
            case "packages" -> packages();
            case "domains" -> domains();
            default -> throw new IllegalArgumentException("unknown command '" + args.command + "'; run 'help'");
        }
        return out.toString();
    }

    private void entrypoints() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean hasDomains = !atlas.domains().isEmpty();
        for (Map<String, Object> r : atlas.entrypoints()) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("symbol", symbol(str(r, "cls"), str(r, "method")));
            row.put("loc", str(r, "file") + ":" + r.get("line"));
            if (hasDomains) {
                String d = atlas.domainOf(str(r, "cls"));
                row.put("domain", d == null ? "(unassigned)" : d);
            }
            rows.add(row);
        }
        Map<String, String> applied = new LinkedHashMap<>();
        Predicate<Map<String, Object>> filter = r -> true;
        filter = filter.and(exactAny("kind", "kind", applied));
        filter = filter.and(containsAny("subkind", "subkind", applied));
        filter = filter.and(prefix("module", "module", applied));
        filter = filter.and(prefix("package", "cls", applied));
        filter = filter.and(contains("class", "symbol", applied));
        filter = filter.and(contains("target", "target", applied));
        filter = filter.and(prefix("path-prefix", "target", applied));
        filter = filter.and(exactAny("status", "status", applied));
        filter = filter.and(exactAny("origin", "origin", applied));
        filter = filter.and(contains("via", "via", applied));
        filter = filter.and(contains("file", "file", applied));
        if (args.get("domain") != null && !hasDomains) {
            throw new IllegalArgumentException("--domain needs domains.jsonl; run the domain-cartographer skill first, or filter by --package");
        }
        filter = filter.and(exactAny("domain", "domain", applied));
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
        List<Map<String, Object>> matched = rows.stream().filter(filter).toList();

        String groupBy = args.get("group-by");
        if (groupBy != null) {
            grouped("entrypoints", matched, groupBy, applied);
            return;
        }
        List<String> defaults = new ArrayList<>(EP_DEFAULT_FIELDS);
        if (hasDomains) {
            defaults.add(1, "domain");
        }
        table("entrypoints", matched, fields(defaults, EP_ALL_FIELDS), applied, rows);
    }

    private void types() throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : atlas.types()) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("loc", str(r, "file") + ":" + r.get("line"));
            rows.add(row);
        }
        Map<String, String> applied = new LinkedHashMap<>();
        Predicate<Map<String, Object>> filter = r -> true;
        String impl = args.get("implements");
        if (impl != null) {
            applied.put("implements", impl);
            filter = filter.and(r -> listOf(r, "supers").stream().anyMatch(s -> s.equals(impl) || s.endsWith("." + impl)));
        }
        String annotated = args.get("annotated");
        if (annotated != null) {
            applied.put("annotated", annotated);
            filter = filter.and(r -> listOf(r, "annos").contains(annotated) || listOf(r, "stereotypes").contains(annotated));
        }
        filter = filter.and(exactAny("kind", "kind", applied));
        filter = filter.and(contains("name", "fqn", applied));
        filter = filter.and(prefix("package", "fqn", applied));
        filter = filter.and(prefix("module", "module", applied));
        String stereotype = args.get("stereotype");
        if (stereotype != null) {
            applied.put("stereotype", stereotype);
            filter = filter.and(r -> listOf(r, "stereotypes").contains(stereotype));
        }
        List<Map<String, Object>> matched = rows.stream().filter(filter).toList();
        if (args.get("group-by") != null) {
            grouped("types", matched, args.get("group-by"), applied);
            return;
        }
        table("types", matched, fields(TYPE_DEFAULT_FIELDS,
                List.of("fqn", "kind", "abstract", "stereotypes", "supers", "annos", "module", "file", "line", "loc")), applied, rows);
    }

    private void record() throws IOException {
        if (args.positional.isEmpty()) {
            throw new IllegalArgumentException("usage: record <EP-id | fully.qualified.Type>");
        }
        String key = args.positional.get(0);
        out.append(atlas.header()).append('\n');
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Map<String, Object> r : atlas.entrypoints()) {
            if (str(r, "id").equals(key)) {
                hits.add(r);
            }
        }
        if (hits.isEmpty() && key.length() >= "EP-".length() + 6) {
            for (Map<String, Object> r : atlas.entrypoints()) {
                if (str(r, "id").startsWith(key)) {
                    hits.add(r);
                }
            }
        }
        if (hits.isEmpty()) {
            for (Map<String, Object> t : atlas.types()) {
                if (str(t, "fqn").equals(key)) {
                    hits.add(t);
                }
            }
        }
        if (hits.isEmpty()) {
            out.append("# no record with id or type ").append(key).append('\n');
            return;
        }
        if (hits.size() > 1) {
            out.append("# ").append(hits.size()).append(" records match prefix ").append(key).append("; showing the first 5\n");
        }
        for (Map<String, Object> r : hits.subList(0, Math.min(5, hits.size()))) {
            Map<String, Object> row = new LinkedHashMap<>(r);
            if (r.containsKey("cls") && !atlas.domains().isEmpty()) {
                row.put("domain", atlas.domainOf(str(r, "cls")));
            }
            out.append(Json.write(row)).append('\n');
        }
    }

    private void stats() throws IOException {
        Map<String, Object> m = atlas.manifest;
        out.append(atlas.header()).append('\n');
        out.append("# scanned ").append(m.get("scannedAt")).append(" by ").append(m.get("scanner"))
                .append(" on Java ").append(m.get("javaRuntime")).append('\n');
        if (m.get("build") instanceof Map<?, ?> b) {
            out.append("# build: ").append(b.get("tool")).append(", ").append(b.get("modules")).append(" modules, Spring Boot ")
                    .append(b.get("springBootVersion")).append(", Java ").append(b.get("javaVersion"))
                    .append("; declares ").append(b.get("declares")).append('\n');
        }
        if (m.get("files") instanceof Map<?, ?> f) {
            out.append("# files: java=").append(f.get("java")).append(" kotlin=").append(f.get("kotlin"))
                    .append(" generated=").append(f.get("generated")).append(" parse-failures=").append(f.get("parseFailures")).append('\n');
        }
        Map<String, Map<String, Integer>> byKind = new TreeMap<>();
        Map<String, Map<String, Integer>> byModule = new TreeMap<>();
        for (Map<String, Object> r : atlas.entrypoints()) {
            byKind.computeIfAbsent(str(r, "kind"), k -> new TreeMap<>()).merge(str(r, "status"), 1, Integer::sum);
            byModule.computeIfAbsent(str(r, "module"), k -> new TreeMap<>()).merge(str(r, "kind"), 1, Integer::sum);
        }
        out.append("kind\ttotal\tSTATIC\tINFERRED\tUNKNOWN\n");
        byKind.forEach((kind, s) -> out.append(kind).append('\t').append(sum(s)).append('\t')
                .append(s.getOrDefault("STATIC", 0)).append('\t').append(s.getOrDefault("INFERRED", 0)).append('\t')
                .append(s.getOrDefault("UNKNOWN", 0)).append('\n'));
        List<Map.Entry<String, Map<String, Integer>>> modules = new ArrayList<>(byModule.entrySet());
        modules.sort(Comparator.comparing((Map.Entry<String, Map<String, Integer>> e) -> -sum(e.getValue())).thenComparing(Map.Entry::getKey));
        out.append("module\ttotal\tbreakdown\n");
        for (Map.Entry<String, Map<String, Integer>> e : modules.subList(0, Math.min(15, modules.size()))) {
            out.append(e.getKey()).append('\t').append(sum(e.getValue())).append('\t').append(breakdown(e.getValue())).append('\n');
        }
        if (modules.size() > 15) {
            out.append("# ").append(modules.size() - 15).append(" more modules: entrypoints --group-by module\n");
        }
        if (m.get("warnings") instanceof List<?> w) {
            for (Object x : w) {
                out.append("# warning: ").append(x).append('\n');
            }
        }
        if (!atlas.domains().isEmpty()) {
            out.append("# domains: ").append(atlas.domains().size()).append(" defined; see 'domains'\n");
        }
    }

    private void packages() throws IOException {
        int depth = args.getInt("depth", defaultPackageDepth(), 1, 20);
        Map<String, int[]> types = new TreeMap<>();
        for (Map<String, Object> t : atlas.types()) {
            types.computeIfAbsent(truncate(packageOf(str(t, "fqn")), depth), k -> new int[1])[0]++;
        }
        Map<String, Map<String, Integer>> eps = new TreeMap<>();
        for (Map<String, Object> r : atlas.entrypoints()) {
            eps.computeIfAbsent(truncate(packageOf(str(r, "cls")), depth), k -> new TreeMap<>()).merge(str(r, "kind"), 1, Integer::sum);
        }
        Set<String> all = new TreeSet<>(types.keySet());
        all.addAll(eps.keySet());
        out.append(atlas.header()).append('\n');
        out.append("# ").append(all.size()).append(" packages at depth ").append(depth).append(" (--depth to change)\n");
        out.append("package\ttypes\tentrypoints\tbreakdown\n");
        for (String p : all) {
            Map<String, Integer> b = eps.getOrDefault(p, Map.of());
            out.append(p.isEmpty() ? "(default)" : p).append('\t').append(types.getOrDefault(p, new int[1])[0]).append('\t')
                    .append(sum(b)).append('\t').append(breakdown(b)).append('\n');
        }
    }

    private void domains() throws IOException {
        out.append(atlas.header()).append('\n');
        if (atlas.domains().isEmpty()) {
            out.append("# no domains.jsonl yet; the domain-cartographer skill writes it\n");
            return;
        }
        Map<String, Integer> eps = new TreeMap<>();
        for (Map<String, Object> r : atlas.entrypoints()) {
            String d = atlas.domainOf(str(r, "cls"));
            eps.merge(d == null ? "(unassigned)" : d, 1, Integer::sum);
        }
        out.append("domain\tconfidence\tentrypoints\tpackages\n");
        for (Map<String, Object> d : atlas.domains()) {
            String name = str(d, "domain");
            out.append(name).append('\t').append(d.get("confidence")).append('\t').append(eps.getOrDefault(name, 0))
                    .append('\t').append(String.join(",", listOf(d, "packages"))).append('\n');
        }
        out.append("(unassigned)\t\t").append(eps.getOrDefault("(unassigned)", 0)).append("\t\n");
    }

    private void table(String noun, List<Map<String, Object>> matched, List<String> fields, Map<String, String> applied,
                       List<Map<String, Object>> universe) {
        out.append(atlas.header()).append('\n');
        String format = args.get("format", "tsv");
        if (format.equals("count")) {
            out.append(matched.size()).append('\n');
            return;
        }
        int limit = args.getInt("limit", DEFAULT_LIMIT, 0, MAX_LIMIT);
        int offset = args.getInt("offset", 0, 0, Integer.MAX_VALUE);
        int from = Math.min(offset, matched.size());
        int to = Math.min(matched.size(), from + limit);
        out.append("# ").append(matched.size()).append(' ').append(noun).append(" match").append(describe(applied));
        if (matched.isEmpty()) {
            out.append('\n');
            suggest(applied, universe);
            return;
        }
        out.append("; showing ").append(from + 1).append('-').append(to);
        if (to < matched.size()) {
            out.append("; next: --offset ").append(to);
        }
        out.append('\n');
        List<Map<String, Object>> page = matched.subList(from, to);
        if (format.equals("jsonl")) {
            for (Map<String, Object> r : page) {
                Map<String, Object> projected = new LinkedHashMap<>();
                for (String f : fields) {
                    projected.put(f, r.get(f));
                }
                out.append(Json.write(projected)).append('\n');
            }
            return;
        }
        out.append(String.join("\t", fields)).append('\n');
        for (Map<String, Object> r : page) {
            List<String> cells = new ArrayList<>();
            for (String f : fields) {
                cells.add(cell(r.get(f)));
            }
            out.append(String.join("\t", cells)).append('\n');
        }
    }

    private void grouped(String noun, List<Map<String, Object>> matched, String groupBy, Map<String, String> applied) {
        int depth = args.getInt("depth", 0, 0, 20);
        Function<Map<String, Object>, String> key = switch (groupBy) {
            case "package" -> r -> truncate(packageOf(str(r, r.containsKey("cls") ? "cls" : "fqn")), depth);
            case "class" -> r -> str(r, r.containsKey("cls") ? "cls" : "fqn");
            case "path" -> r -> pathPrefix(str(r, "target"), depth == 0 ? 2 : depth);
            default -> r -> {
                Object v = r.get(groupBy);
                if (v == null && !r.containsKey(groupBy)) {
                    throw new IllegalArgumentException("cannot group " + noun + " by '" + groupBy + "'");
                }
                return cell(v);
            };
        };
        Map<String, Map<String, Integer>> groups = new TreeMap<>();
        for (Map<String, Object> r : matched) {
            groups.computeIfAbsent(key.apply(r), k -> new TreeMap<>()).merge(str(r, "kind"), 1, Integer::sum);
        }
        List<Map.Entry<String, Map<String, Integer>>> rows = new ArrayList<>(groups.entrySet());
        rows.sort(Comparator.comparing((Map.Entry<String, Map<String, Integer>> e) -> -sum(e.getValue())).thenComparing(Map.Entry::getKey));
        int limit = args.getInt("limit", DEFAULT_LIMIT, 0, MAX_LIMIT);
        out.append(atlas.header()).append('\n');
        out.append("# ").append(matched.size()).append(' ').append(noun).append(" in ").append(groups.size())
                .append(" groups by ").append(groupBy).append(describe(applied));
        if (rows.size() > limit) {
            out.append("; showing the largest ").append(limit);
        }
        out.append('\n').append(groupBy).append("\ttotal\tbreakdown\n");
        for (Map.Entry<String, Map<String, Integer>> e : rows.subList(0, Math.min(limit, rows.size()))) {
            out.append(e.getKey()).append('\t').append(sum(e.getValue())).append('\t').append(breakdown(e.getValue())).append('\n');
        }
    }

    private void suggest(Map<String, String> applied, List<Map<String, Object>> universe) {
        for (Map.Entry<String, String> f : applied.entrySet()) {
            String field = switch (f.getKey()) {
                case "kind", "status", "origin", "module", "domain", "subkind" -> f.getKey();
                default -> null;
            };
            if (field == null) {
                continue;
            }
            Set<String> known = new TreeSet<>();
            for (Map<String, Object> r : universe) {
                Object v = r.get(field);
                if (v != null) {
                    known.add(v.toString());
                }
            }
            for (String value : f.getValue().split(",")) {
                String wanted = value.trim().toLowerCase(Locale.ROOT);
                if (known.stream().anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(wanted))) {
                    continue;
                }
                List<String> closest = known.stream()
                        .sorted(Comparator.comparingInt((String k) -> distance(k.toLowerCase(Locale.ROOT), wanted)).thenComparing(k -> k))
                        .limit(3).toList();
                out.append("# no ").append(field).append(" '").append(value.trim()).append("'; closest: ")
                        .append(String.join(", ", closest)).append('\n');
            }
        }
        out.append("# drop a filter, or use --group-by to see what exists\n");
    }

    private List<String> fields(List<String> defaults, List<String> all) {
        String f = args.get("fields");
        if (f == null) {
            return defaults;
        }
        if (f.equals("all")) {
            return all;
        }
        List<String> chosen = new ArrayList<>();
        for (String x : f.split(",")) {
            String name = x.trim();
            if (name.startsWith("+")) {
                if (chosen.isEmpty()) {
                    chosen.addAll(defaults);
                }
                name = name.substring(1);
            }
            if (!all.contains(name)) {
                throw new IllegalArgumentException("unknown field '" + name + "'; available: " + String.join(",", all));
            }
            if (!chosen.contains(name)) {
                chosen.add(name);
            }
        }
        return chosen;
    }

    private Predicate<Map<String, Object>> exactAny(String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        applied.put(flag, v);
        Set<String> wanted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        wanted.addAll(List.of(v.split(",")));
        return r -> wanted.contains(cell(r.get(field)));
    }

    private Predicate<Map<String, Object>> contains(String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        String needle = normalizeMsysPath(v);
        applied.put(flag, needle);
        String lower = needle.toLowerCase(Locale.ROOT);
        return r -> cell(r.get(field)).toLowerCase(Locale.ROOT).contains(lower);
    }

    private Predicate<Map<String, Object>> containsAny(String flag, String field, Map<String, String> applied) {
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
            String value = cell(r.get(field)).toLowerCase(Locale.ROOT);
            return needles.stream().anyMatch(value::contains);
        };
    }

    private Predicate<Map<String, Object>> prefix(String flag, String field, Map<String, String> applied) {
        String v = args.get(flag);
        if (v == null) {
            return r -> true;
        }
        String p = normalizeMsysPath(v);
        applied.put(flag, p);
        return r -> cell(r.get(field)).startsWith(p);
    }

    /**
     * Git Bash rewrites an argument like {@code /api/x} to {@code <Git install dir>/api/x}; undo
     * that. Git Bash exports its install directory as {@code EXEPATH}; the default install path
     * covers shells that do not.
     */
    static String normalizeMsysPath(String v) {
        if (v.length() < 3 || v.charAt(1) != ':') {
            return v;
        }
        String exe = System.getenv("EXEPATH");
        if (exe != null && !exe.isBlank()) {
            String root = exe.replace('\\', '/').replaceAll("/+$", "");
            if (v.regionMatches(true, 0, root, 0, root.length()) && v.length() > root.length() && v.charAt(root.length()) == '/') {
                return v.substring(root.length());
            }
        }
        String marker = "/Git/";
        int i = v.indexOf(marker);
        if (i > 0 && v.substring(0, i).toLowerCase(Locale.ROOT).contains("program files")) {
            return "/" + v.substring(i + marker.length());
        }
        return v;
    }

    private int defaultPackageDepth() throws IOException {
        List<String> packages = new ArrayList<>();
        for (Map<String, Object> t : atlas.types()) {
            packages.add(packageOf(str(t, "fqn")));
        }
        if (packages.isEmpty()) {
            return 3;
        }
        String[] common = packages.get(0).split("\\.");
        int n = common.length;
        for (String p : packages) {
            String[] segs = p.split("\\.");
            int i = 0;
            while (i < n && i < segs.length && segs[i].equals(common[i])) {
                i++;
            }
            n = i;
        }
        return n + 1;
    }

    private static String describe(Map<String, String> applied) {
        if (applied.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        applied.forEach((k, v) -> parts.add(k + "=" + v));
        return " (" + String.join(" ", parts) + ")";
    }

    public static String symbol(String cls, String method) {
        String[] segs = cls.split("\\.");
        int start = segs.length - 1;
        for (int i = 0; i < segs.length; i++) {
            if (!segs[i].isEmpty() && Character.isUpperCase(segs[i].charAt(0))) {
                start = i;
                break;
            }
        }
        return String.join(".", java.util.Arrays.copyOfRange(segs, start, segs.length)) + "." + method;
    }

    static String packageOf(String fqn) {
        String[] segs = fqn.split("\\.");
        List<String> pkg = new ArrayList<>();
        for (String s : segs) {
            if (!s.isEmpty() && Character.isUpperCase(s.charAt(0))) {
                break;
            }
            pkg.add(s);
        }
        if (pkg.size() == segs.length && !pkg.isEmpty()) {
            pkg.remove(pkg.size() - 1);
        }
        return String.join(".", pkg);
    }

    /** The first {@code depth} segments of an HTTP path: {@code /api/billing/invoices/{id}} → {@code /api/billing}. */
    private static String pathPrefix(String target, int depth) {
        if (!target.startsWith("/")) {
            return target;
        }
        String[] segs = target.substring(1).split("/");
        return "/" + String.join("/", java.util.Arrays.copyOfRange(segs, 0, Math.min(depth, segs.length)));
    }

    private static String truncate(String pkg, int depth) {
        if (depth <= 0 || pkg.isEmpty()) {
            return pkg;
        }
        String[] segs = pkg.split("\\.");
        return segs.length <= depth ? pkg : String.join(".", java.util.Arrays.copyOfRange(segs, 0, depth));
    }

    private static String cell(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof List<?> l) {
            List<String> parts = new ArrayList<>();
            for (Object o : l) {
                parts.add(String.valueOf(o));
            }
            return String.join(",", parts).replace('\t', ' ').replace('\n', ' ');
        }
        if (v instanceof Map<?, ?> m) {
            return m.isEmpty() ? "" : Json.write(m).replace('\t', ' ').replace('\n', ' ');
        }
        return v.toString().replace('\t', ' ').replace('\n', ' ');
    }

    private static String str(Map<String, Object> r, String key) {
        Object v = r.get(key);
        return v == null ? "" : v.toString();
    }

    private static List<String> listOf(Map<String, Object> r, String key) {
        List<String> out = new ArrayList<>();
        if (r.get(key) instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static int sum(Map<String, Integer> m) {
        int s = 0;
        for (int v : m.values()) {
            s += v;
        }
        return s;
    }

    private static String breakdown(Map<String, Integer> m) {
        List<String> parts = new ArrayList<>();
        m.forEach((k, v) -> parts.add(k + "=" + v));
        return String.join(" ", parts);
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
