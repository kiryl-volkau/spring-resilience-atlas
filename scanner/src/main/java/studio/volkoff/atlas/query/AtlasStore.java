package studio.volkoff.atlas.query;

import studio.volkoff.atlas.json.Json;
import studio.volkoff.atlas.store.Git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read access to a built atlas plus the staleness line every query prints first. */
public final class AtlasStore {

    public static final class Missing extends RuntimeException {
        Missing(String message) {
            super(message);
        }
    }

    public final Path repo;
    public final Path dir;
    public final Map<String, Object> manifest;
    private List<Map<String, Object>> entrypoints;
    private List<Map<String, Object>> types;
    private List<Map<String, Object>> domains;
    private List<Map<String, Object>> effects;

    private AtlasStore(Path repo, Path dir, Map<String, Object> manifest) {
        this.repo = repo;
        this.dir = dir;
        this.manifest = manifest;
    }

    public static AtlasStore open(Path repo, Path dir) throws IOException {
        Path m = dir.resolve("manifest.json");
        if (!Files.exists(m)) {
            throw new Missing("atlas not found at " + dir + "; build it with the atlas-bootstrap skill "
                    + "(the scanner's scan command)");
        }
        return new AtlasStore(repo, dir, Json.parseObject(Files.readString(m, StandardCharsets.UTF_8)));
    }

    public List<Map<String, Object>> entrypoints() throws IOException {
        if (entrypoints == null) {
            entrypoints = readJsonl(dir.resolve("entrypoints.jsonl"));
        }
        return entrypoints;
    }

    public List<Map<String, Object>> types() throws IOException {
        if (types == null) {
            types = readJsonl(dir.resolve("types.jsonl"));
        }
        return types;
    }

    /** Rows of {@code effects.jsonl}, one per entry point; empty for an atlas built before effect analysis. */
    public List<Map<String, Object>> effects() throws IOException {
        if (effects == null) {
            effects = readJsonl(dir.resolve("effects.jsonl"));
        }
        return effects;
    }

    /** Rows of {@code domains.jsonl}, written by the domain-cartographer skill; empty when absent. */
    public List<Map<String, Object>> domains() throws IOException {
        if (domains == null) {
            Path f = dir.resolve("domains.jsonl");
            domains = Files.exists(f) ? readJsonl(f) : List.of();
        }
        return domains;
    }

    /** The domain whose package prefix is the longest match for {@code cls}, or {@code null}. */
    public String domainOf(String cls) throws IOException {
        String best = null;
        int bestLen = -1;
        for (Map<String, Object> d : domains()) {
            Object pkgs = d.get("packages");
            if (!(pkgs instanceof List<?> list)) {
                continue;
            }
            for (Object p : list) {
                String prefix = String.valueOf(p);
                if ((cls.equals(prefix) || cls.startsWith(prefix + ".")) && prefix.length() > bestLen) {
                    best = String.valueOf(d.get("domain"));
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    public String header() {
        Object commit = manifest.get("commit");
        String scannedDirty = Boolean.TRUE.equals(manifest.get("dirty")) ? ", scanned with uncommitted changes" : "";
        if (commit == null) {
            return "# atlas (no git commit recorded" + scannedDirty + ")";
        }
        String c = commit.toString();
        Git git = new Git(repo);
        String head = git.head();
        if (head == null) {
            return "# atlas @" + shortSha(c) + " (HEAD unknown" + scannedDirty + ")";
        }
        Integer changed = git.changedSourceFiles(c);
        String changes = changed == null ? "" : changed == 0 ? "clean" : changed + " source files differ from the scan";
        if (head.equals(c)) {
            return "# atlas @" + shortSha(c) + " (HEAD " + shortSha(head) + ", " + changes + scannedDirty + ")";
        }
        return "# atlas @" + shortSha(c) + " is behind HEAD " + shortSha(head) + " (" + changes
                + "); rescan with the atlas-bootstrap skill for exact results";
    }

    private static String shortSha(String sha) {
        return sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    private static final String BOM = String.valueOf((char) 0xFEFF);

    /** The rows of a JSON Lines file; empty when the file does not exist. A byte-order mark is ignored. */
    public static List<Map<String, Object>> readJsonl(Path f) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!Files.exists(f)) {
            return rows;
        }
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String row = line.startsWith(BOM) ? line.substring(1) : line;
            if (!row.isBlank()) {
                rows.add(Json.parseObject(row));
            }
        }
        return rows;
    }
}
