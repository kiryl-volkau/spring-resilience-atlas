package studio.volkoff.atlas.flow;

import studio.volkoff.atlas.query.Args;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** {@code flow list|show|page|candidates|snapshot|diff}: the recorded flows, one as text, its page, and their history. */
public final class FlowCommand {

    private FlowCommand() {
    }

    public static String run(Flows flows, Args args) throws Exception {
        String sub = args.positional.isEmpty() ? "list" : args.positional.get(0);
        return switch (sub) {
            case "list" -> list(flows);
            case "show" -> FlowAscii.render(Flows.read(existing(flows, id(args, "show"))));
            case "candidates" -> {
                if (args.positional.size() < 2 || !args.positional.get(1).matches("EP-[0-9a-f]+")) {
                    throw new IllegalArgumentException("usage: flow candidates <EP-id>");
                }
                yield candidates(flows, args.positional.get(1));
            }
            case "page" -> {
                String id = id(args, "page");
                existing(flows, id);
                flows.writeIndex();
                Path page = FlowPage.write(flows, id);
                yield page == null ? "# " + id + " is a version 1 flow: traced before diagrams, re-trace it for a page\n"
                        : "# page: " + page.toUri() + "\n";
            }
            case "snapshot" -> {
                Path p = History.snapshot(flows, "manual", args.getInt("keep", History.DEFAULT_KEEP, 1, 1000));
                Map<String, Object> s = studio.volkoff.atlas.json.Json.parseObject(Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
                yield "# snapshot: " + p + "\n# " + Flows.maps(s.get("flows")).size() + " flows, " + Flows.maps(s.get("scenarios")).size()
                        + " scenarios, " + Flows.maps(s.get("findings")).size() + " deep findings\n";
            }
            case "diff" -> {
                List<Path> all = History.all(flows);
                if (args.positional.size() < 2 && all.isEmpty()) {
                    yield "# no snapshot yet: flow snapshot takes one, and a deep audit takes one when it finishes\n";
                }
                String from = args.positional.size() > 1 ? args.positional.get(1) : all.get(all.size() - 1).getFileName().toString();
                String to = args.positional.size() > 2 ? args.positional.get(2) : "live";
                Map<String, Object> d = History.diff(History.load(flows, from), History.load(flows, to));
                yield "json".equals(args.get("format")) ? studio.volkoff.atlas.json.Json.pretty(d) + "\n" : History.render(d);
            }
            default -> throw new IllegalArgumentException("usage: flow list | flow show <FLOW-id> | flow page <FLOW-id> [--open] | flow candidates <EP-id>"
                    + " | flow snapshot [--keep N] | flow diff [<snapshot> [<snapshot>|live]] [--format json]");
        };
    }

    /**
     * The entry points a flow starting at {@code start} plausibly reaches, from {@code links.jsonl}:
     * forward over every link out of the reached entry points, and backward into a webhook start
     * from the outbound calls it may answer; at most 4 segments and 12 links.
     */
    private static String candidates(Flows flows, String start) throws Exception {
        List<Map<String, Object>> links = flows.links();
        Map<String, Map<String, Object>> eps = new java.util.HashMap<>();
        for (Map<String, Object> ep : studio.volkoff.atlas.query.AtlasStore.readJsonl(flows.atlasDir.resolve("entrypoints.jsonl"))) {
            eps.put(String.valueOf(ep.get("id")), ep);
        }
        if (!eps.containsKey(start)) {
            throw new IllegalArgumentException(start + " is not in the atlas");
        }
        java.util.Set<String> reached = new java.util.LinkedHashSet<>(List.of(start));
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && rows.size() < 12) {
            String at = queue.poll();
            for (Map<String, Object> l : links) {
                String from = String.valueOf(((Map<?, ?>) l.get("from")).get("entrypointId"));
                String to = String.valueOf(((Map<?, ?>) l.get("to")).get("entrypointId"));
                boolean forward = from.equals(at);
                boolean backward = at.equals(start) && to.equals(start) && "OUTBOUND_WEBHOOK".equals(l.get("kind"));
                if ((forward || backward) && rows.size() < 12 && !rows.contains(l)) {
                    rows.add(l);
                    String next = forward ? to : from;
                    if (reached.size() < 4 && reached.add(next)) {
                        queue.add(next);
                    }
                }
            }
        }
        StringBuilder out = new StringBuilder("# " + rows.size() + " candidate links from " + start + "; decide each in the flow's links: accept with evidence on both sides, or reject with a reason\n");
        if (rows.isEmpty()) {
            return out.append("# no link in the atlas leaves this entry point: its sends, events and calls reach no other entry point the scan resolved\n").toString();
        }
        out.append("id\tkind\tfrom\tto\tkey\tbasis\tstatus\n");
        for (Map<String, Object> l : rows) {
            Map<?, ?> f = (Map<?, ?>) l.get("from");
            Map<?, ?> t = (Map<?, ?>) l.get("to");
            out.append(l.get("id")).append('\t').append(l.get("kind")).append('\t').append(f.get("entrypointId")).append(' ').append(f.get("symbol"))
                    .append('\t').append(t.get("entrypointId")).append(' ').append(t.get("symbol")).append('\t').append(l.get("key")).append('\t')
                    .append(l.get("basis")).append('\t').append(l.get("status")).append('\n');
        }
        out.append("# evidence\n");
        for (Map<String, Object> l : rows) {
            Map<?, ?> f = (Map<?, ?>) l.get("from");
            Map<?, ?> t = (Map<?, ?>) l.get("to");
            out.append(l.get("id")).append("\tfrom\t").append(f.get("file")).append(':').append(f.get("line")).append('\t')
                    .append(line(flows, String.valueOf(f.get("file")), ((Number) f.get("line")).intValue())).append('\n');
            out.append(l.get("id")).append("\tto\t").append(t.get("file")).append(':').append(t.get("line")).append('\t')
                    .append(line(flows, String.valueOf(t.get("file")), ((Number) t.get("line")).intValue())).append('\n');
            out.append(l.get("id")).append("\tvia\t").append(String.join("; ", (List<String>) l.get("via"))).append('\n');
        }
        java.util.Set<String> files = new java.util.TreeSet<>();
        for (Map<String, Object> row : studio.volkoff.atlas.query.AtlasStore.readJsonl(flows.atlasDir.resolve("effects.jsonl"))) {
            if (reached.contains(String.valueOf(row.get("id"))) && row.get("files") instanceof List<?> l) {
                l.forEach(x -> files.add(String.valueOf(x)));
            }
        }
        out.append("# ").append(files.size()).append(" files on the paths of ").append(String.join(", ", reached)).append('\n');
        files.forEach(f -> out.append(f).append('\n'));
        return out.toString();
    }

