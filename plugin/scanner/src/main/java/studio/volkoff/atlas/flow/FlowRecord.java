package studio.volkoff.atlas.flow;

import studio.volkoff.atlas.audit.Slices;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.EvidenceVerifier;
import studio.volkoff.atlas.store.Git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code audit record-flow <id>}: checks a tracer's draft against {@code flow.schema.json} v2 and
 * the working tree, replaces its ids with stable ones, computes what the agent may not set (link
 * confidence, numbering, commit, fingerprint), and writes the flow, the index and the page. A
 * draft with any problem writes nothing and leaves the previous flow in place.
 */
public final class FlowRecord {

    /** {@code ok} false means nothing was written; the report names every problem. */
    public record Result(String report, boolean ok) {
    }

    private static final Set<String> PARTICIPANT_KINDS = Set.of("EXTERNAL", "ENTRY", "BEAN", "STORE", "BROKER", "EXECUTOR", "SCHEDULER");
    private static final Set<String> STEP_KINDS = Set.of("TRIGGER", "CALL", "RETURN", "RESPONSE", "TX_BEGIN", "TX_COMMIT", "TX_ROLLBACK",
            "DB_WRITE", "DB_READ", "MESSAGE_SEND", "MESSAGE_RECEIVE", "MESSAGE_ACK", "EVENT_PUBLISH", "EVENT_DELIVER", "ASYNC_FORK",
            "ASYNC_RUN", "EXTERNAL_CALL", "EXTERNAL_REPLY", "CACHE_WRITE", "FILE_WRITE", "LOCK", "IDEMPOTENCY_CHECK", "RETRY", "LOG_ONLY");
    private static final Set<String> SELF_KINDS = Set.of("TX_BEGIN", "TX_COMMIT", "TX_ROLLBACK");
    private static final Set<String> LINK_KINDS = Set.of("SEND_CONSUME", "EVENT_LISTEN", "OUTBOUND_WEBHOOK", "REDIRECT_CALLBACK", "WRITE_PICKUP", "MANUAL");
    private static final Set<String> BASES = Set.of("LITERAL", "CONSTANT", "CONFIG_KEY", "EVENT_TYPE", "PROVIDER_TOKEN", "CORRELATION_FIELD", "TABLE", "AGENT");
    /** Bases that name a shared key in code: as strong as the scanner's own match once phase-2 candidates confirm them. */
    private static final Set<String> KEYED = Set.of("LITERAL", "CONSTANT", "CONFIG_KEY", "EVENT_TYPE", "TABLE");

    private final Flows flows;
    private final List<String> problems = new ArrayList<>();
    private final EvidenceVerifier verifier;
    private int evidenceOk;

    private FlowRecord(Flows flows) {
        this.flows = flows;
        this.verifier = new EvidenceVerifier(flows.repo);
    }

    public static Result record(Flows flows, String idOrPath, String model) throws Exception {
        return new FlowRecord(flows).run(idOrPath, model);
    }

