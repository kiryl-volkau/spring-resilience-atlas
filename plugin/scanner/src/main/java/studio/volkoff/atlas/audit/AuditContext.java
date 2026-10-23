package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.report.Places;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

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

    private static final Pattern MIGRATION_DIR = Pattern.compile(
            "(^|/)(db/migration|db/changelog|liquibase|flyway|migrations?)(/|$)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MIGRATION_EXTENSIONS = Set.of("sql", "xml", "yaml", "yml", "json");
    private static final Set<String> SKIPPED_DIRS = Set.of(".git", ".reliability", ".idea", "target", "build", "out", "dist",
            "node_modules", ".gradle", ".mvn");
    private Map<String, String> migrations;

    /**
     * Flyway and Liquibase files of the repository, by relative path, with their content in lower
     * case: where unique indexes live when the entities do not declare them. Read once.
     */
    Map<String, String> migrations() throws IOException {
        if (migrations != null) {
            return migrations;
        }
        Map<String, String> out = new TreeMap<>();
        Files.walkFileTree(repo, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(repo) && SKIPPED_DIRS.contains(String.valueOf(dir.getFileName()))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String rel = repo.relativize(file).toString().replace('\\', '/');
                String name = file.getFileName().toString();
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                if (MIGRATION_EXTENSIONS.contains(ext) && MIGRATION_DIR.matcher(rel).find() && attrs.size() < 2_000_000) {
                    out.put(rel, new String(Files.readAllBytes(file), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        migrations = out;
        return out;
    }

    private Map<String, List<Map<String, Object>>> typesBySimpleName;

    /** Types of the atlas with this simple name, such as an entity or a repository a write names. */
    List<Map<String, Object>> types(String simpleName) throws IOException {
        if (typesBySimpleName == null) {
            typesBySimpleName = new HashMap<>();
            for (Map<String, Object> t : atlas.types()) {
                String fqn = String.valueOf(t.get("fqn"));
                typesBySimpleName.computeIfAbsent(fqn.substring(fqn.lastIndexOf('.') + 1), k -> new ArrayList<>()).add(t);
            }
        }
        return typesBySimpleName.getOrDefault(simpleName, List.of());
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
