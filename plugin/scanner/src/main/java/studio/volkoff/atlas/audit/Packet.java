package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.query.Query;
import studio.volkoff.atlas.report.Places;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The evidence packet a checker reads: per place the hypothesis, its named mitigations, the
 * operations, the effects the rule cited and the methods around them, cut from the working tree.
 * Deterministic for a given plan, batch and tree.
 */
final class Packet {

    /**
     * @param evidenceSlices the methods enclosing the rule's own evidence: what the fingerprint covers
     * @param entrySlices    entry methods of up to three operations, context only
     * @param otherFiles     path and hint of files on the operations' paths that are not printed
     * @param missing        evidence files that no longer exist
     */
    record PlaceView(Places.Place place, List<Slices.Slice> evidenceSlices, List<Slices.Slice> entrySlices,
                     List<String[]> otherFiles, List<String> missing) {

        /** Files the packet prints or lists: opening them is within the packet, not extra reading. */
        Set<String> files() {
            Set<String> out = new LinkedHashSet<>();
            evidenceSlices.forEach(s -> out.add(s.file()));
            entrySlices.forEach(s -> out.add(s.file()));
            otherFiles.forEach(f -> out.add(f[0]));
            return out;
        }
    }

    static final int MAX_ENTRY_SLICES = 3;
    static final int MAX_OTHER_FILES = 8;
    static final int MAX_OTHER_EFFECTS = 12;
    static final int EXTRA_FILES_PER_PLACE = 2;

    private Packet() {
    }

