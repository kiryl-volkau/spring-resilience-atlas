package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.query.Args;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.Query;
import studio.volkoff.atlas.report.Places;
import studio.volkoff.atlas.report.Risk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code places}: the report's places in its "fix here first" order, with the quick check's
 * verdict where one is recorded. Reads only; a skill uses it to pick where to start.
 */
public final class PlaceList {

    private PlaceList() {
    }

    public static String run(Path repo, Path atlasDir, Path auditDir, Args args) throws Exception {
        AtlasStore atlas = AtlasStore.open(repo, atlasDir);
        List<Places.Input> inputs = Places.inputs(atlas);
        Map<String, Places.Input> byId = new HashMap<>();
        inputs.forEach(in -> byId.put(in.id(), in));
        Set<String> levels = new LinkedHashSet<>();
        for (String l : args.get("level", "HIGH,MEDIUM").toUpperCase(Locale.ROOT).split(",")) {
            if (!l.isBlank()) {
                levels.add(l.strip());
            }
        }
        String rule = args.get("rule");
        Map<String, Map<String, Object>> verdicts = new HashMap<>();
        Path recorded = auditDir.resolve("places.jsonl");
        if (Files.exists(recorded)) {
            for (String line : AuditStore.read(recorded).split("\\R")) {
                if (!line.isBlank()) {
                    Map<String, Object> v = Json.parseObject(line);
                    verdicts.put(String.valueOf(v.get("placeId")), v);
                }
            }
        }
        List<Places.Place> shown = new ArrayList<>();
        for (Places.Place p : Places.derive(inputs).places()) {
            if (levels.contains(p.level().name()) && (rule == null || rule.equals(p.code()))) {
                shown.add(p);
            }
        }
        int limit = args.getInt("limit", 20, 1, 500);
        StringBuilder out = new StringBuilder(atlas.header()).append('\n');
        out.append("# ").append(shown.size()).append(" places at ").append(String.join(",", levels))
                .append(verdicts.isEmpty() ? "; no quick check recorded" : "; verdicts from the quick check, a small model's triage")
                .append("; showing ").append(Math.min(limit, shown.size())).append('\n');
        out.append("rank\tid\tlevel\trule\tplace\tloc\toperations\tfirst\ttarget\tverdict\n");
        for (int i = 0; i < Math.min(limit, shown.size()); i++) {
            Places.Place p = shown.get(i);
            Map<String, Object> ep = byId.get(p.sampleOp()).ep();
            Map<String, Object> v = verdicts.get(p.id());
            String verdict = v == null ? "-" : "CURRENT".equals(v.get("status")) ? String.valueOf(v.get("verdict")) : String.valueOf(v.get("status"));
            out.append(i + 1).append('\t').append(p.id()).append('\t').append(p.level() == Risk.Level.NONE ? "NONE" : p.level().name())
                    .append('\t').append(p.code()).append('\t').append(p.symbol()).append('\t').append(p.file()).append(':').append(p.line())
                    .append('\t').append(p.ops().size()).append('\t').append(p.sampleOp()).append('\t')
                    .append(ep.get("subkind")).append(' ').append(Query.target(ep)).append('\t').append(verdict).append('\n');
        }
        return out.toString();
    }
}