    private static String line(Flows flows, String file, int n) {
        try {
            List<String> lines = java.nio.file.Files.readAllLines(flows.repo.resolve(file), java.nio.charset.StandardCharsets.UTF_8);
            return n >= 1 && n <= lines.size() ? lines.get(n - 1).strip() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static String list(Flows flows) throws Exception {
        List<Map<String, Object>> rows = flows.writeIndex();
        if (rows.isEmpty()) {
            return "# no flows recorded yet; trace one with the critical-flow-tracer skill\n";
        }
        StringBuilder out = new StringBuilder("# " + rows.size() + " flows; pages under " + rel(flows, flows.dir) + "\n");
        out.append("id\tstatus\tsteps\tsegments\tplaces\ttitle\n");
        for (Map<String, Object> r : rows) {
            out.append(r.get("id")).append('\t').append(r.get("status")).append('\t').append(r.getOrDefault("steps", "-")).append('\t')
                    .append(r.get("entries") instanceof List<?> l ? l.size() : 0).append('\t')
                    .append(r.get("places") instanceof List<?> p ? p.size() : 0).append('\t').append(r.get("title")).append('\n');
        }
        return out.toString();
    }

    private static Path existing(Flows flows, String id) {
        Path doc = flows.doc(id);
        if (!Files.isRegularFile(doc)) {
            throw new IllegalArgumentException("no recorded flow " + id + "; flow list shows the recorded ones");
        }
        return doc;
    }

    private static String id(Args args, String usage) {
        if (args.positional.size() < 2 || !Flows.ID.matcher(args.positional.get(1)).matches()) {
            throw new IllegalArgumentException("usage: flow " + usage + " <FLOW-id>");
        }
        return args.positional.get(1);
    }

    private static String rel(Flows flows, Path p) {
        return p.startsWith(flows.repo) ? flows.repo.relativize(p).toString().replace('\\', '/') : p.toString();
    }
}
