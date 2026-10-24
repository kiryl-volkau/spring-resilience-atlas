package studio.volkoff.atlas.flow;

import studio.volkoff.atlas.audit.Slices;
import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.report.Page;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.scan.Scanner;
import studio.volkoff.atlas.store.Git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code .reliability/flows/<id>.html}: one page per recorded v2 flow, rendered only from its flow
 * document, the audit store and the index, so it can be rebuilt at any time and never holds
 * anything the data files do not.
 */
public final class FlowPage {

    /** Chip order: what still stands first, what was ruled out last. */
    private static final List<String> STANDING = List.of("VERIFIED", "CANDIDATE", "DISPROVED", "HARMLESS");
    private static final List<String> SEVERITY = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private FlowPage() {
    }

    /** Rewrites the page of one recorded flow; a version 1 flow has no page. */
    public static Path write(Flows flows, String id) throws Exception {
        Map<String, Object> doc = Flows.read(flows.doc(id));
        if (!Flows.v2(doc)) {
            return null;
        }
        Slices slices = new Slices(flows.repo);
        Map<String, Map<String, Object>> verdicts = new HashMap<>();
        Path placesFile = flows.auditDir.resolve("places.jsonl");
        if (Files.exists(placesFile)) {
            for (Map<String, Object> v : AtlasStore.readJsonl(placesFile)) {
                verdicts.put(String.valueOf(v.get("placeId")), v);
            }
        }
        List<Map<String, Object>> places = new ArrayList<>();
        for (Flows.Crossing c : flows.crossings(slices, doc, flows.places())) {
            Places.Place p = c.place();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("code", p.code());
            m.put("title", p.title());
            m.put("level", p.level().name());
            m.put("symbol", p.symbol());
            m.put("file", p.file());
            m.put("line", p.line());
            m.put("steps", c.steps());
            m.put("module", c.module());
            Map<String, Object> v = verdicts.get(p.id());
            if (v != null) {
                for (String k : List.of("verdict", "confidence", "status", "summary", "tier")) {
                    if (v.get(k) != null) {
                        m.put(k, v.get(k));
                    }
                }
            }
            places.add(m);
        }
        List<Map<String, Object>> index = new ArrayList<>();
        Path indexFile = flows.dir.resolve("index.json");
        if (Files.exists(indexFile)) {
            Object rows = Json.parse(Files.readString(indexFile, StandardCharsets.UTF_8));
            for (Map<String, Object> r : Flows.maps(rows)) {
                Map<String, Object> m = new LinkedHashMap<>();
                for (String k : List.of("id", "title", "status", "page")) {
                    m.put(k, r.get(k));
                }
                index.add(m);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", doc.get("title"));
        data.put("generatedAt", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        data.put("generator", "resilience-atlas " + Scanner.VERSION);
        data.put("head", new Git(flows.repo).head());
        data.put("stale", flows.stale(slices, doc));
        data.put("report", "../report.html");
        data.put("dataFile", id + ".json");
        data.put("flow", doc);
        data.put("places", places);
        data.put("flows", index);
        Object core = doc.get("fingerprint") instanceof Map<?, ?> fp ? fp.get("core") : null;
        List<Map<String, Object>> scenarios = flows.scenarios(id);
        scenarios.forEach(sc -> sc.put("stale", core == null || !core.equals(sc.get("flowFingerprint"))));
        scenarios.sort(Comparator.comparingInt((Map<String, Object> sc) -> STANDING.indexOf(String.valueOf(sc.get("status"))))
                .thenComparingInt(sc -> -SEVERITY.indexOf(String.valueOf(sc.get("severity")))));
        data.put("scenarios", scenarios);
        data.put("findings", flows.findings(id));
        data.put("concepts", flows.concepts(id));
        Path out = flows.page(id);
        Files.writeString(out, Page.fill(Page.template("flow.html"), String.valueOf(doc.get("title")), data), StandardCharsets.UTF_8);
        return out;
    }

    /** Rewrites the index and every v2 flow's page; returns how many pages were written. */
    public static int writeAll(Flows flows) throws Exception {
        if (!Files.isDirectory(flows.dir)) {
            return 0;
        }
        flows.writeIndex();
        int n = 0;
        for (String id : flows.all().keySet()) {
            if (write(flows, id) != null) {
                n++;
            }
        }
        return n;
    }
}
