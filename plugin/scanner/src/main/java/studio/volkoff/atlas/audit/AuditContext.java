package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.report.Places;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One atlas read once: its operations, places, hypotheses and the slicer over the working tree. */
final class AuditContext {

    final Path repo;
    final Path atlasDir;
    final AtlasStore atlas;
    final List<Places.Input> inputs;
    final Map<String, Places.Input> inputById = new LinkedHashMap<>();
    final Places.Result places;
    final Map<String, Places.Place> placeById = new LinkedHashMap<>();
    final Map<String, Hypotheses.Hypothesis> hypotheses;
    final Map<String, Map<String, Object>> effectRowById = new HashMap<>();
    final Slices slices;

    private AuditContext(Path repo, Path atlasDir) throws Exception {
        this.repo = repo;
        this.atlasDir = atlasDir;
        this.atlas = AtlasStore.open(repo, atlasDir);
        this.inputs = Places.inputs(atlas);
        for (Places.Input in : inputs) {
            inputById.put(in.id(), in);
        }
        this.hypotheses = Hypotheses.load();
        Places.Result all = Places.derive(inputs);
        List<Places.Place> inScope = new ArrayList<>();
        for (Places.Place p : all.places()) {
            Hypotheses.Hypothesis h = hypotheses.get(p.code());
            if (h != null && h.inScope()) {
                inScope.add(p);
                placeById.put(p.id(), p);
            }
        }
        this.places = new Places.Result(inScope, all.placeIdsByOp());
        for (Map<String, Object> row : atlas.effects()) {
            effectRowById.put(String.valueOf(row.get("id")), row);
        }
        this.slices = new Slices(repo);
    }

    static AuditContext open(Path repo, Path atlasDir) throws Exception {
        return new AuditContext(repo, atlasDir);
    }

    /** The in-scope place ids an operation reaches, in reason order. */
    List<String> placeIdsOf(String opId) {
        List<String> out = new ArrayList<>();
        for (String id : places.placeIdsByOp().getOrDefault(opId, List.of())) {
            if (placeById.containsKey(id)) {
                out.add(id);
            }
        }
        return out;
    }

    Set<String> declares() {
        Set<String> out = new LinkedHashSet<>();
        if (atlas.manifest.get("build") instanceof Map<?, ?> b && b.get("declares") instanceof List<?> l) {
            l.forEach(d -> out.add(String.valueOf(d)));
        }
        return out;
    }

    String manifestString(String key) {
        Object v = atlas.manifest.get(key);
        return v == null ? null : v.toString();
    }
}