    private Result run(String idOrPath, String model) throws Exception {
        Path source = source(idOrPath);
        if (source == null) {
            return new Result("# no draft for " + idOrPath + ": write .reliability/flows/drafts/<FLOW-id>.json first\n", false);
        }
        Map<String, Object> draft;
        try {
            draft = Flows.read(source);
        } catch (IllegalArgumentException e) {
            return new Result("# " + rel(source) + " is not JSON: " + e.getMessage() + "\n", false);
        }
        Map<String, Object> doc = normalise(draft);
        String id = String.valueOf(draft.get("id"));
        if (!problems.isEmpty()) {
            StringBuilder out = new StringBuilder("# flow " + id + " rejected: " + problems.size() + " problems; nothing was written\n");
            problems.forEach(p -> out.append("PROBLEM\t").append(p).append('\n'));
            return new Result(out.toString(), false);
        }
        Slices slices = new Slices(flows.repo);
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("core", Flows.core(slices, doc));
        fingerprint.put("files", flows.fileHashes(doc));
        doc.put("commit", new Git(flows.repo).head());
        doc.put("tracedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        doc.put("model", model != null ? model : draft.get("model"));
        doc.put("fingerprint", fingerprint);
        Files.createDirectories(flows.dir);
        Files.writeString(flows.doc(id), Json.pretty(order(doc)), StandardCharsets.UTF_8);
        flows.writeIndex();
        FlowPage.write(flows, id);
        return new Result("# flow " + id + " recorded: " + count(doc, "steps", "step") + " in " + count(doc, "entries", "segment") + ", "
                + count(doc, "links", "link") + ", " + count(doc, "businessParts", "business part") + "; evidence " + evidenceOk + " ok\n"
                + "# file: " + rel(flows.doc(id)) + "\n"
                + "# page: " + flows.page(id).toUri() + "\n"
                + FlowAscii.render(doc), true);
    }

    private Path source(String idOrPath) {
        Path asPath = flows.repo.resolve(idOrPath).normalize();
        if (Files.isRegularFile(asPath)) {
            return asPath;
        }
        if (Flows.ID.matcher(idOrPath).matches()) {
            for (Path p : List.of(flows.draft(idOrPath), flows.doc(idOrPath))) {
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private Map<String, Object> normalise(Map<String, Object> d) throws Exception {
        Map<String, Object> doc = new LinkedHashMap<>(d);
        if (!Flows.v2(d)) {
            problem("schemaVersion", "must be 2; run doc flow.schema.json for the format");
            return doc;
        }
        String id = String.valueOf(d.get("id"));
        if (!Flows.ID.matcher(id).matches()) {
            problem("id", "\"" + id + "\" is not FLOW-<area>-<slug> in lower-case kebab");
        }
        limit("title", d.get("title"), 60, true);
        limit("summary", d.get("summary"), 160, true);

        Map<String, Map<String, Object>> eps = entrypoints();
        List<Map<String, Object>> entries = new ArrayList<>();
        Set<String> segments = new LinkedHashSet<>();
        for (Map<String, Object> e : Flows.maps(d.get("entries"))) {
            Map<String, Object> out = new LinkedHashMap<>(e);
            String seg = String.valueOf(e.get("segment"));
            if (!seg.matches("[A-Z]") || !segments.add(seg)) {
                problem("entries", "segment \"" + seg + "\" is not a new capital letter");
            }
            Map<String, Object> ep = eps.get(String.valueOf(e.get("entrypointId")));
            if (eps.isEmpty()) {
                // No atlas: the entry is kept as the tracer wrote it.
            } else if (ep == null) {
                problem("entries[" + seg + "]", e.get("entrypointId") + " is not in the atlas");
            } else {
                out.put("kind", ep.get("kind"));
                out.put("subkind", ep.get("subkind"));
                out.put("target", ep.get("target"));
                out.put("symbol", simple(String.valueOf(ep.get("cls"))) + "." + ep.get("method"));
            }
            limit("entries[" + seg + "].trigger", e.get("trigger"), 40, true);
            entries.add(out);
        }
        if (entries.isEmpty()) {
            problem("entries", "at least one entry point is needed");
        }
        doc.put("entries", entries);

        Map<String, String> pid = new HashMap<>();
        List<Map<String, Object>> participants = new ArrayList<>();
        for (Map<String, Object> p : Flows.maps(d.get("participants"))) {
            String kind = String.valueOf(p.get("kind"));
            String name = String.valueOf(p.get("name"));
            if (!PARTICIPANT_KINDS.contains(kind)) {
                problem("participants[" + name + "]", "kind " + kind + " is not one of " + PARTICIPANT_KINDS);
            }
            limit("participants[" + name + "].name", p.get("name"), 40, true);
            limit("participants[" + name + "].detail", p.get("detail"), 80, false);
            String stable = "P-" + Flows.sha1Short(kind + "|" + name);
            if (pid.containsValue(stable)) {
                problem("participants[" + name + "]", "named twice with kind " + kind);
            }
            pid.put(String.valueOf(p.get("id")), stable);
            Map<String, Object> out = new LinkedHashMap<>(p);
            out.put("id", stable);
            participants.add(out);
        }
        doc.put("participants", participants);

        Map<String, Object> txIds = new LinkedHashMap<>();
        for (Map<String, Object> t : Flows.maps(d.get("transactions"))) {
            txIds.put(String.valueOf(t.get("id")), t);
        }

        Slices slices = new Slices(flows.repo);
        Set<String> files = new LinkedHashSet<>();
        Flows.maps(d.get("steps")).forEach(s -> {
            if (s.get("evidence") instanceof Map<?, ?> e) {
                files.add(String.valueOf(e.get("file")));
            }
        });
        slices.load(files);
        Map<String, Map<String, Object>> effects = effects();
        Map<String, String> sid = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();
        int n = 0;
        for (Map<String, Object> s : Flows.maps(d.get("steps"))) {
            n++;
            String where = "steps[" + n + "] " + s.get("id");
            String kind = String.valueOf(s.get("kind"));
            String seg = String.valueOf(s.get("segment"));
            if (!STEP_KINDS.contains(kind)) {
                problem(where, "kind " + kind + " is not a step kind");
            }
            if (!segments.contains(seg)) {
                problem(where, "segment " + seg + " has no entry");
            }
            String from = pid.get(String.valueOf(s.get("from")));
            String to = pid.get(String.valueOf(s.get("to")));
            if (from == null || to == null) {
                problem(where, "from or to is not a participant id");
            }
            if (SELF_KINDS.contains(kind) && (from == null || !from.equals(to))) {
                problem(where, kind + " is drawn on one lifeline: from must equal to");
            }
            limit(where + ".label", s.get("label"), 48, true);
            limit(where + ".notes", s.get("notes"), 120, false);
            Object tx = s.get("tx");
            if (tx != null && !txIds.containsKey(String.valueOf(tx))) {
                problem(where, "tx " + tx + " is not in transactions");
            }
            String method = "?";
            if (!(s.get("evidence") instanceof Map<?, ?> ev)) {
                problem(where, "has no evidence object");
            } else {
                evidence(where, ev);
                Slices.Slice slice = ev.get("line") instanceof Number ln ? slices.enclosing(String.valueOf(ev.get("file")), ln.intValue()) : null;
                method = slice == null ? "?" : slice.method();
                effectRef(where, s.get("effectRef"), ev, effects);
            }
            String key = seg + "|" + kind + "|" + from + "|" + to + "|" + (s.get("evidence") instanceof Map<?, ?> ev2 ? ev2.get("file") : "") + "|" + method;
            int occurrence = seen.merge(key, 1, Integer::sum);
            String stable = "S-" + Flows.sha1Short(occurrence == 1 ? key : key + "|" + occurrence);
            if (sid.containsKey(String.valueOf(s.get("id")))) {
                problem(where, "the id is used twice");
            }
            sid.put(String.valueOf(s.get("id")), stable);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", stable);
            out.put("n", n);
            for (Map.Entry<String, Object> x : s.entrySet()) {
                if (!x.getKey().equals("id") && !x.getKey().equals("n")) {
                    out.put(x.getKey(), x.getValue());
                }
            }
            out.put("from", from);
            out.put("to", to);
            if (s.get("thread") != null && pid.containsKey(String.valueOf(s.get("thread")))) {
                out.put("thread", pid.get(String.valueOf(s.get("thread"))));
            }
            steps.add(out);
        }
        if (steps.isEmpty()) {
            problem("steps", "at least one step is needed");
        }
        doc.put("steps", steps);
        Map<String, Map<String, Object>> stepById = new LinkedHashMap<>();
        steps.forEach(s -> stepById.put(String.valueOf(s.get("id")), s));

        List<Map<String, Object>> transactions = new ArrayList<>();
        for (Map<String, Object> t : Flows.maps(d.get("transactions"))) {
            Map<String, Object> out = new LinkedHashMap<>(t);
            String where = "transactions[" + t.get("id") + "]";
            if (!String.valueOf(t.get("id")).matches("T[0-9]+")) {
                problem(where, "id is not T<number>");
            }
            out.put("begin", stepRef(where + ".begin", t.get("begin"), sid, stepById, "TX_BEGIN"));
            out.put("commit", stepRef(where + ".commit", t.get("commit"), sid, stepById, "TX_COMMIT"));
            transactions.add(out);
        }
        doc.put("transactions", transactions);

        Map<String, String> entryOf = new HashMap<>();
        entries.forEach(e -> entryOf.put(String.valueOf(e.get("segment")), String.valueOf(e.get("entrypointId"))));
        Map<String, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Map<String, Object> row : flows.links()) {
            candidates.put(String.valueOf(row.get("id")), row);
        }
        Set<String> decided = new LinkedHashSet<>();
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> l : Flows.maps(d.get("links"))) {
            String where = "links[" + l.get("id") + "]";
            String kind = String.valueOf(l.get("kind"));
            String basis = String.valueOf(l.get("basis"));
            if (!LINK_KINDS.contains(kind)) {
                problem(where, "kind " + kind + " is not a link kind");
            }
            if (!BASES.contains(basis)) {
                problem(where, "basis " + basis + " is not one of " + BASES);
            }
            boolean accepted = false;
            if (!(l.get("decision") instanceof Map<?, ?> dec) || !(dec.get("accepted") instanceof Boolean a)) {
                problem(where, "decision needs accepted (true or false) and a reason");
            } else {
                accepted = a;
                limit(where + ".decision.reason", dec.get("reason"), 160, true);
            }
            String fromStep = stepRef(where + ".fromStep", l.get("fromStep"), sid, stepById, null);
            // A rejected candidate, or a loop a person closes, has no step on the other side.
            String toStep = l.get("toStep") == null && (kind.equals("MANUAL") || !accepted) ? null
                    : stepRef(where + ".toStep", l.get("toStep"), sid, stepById, null);
            Map<String, Object> candidate = null;
            if (l.get("candidate") != null) {
                candidate = candidates.get(String.valueOf(l.get("candidate")));
                if (candidate == null) {
                    problem(where, "candidate " + l.get("candidate") + " is not in atlas/links.jsonl; take ids from flow candidates");
                } else {
                    decided.add(String.valueOf(candidate.get("id")));
                }
            } else if (!accepted) {
                problem(where, "a rejected link names the candidate it rejects");
            }
            String fromEntry = fromStep == null ? null : entryOf.get(String.valueOf(stepById.get(fromStep).get("segment")));
            String toEntry = toStep == null ? null : entryOf.get(String.valueOf(stepById.get(toStep).get("segment")));
            for (Map<String, Object> row : candidates.values()) {
                if (accepted && kind.equals(row.get("kind")) && fromEntry != null && fromEntry.equals(entry(row, "from")) && toEntry != null && toEntry.equals(entry(row, "to"))) {
                    decided.add(String.valueOf(row.get("id")));
                    if (candidate == null) {
                        candidate = row;
                    }
                }
            }
            List<Map<String, Object>> ev = Flows.maps(l.get("evidence"));
            if (ev.isEmpty()) {
                problem(where, "needs evidence on at least one side");
            }
            for (int i = 0; i < ev.size(); i++) {
                evidence(where + ".evidence[" + i + "]", ev.get(i));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", "LK-" + Flows.sha1Short(kind + "|" + fromStep + "|" + toStep + "|" + l.get("key")));
            for (Map.Entry<String, Object> x : l.entrySet()) {
                if (!x.getKey().equals("id") && !x.getKey().equals("status") && !x.getKey().equals("confidence")) {
                    out.put(x.getKey(), x.getValue());
                }
            }
            out.put("fromStep", fromStep);
            out.put("toStep", toStep);
            if (candidate != null) {
                out.put("candidate", candidate.get("id"));
            }
            String status = candidate == null ? "AGENT" : String.valueOf(candidate.get("status"));
            out.put("status", status);
            boolean sameKey = candidate != null && kind.equals(candidate.get("kind")) && String.valueOf(candidate.get("key")).equals(String.valueOf(l.get("key")));
            out.put("confidence", !accepted ? null
                    : status.equals("STATIC") && sameKey ? "HIGH"
                    : status.equals("INFERRED") && basis.equals("CORRELATION_FIELD") ? "HIGH"
                    : basis.equals("AGENT") ? "LOW"
                    : KEYED.contains(basis) && ev.size() >= 2 || basis.equals("CORRELATION_FIELD") || status.equals("INFERRED") && ev.size() >= 2 ? "MEDIUM" : "LOW");
            links.add(out);
        }
        doc.put("links", links);
        List<Map<String, Object>> undecided = new ArrayList<>();
        for (Map<String, Object> row : candidates.values()) {
            String from = entry(row, "from");
            if (!entryOf.containsValue(from) || decided.contains(String.valueOf(row.get("id")))) {
                continue;
            }
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("id", row.get("id"));
            u.put("kind", row.get("kind"));
            u.put("key", row.get("key"));
            u.put("status", row.get("status"));
            u.put("to", ((Map<?, ?>) row.get("to")).get("symbol"));
            undecided.add(u);
            if ("STATIC".equals(row.get("status")) && !entryOf.containsValue(entry(row, "to"))) {
                problem("links", "candidate " + row.get("id") + " (" + row.get("kind") + " " + row.get("key") + " to " + u.get("to")
                        + ") leaves the flow undecided: follow it as a segment, or add a link that rejects it with a reason");
            }
        }
        doc.put("candidatesUndecided", undecided);

        List<Map<String, Object>> parts = Flows.maps(d.get("businessParts"));
        if (parts.isEmpty() || parts.size() > 8) {
            problem("businessParts", "name 1 to 8 parts, not " + parts.size());
        }
        Map<String, Integer> owner = new HashMap<>();
        List<Map<String, Object>> bps = new ArrayList<>();
        for (Map<String, Object> bp : parts) {
            String where = "businessParts[" + bp.get("id") + "]";
            limit(where + ".name", bp.get("name"), 40, true);
            limit(where + ".outcome", bp.get("outcome"), 80, true);
            List<String> mine = new ArrayList<>();
            if (bp.get("steps") instanceof List<?> l) {
                for (Object x : l) {
                    String s = stepRef(where + ".steps", x, sid, stepById, null);
                    if (s != null) {
                        mine.add(s);
                        owner.merge(s, 1, Integer::sum);
                    }
                }
            }
            Map<String, Object> out = new LinkedHashMap<>(bp);
            out.put("steps", mine);
            bps.add(out);
        }
        for (Map<String, Object> s : steps) {
            int count = owner.getOrDefault(String.valueOf(s.get("id")), 0);
            if (Flows.EFFECTFUL.contains(String.valueOf(s.get("kind"))) && count != 1) {
                problem("steps[" + s.get("n") + "]", s.get("kind") + " belongs to " + count + " business parts; it must belong to exactly one");
            }
        }
        doc.put("businessParts", bps);

        List<Map<String, Object>> acks = new ArrayList<>();
        for (Map<String, Object> a : Flows.maps(d.get("acknowledgements"))) {
            String where = "acknowledgements[" + a.get("segment") + "]";
            Map<String, Object> out = new LinkedHashMap<>(a);
            out.put("stepId", stepRef(where + ".stepId", a.get("stepId"), sid, stepById, null));
            limit(where + ".description", a.get("description"), 120, true);
            acks.add(out);
        }
        doc.put("acknowledgements", acks);
        if (!(d.get("sourceBudget") instanceof Map<?, ?>)) {
            problem("sourceBudget", "needs filesOpened and limit");
        }
        if (!(d.get("openQuestions") instanceof List<?>)) {
            doc.put("openQuestions", List.of());
        }
        return doc;
    }

    private static String entry(Map<String, Object> link, String side) {
        return link.get(side) instanceof Map<?, ?> m ? String.valueOf(m.get("entrypointId")) : null;
    }

    private String stepRef(String where, Object draftId, Map<String, String> sid, Map<String, Map<String, Object>> stepById, String kind) {
        String id = sid.get(String.valueOf(draftId));
        if (id == null) {
            problem(where, draftId + " is not a step id");
            return null;
        }
        if (kind != null && !kind.equals(stepById.get(id).get("kind"))) {
            problem(where, draftId + " is a " + stepById.get(id).get("kind") + " step, not " + kind);
        }
        return id;
    }

    private void evidence(String where, Map<?, ?> ev) throws java.io.IOException {
        EvidenceVerifier.Check c = verifier.check(ev);
        if (c == EvidenceVerifier.Check.OK) {
            evidenceOk++;
        } else {
            problem(where, "evidence " + c + " at " + ev.get("file") + ":" + ev.get("line")
                    + (c == EvidenceVerifier.Check.TRIVIAL ? " (cite a line with a real identifier; for a commit, the method's last statement)" : ""));
        }
    }

    private void effectRef(String where, Object ref, Map<?, ?> ev, Map<String, Map<String, Object>> effects) {
        if (!(ref instanceof Map<?, ?> r) || effects.isEmpty()) {
            return;
        }
        Map<String, Object> row = effects.get(String.valueOf(r.get("entrypointId")));
        List<Map<String, Object>> list = row == null ? List.of() : Flows.maps(row.get("effects"));
        int i = r.get("index") instanceof Number x ? x.intValue() : -1;
        if (i < 0 || i >= list.size() || !String.valueOf(list.get(i).get("file")).equals(String.valueOf(ev.get("file")))) {
            problem(where, "effectRef " + r.get("entrypointId") + "#" + i + " is not an effect in " + ev.get("file"));
        }
    }

    private void limit(String where, Object value, int max, boolean required) {
        if (value == null || String.valueOf(value).isBlank()) {
            if (required) {
                problem(where, "is missing");
            }
            return;
        }
        int len = String.valueOf(value).length();
        if (len > max) {
            problem(where, "is " + len + " characters, the limit is " + max);
        }
    }

    private void problem(String where, String what) {
        problems.add(where + "\t" + what);
    }

    private Map<String, Map<String, Object>> entrypoints() throws java.io.IOException {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (Files.exists(flows.atlasDir.resolve("entrypoints.jsonl"))) {
            for (Map<String, Object> ep : AtlasStore.readJsonl(flows.atlasDir.resolve("entrypoints.jsonl"))) {
                out.put(String.valueOf(ep.get("id")), ep);
            }
        }
        return out;
    }

    private Map<String, Map<String, Object>> effects() throws java.io.IOException {
        Map<String, Map<String, Object>> out = new HashMap<>();
        if (Files.exists(flows.atlasDir.resolve("effects.jsonl"))) {
            for (Map<String, Object> row : AtlasStore.readJsonl(flows.atlasDir.resolve("effects.jsonl"))) {
                out.put(String.valueOf(row.get("id")), row);
            }
        }
        return out;
    }

    /** The schema's field order, whatever order the draft used. */
    private static Map<String, Object> order(Map<String, Object> doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("schemaVersion", "id", "title", "summary", "commit", "tracedAt", "model", "domain", "entries",
                "participants", "steps", "transactions", "links", "businessParts", "acknowledgements", "stoppedAt", "sourceBudget",
                "openQuestions", "candidatesUndecided", "fingerprint")) {
            if (doc.containsKey(k)) {
                out.put(k, doc.get(k));
            }
        }
        doc.forEach(out::putIfAbsent);
        return out;
    }

    private static String count(Map<String, Object> doc, String key, String noun) {
        int n = Flows.maps(doc.get(key)).size();
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private String rel(Path p) {
        return p.startsWith(flows.repo) ? flows.repo.relativize(p).toString().replace('\\', '/') : p.toString();
    }

    private static String simple(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1);
    }
}