    static PlaceView view(AuditContext ctx, Places.Place p) throws Exception {
        List<String> opIds = new ArrayList<>(p.ops());
        opIds.sort(null);
        List<String> entryOps = opIds.subList(0, Math.min(MAX_ENTRY_SLICES, opIds.size()));
        Set<String> toLoad = new LinkedHashSet<>();
        for (Map<String, Object> e : p.reasonEffects()) {
            toLoad.add(String.valueOf(e.get("file")));
        }
        for (String op : entryOps) {
            toLoad.add(String.valueOf(ctx.inputById.get(op).ep().get("file")));
        }
        ctx.slices.load(toLoad);

        List<Slices.Slice> evidence = new ArrayList<>();
        Set<String> labels = new LinkedHashSet<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> e : p.reasonEffects()) {
            String file = String.valueOf(e.get("file"));
            Slices.Slice s = ctx.slices.enclosing(file, line(e));
            if (s == null) {
                if (!missing.contains(file)) {
                    missing.add(file);
                }
            } else if (labels.add(s.label())) {
                evidence.add(s);
            }
        }
        List<Slices.Slice> entries = new ArrayList<>();
        for (String op : entryOps) {
            Map<String, Object> ep = ctx.inputById.get(op).ep();
            Slices.Slice s = ctx.slices.enclosing(String.valueOf(ep.get("file")), ep.get("line") instanceof Number n ? n.intValue() : 0);
            if (s != null && labels.add(s.label())) {
                entries.add(s);
            }
        }
        Set<String> sliced = new LinkedHashSet<>();
        evidence.forEach(s -> sliced.add(s.file()));
        entries.forEach(s -> sliced.add(s.file()));
        Map<String, String> other = new LinkedHashMap<>();
        for (String op : p.ops()) {
            Map<String, Object> row = ctx.effectRowById.get(op);
            if (row == null || !(row.get("files") instanceof List<?> files)) {
                continue;
            }
            for (Object f : files) {
                String file = String.valueOf(f);
                if (!sliced.contains(file) && !other.containsKey(file) && other.size() < MAX_OTHER_FILES) {
                    other.put(file, hint(ctx, p, file));
                }
            }
        }
        List<String[]> otherFiles = new ArrayList<>();
        other.forEach((f, h) -> otherFiles.add(new String[] {f, h}));
        return new PlaceView(p, evidence, entries, otherFiles, missing);
    }

    /** What points at the file: the first effect of the place's operations located there. */
    private static String hint(AuditContext ctx, Places.Place p, String file) {
        for (String op : p.ops()) {
            for (Map<String, Object> e : ctx.inputById.get(op).effects()) {
                if (file.equals(e.get("file"))) {
                    return e.get("signal") + " " + e.get("detail");
                }
            }
        }
        return "on the call path";
    }

    static String render(AuditContext ctx, String batchId, String draftPath, List<PlaceView> views) throws Exception {
        StringBuilder body = new StringBuilder();
        Map<String, String> printed = new LinkedHashMap<>();
        Set<String> files = new TreeSet<>();
        Set<String> declares = ctx.declares();
        for (PlaceView v : views) {
            files.addAll(v.files());
            body.append(section(ctx, v, printed, declares)).append('\n');
        }
        body.append(DRAFT_FORMAT);
        int tokens = bodyBytes(body.toString()) / 4;
        return "# packet " + batchId + " · " + views.size() + " places · " + files.size() + " files · ~" + Math.round(tokens / 100.0) / 10.0 + "k tokens\n"
                + "# rules: read at most " + EXTRA_FILES_PER_PLACE + " files per place beyond those printed or listed below; never search the tree; never modify any file except your draft\n"
                + "# write your draft to " + draftPath + " (one JSON object per line; format at the end)\n\n"
                + body;
    }

    private static String section(AuditContext ctx, PlaceView v, Map<String, String> printed, Set<String> declares) {
        Places.Place p = v.place();
        Hypotheses.Hypothesis h = ctx.hypotheses.get(p.code());
        StringBuilder out = new StringBuilder();
        out.append("## ").append(p.id()).append(" · ").append(p.code()).append(" · ").append(p.level()).append(" · ").append(p.symbol()).append('\n');
        out.append("hypothesis: ").append(h.hypothesis()).append('\n');
        out.append("check: ").append(h.check()).append('\n');
        List<String> refutes = new ArrayList<>();
        List<String> mitigates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Hypotheses.Mitigation m : h.mitigations()) {
            (m.kind().equals("refutes") ? refutes : mitigates).add("[" + m.name() + "] " + m.text());
            names.add(m.name());
        }
        if (!refutes.isEmpty()) {
            out.append("refuted when: ").append(String.join("; ", refutes)).append('\n');
        }
        if (!mitigates.isEmpty()) {
            out.append("mitigated when: ").append(String.join("; ", mitigates)).append('\n');
        }
        out.append("cite: ").append(h.cite()).append('\n');
        out.append("mitigation names to use in mitigationsChecked, exactly: ").append(String.join(" · ", names)).append("\n\n");

        out.append("operations (").append(p.ops().size()).append("):");
        for (String op : p.ops()) {
            Map<String, Object> ep = ctx.inputById.get(op).ep();
            out.append("\n  ").append(op).append(' ').append(ep.get("kind")).append(' ').append(ep.get("subkind")).append(' ').append(ep.get("target"))
                    .append(' ').append(Query.symbol(String.valueOf(ep.get("cls")), String.valueOf(ep.get("method"))));
            if (ep.get("attrs") instanceof Map<?, ?> a && a.get("webhook") instanceof Map<?, ?> w && "WEBHOOK".equals(w.get("verdict"))) {
                out.append(" [webhook]");
            }
        }
        out.append('\n');
        Set<String> seen = new TreeSet<>();
        for (String op : p.ops()) {
            seen.addAll(ctx.inputById.get(op).assessment().mitigations());
        }
        out.append("mitigations seen on these paths by the scanner: ").append(seen.isEmpty() ? "none" : String.join(", ", seen)).append('\n');
        if (!h.declares().isEmpty()) {
            List<String> sig = new ArrayList<>();
            for (String d : h.declares()) {
                sig.add(d + "=" + (declares.contains(d) ? "yes" : "no"));
            }
            out.append("project build declares: ").append(String.join(", ", sig)).append('\n');
        }

        out.append("\nthe rule's own evidence:\n");
        int n = 1;
        Set<String> cited = new LinkedHashSet<>();
        for (Map<String, Object> e : p.reasonEffects()) {
            out.append("  E").append(n++).append(' ').append(effectLine(e)).append('\n');
            cited.add(e.get("file") + ":" + e.get("line") + ":" + e.get("detail"));
        }
        List<String> others = new ArrayList<>();
        for (String op : p.ops()) {
            for (Map<String, Object> e : ctx.inputById.get(op).effects()) {
                if (cited.add(e.get("file") + ":" + e.get("line") + ":" + e.get("detail"))) {
                    others.add(effectLine(e));
                }
            }
        }
        if (!others.isEmpty()) {
            out.append("other effects on the path (context only):\n");
            for (int i = 0; i < Math.min(MAX_OTHER_EFFECTS, others.size()); i++) {
                out.append("  E").append(n++).append(' ').append(others.get(i)).append('\n');
            }
            if (others.size() > MAX_OTHER_EFFECTS) {
                out.append("  … ").append(others.size() - MAX_OTHER_EFFECTS).append(" more\n");
            }
        }
        for (String f : v.missing()) {
            out.append("missing file: ").append(f).append(" (the atlas cites it, the tree no longer has it)\n");
        }

        out.append("\nslices (line numbers are the file's; copy snippets verbatim from here):\n");
        List<Slices.Slice> all = new ArrayList<>(v.evidenceSlices());
        all.addAll(v.entrySlices());
        for (Slices.Slice s : all) {
            String key = s.label();
            if (printed.containsKey(key)) {
                out.append("--- ").append(printed.get(key)).append(' ').append(s.file()).append("  ").append(s.method())
                        .append("  lines ").append(s.from()).append('-').append(s.to()).append("  (printed above)\n");
                continue;
            }
            String label = "S" + (printed.size() + 1);
            printed.put(key, label);
            Set<Integer> focus = new TreeSet<>();
            for (Map<String, Object> e : p.reasonEffects()) {
                if (s.file().equals(e.get("file"))) {
                    focus.add(line(e));
                }
            }
            out.append("--- ").append(label).append(' ').append(s.file()).append("  ").append(s.method())
                    .append("  lines ").append(s.from()).append('-').append(s.to())
                    .append(v.entrySlices().contains(s) ? "  (entry method)" : "").append('\n');
            out.append(Slices.render(s, focus));
        }
        if (!v.otherFiles().isEmpty()) {
            out.append("other files on this path (not printed; open at most ").append(EXTRA_FILES_PER_PLACE)
                    .append(" files per place in total, only if the check needs them):\n");
            for (String[] f : v.otherFiles()) {
                out.append("  ").append(f[0]).append("  (").append(f[1]).append(")\n");
            }
        }
        return out.toString();
    }

    private static String effectLine(Map<String, Object> e) {
        StringBuilder s = new StringBuilder();
        s.append(e.get("signal")).append("  ").append(e.get("file")).append(':').append(e.get("line")).append("  ").append(e.get("symbol"));
        if (e.get("hop") instanceof Number h && h.intValue() > 0) {
            s.append("  hop ").append(h.intValue());
        }
        if (Boolean.TRUE.equals(e.get("inTx"))) {
            s.append("  in tx");
        }
        return s.append("  ").append(e.get("detail")).toString();
    }

    /** Bytes of the lines that are not {@code #} header lines: what the token estimate counts. */
    static int bodyBytes(String text) {
        int bytes = 0;
        for (String line : text.split("\n", -1)) {
            if (!line.startsWith("# ")) {
                bytes += line.getBytes(StandardCharsets.UTF_8).length + 1;
            }
        }
        return bytes;
    }

    static int line(Map<String, Object> e) {
        return e.get("line") instanceof Number n ? n.intValue() : 0;
    }

    private static final String DRAFT_FORMAT = """
            ## draft format
            Write the draft file named at the top of this packet: one JSON object per line, one line per place above, nothing else.
            {"placeId":"PL-…","verdict":"CONFIRMED|REFUTED|MITIGATED|UNCERTAIN","confidence":"HIGH|MEDIUM|LOW","summary":"one or two sentences on what happens and why","evidence":[{"file":"path as printed","line":123,"snippet":"code copied verbatim from that line","claim":"what this line shows"}],"mitigationsChecked":[{"name":"<exact name from the place's list>","result":"FOUND|NOT_FOUND|NOT_CHECKED","where":"Class.method or file:line"}],"filesOpened":["every file you read besides this packet"],"needs":{"kind":"file|symbol|config|runtime","what":"what would decide it","wouldOpen":"path"}}
            - CONFIRMED: the hypothesis holds and no listed mitigation applies. REFUTED: the hypothesis does not hold. MITIGATED: it holds but a listed mitigation limits it. UNCERTAIN: the packet and two extra files per place do not decide it; then "needs" is required.
            - CONFIRMED, REFUTED and MITIGATED need at least one evidence object; MITIGATED cites the mitigation. Every snippet must appear within two lines of its line number; copy it from the slices or the file you opened.
            - Report every mitigation name of the place in mitigationsChecked; NOT_CHECKED lowers the confidence to MEDIUM.
            - Leave out tier, model, batch, commit, status and every other field not shown here.
            """;
}
