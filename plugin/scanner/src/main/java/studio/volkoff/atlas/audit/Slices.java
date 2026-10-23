package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.Origin;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.scan.JavaSourceParser;
import studio.volkoff.atlas.scan.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Method bodies cut from the working tree by line, for evidence packets and fingerprints. */
final class Slices {

    /** Lines {@code from..to} of {@code file}, inclusive, 1-based; {@code text} holds exactly those lines. */
    record Slice(String file, String method, int from, int to, List<String> text) {
        String label() {
            return file + ":" + from + "-" + to;
        }
    }

    static final int MAX_LINES = 120;
    static final int AROUND = 15;
    private static final int FALLBACK = 40;

    private final Path repo;
    private final Map<String, List<String>> lines = new HashMap<>();
    private final Map<String, List<MethodDecl>> methods = new HashMap<>();
    private final Map<String, Map<MethodDecl, String>> owners = new HashMap<>();

    Slices(Path repo) {
        this.repo = repo;
    }

    /** Parses the Java files among {@code files} in one javac task; other files are only read. */
    void load(Collection<String> files) throws Exception {
        List<Workspace.SourceEntry> entries = new ArrayList<>();
        for (String f : new LinkedHashSet<>(files)) {
            if (lines.containsKey(f)) {
                continue;
            }
            Path p = repo.resolve(f).normalize();
            if (!p.startsWith(repo) || !Files.isRegularFile(p)) {
                lines.put(f, null);
                continue;
            }
            Workspace.SourceEntry e = new Workspace.SourceEntry(p, f, ".", f.endsWith(".java") ? Origin.SOURCE
                    : f.endsWith(".kt") ? Origin.KOTLIN : Origin.GROOVY);
            lines.put(f, JavaSourceParser.readSource(e).lines().toList());
            if (f.endsWith(".java")) {
                entries.add(e);
            }
        }
        if (entries.isEmpty()) {
            return;
        }
        for (SourceFile sf : new JavaSourceParser().parse(entries, null).parsed()) {
            List<MethodDecl> ms = new ArrayList<>();
            Map<MethodDecl, String> own = new HashMap<>();
            for (TypeDecl t : sf.types()) {
                for (MethodDecl m : t.methods) {
                    ms.add(m);
                    own.put(m, t.simpleName);
                }
            }
            methods.put(sf.relPath(), ms);
            owners.put(sf.relPath(), own);
        }
    }

    boolean exists(String file) {
        return lines.get(file) != null;
    }

    /**
     * The innermost method of {@code file} whose declaration spans {@code line}. A file javac could
     * not parse, or a line outside any method, gets the {@value #FALLBACK} lines from the line.
     */
    Slice enclosing(String file, int line) throws IOException {
        List<String> text = lines.get(file);
        if (text == null) {
            return null;
        }
        MethodDecl best = null;
        for (MethodDecl m : methods.getOrDefault(file, List.of())) {
            if (m.line() <= line && m.endLine() >= line && (best == null || m.line() > best.line())) {
                best = m;
            }
        }
        if (best == null) {
            int from = Math.max(1, line - 5);
            return cut(file, "?", from, Math.min(text.size(), from + FALLBACK - 1), text);
        }
        String name = owners.get(file).get(best) + "." + best.name();
        return cut(file, name, best.line(), Math.min(text.size(), best.endLine()), text);
    }

    private static Slice cut(String file, String method, int from, int to, List<String> text) {
        return new Slice(file, method, from, to, List.copyOf(text.subList(from - 1, Math.max(from - 1, to))));
    }

    /**
     * The slice as packet text: each line prefixed with its file line number. A slice longer than
     * {@value #MAX_LINES} lines shows {@value #AROUND} lines around each of {@code focus} with a
     * {@code …} marker for every gap.
     */
    static String render(Slice s, Set<Integer> focus) {
        StringBuilder out = new StringBuilder();
        int width = String.valueOf(s.to()).length();
        boolean longSlice = s.to() - s.from() + 1 > MAX_LINES;
        boolean gap = false;
        for (int n = s.from(); n <= s.to(); n++) {
            boolean shown = !longSlice || n - s.from() < 3 || s.to() - n < 2;
            for (int f : focus) {
                if (Math.abs(n - f) <= AROUND) {
                    shown = true;
                }
            }
            if (!shown) {
                if (!gap) {
                    out.append(" ".repeat(width)).append("  …\n");
                    gap = true;
                }
                continue;
            }
            gap = false;
            String num = String.valueOf(n);
            out.append(" ".repeat(width - num.length())).append(num).append("  ").append(s.text().get(n - s.from())).append('\n');
        }
        return out.toString();
    }

    /** The slice's code with whitespace collapsed: what a fingerprint hashes, so reindenting or moving it changes nothing. */
    static String normalised(Slice s) {
        return String.join(" ", s.text()).replaceAll("\\s+", " ").trim();
    }
}
