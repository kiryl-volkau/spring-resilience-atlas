package studio.volkoff.atlas.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The scalar keys of a repository's main application config, with the file and line that set
 * each. Keys compare in Spring Boot's relaxed form ({@code open-in-view} = {@code openInView}).
 * A module reads its own config files, else those of the nearest enclosing module that has some,
 * else every config file of the repository. Profiles named like local or test setups do not count.
 */
public final class AppConfig {

    /** One key as set in one file: the key as written, its value, the profile of its file or document ("" for none). */
    public record Entry(String key, String value, String file, int line, String profile) {
    }

    /** A config file: its path, module, profile and keys in file order. */
    record File(String relPath, String module, String profile, List<Entry> entries) {
    }

    private static final Pattern YAML_KEY = Pattern.compile("^(\\s*)([\\w.\\-\\[\\]]+)\\s*:\\s*(.*)$");
    private static final Pattern PROFILE_FILE = Pattern.compile("^(?:application|bootstrap)-([\\w-]+)\\.(?:yml|yaml|properties)$");
    /** Profiles of local runs, tests and demos: their settings say nothing about production. */
    private static final Pattern NON_PRODUCTION = Pattern.compile("(?i).*(test|dev|local|demo|h2|embedded|docker|compose|mock|stub|e2e|^it$|^ci$).*");
    private static final java.util.Set<String> PROFILE_KEYS = java.util.Set.of("springconfigactivateonprofile", "springprofiles");

    private final List<File> files = new ArrayList<>();

    private AppConfig() {
    }

    public static AppConfig read(Workspace ws) {
        AppConfig c = new AppConfig();
        for (Workspace.SourceEntry e : ws.configFiles) {
            try {
                List<String> lines = Files.readAllLines(e.path(), StandardCharsets.ISO_8859_1);
                String name = e.path().getFileName().toString();
                Matcher m = PROFILE_FILE.matcher(name);
                String profile = m.matches() ? m.group(1) : "";
                List<Entry> entries = name.endsWith(".properties") ? properties(lines, e.relPath(), profile) : yaml(lines, e.relPath(), profile);
                c.files.add(new File(e.relPath(), e.module(), profile, entries));
            } catch (IOException ignored) {
                // Unreadable config only loses config rules.
            }
        }
        return c;
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }

    /**
     * The value production uses for {@code key} in {@code module}: a production profile's value
     * over the base value, null when no production file sets it.
     */
    public Entry effective(String module, String key) {
        String k = relaxed(key);
        Entry base = null;
        Entry profiled = null;
        for (File f : scope(module)) {
            if (!f.profile().isEmpty() && NON_PRODUCTION.matcher(f.profile()).matches()) {
                continue;
            }
            for (Entry e : f.entries()) {
                if (!relaxed(e.key()).equals(k) || !e.profile().isEmpty() && NON_PRODUCTION.matcher(e.profile()).matches()) {
                    continue;
                }
                if (e.profile().isEmpty() && base == null) {
                    base = e;
                } else if (!e.profile().isEmpty() && profiled == null) {
                    profiled = e;
                }
            }
        }
        return profiled != null ? profiled : base;
    }

    /** Whether a production file of the module sets any key that starts with {@code prefix} and contains {@code part}. */
    public boolean anyKey(String module, String prefix, String part) {
        String p = relaxed(prefix);
        String q = relaxed(part);
        for (File f : scope(module)) {
            if (!f.profile().isEmpty() && NON_PRODUCTION.matcher(f.profile()).matches()) {
                continue;
            }
            for (Entry e : f.entries()) {
                String k = relaxed(e.key());
                if (k.startsWith(p) && k.contains(q)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The module's base config file, the one evidence points at when a key is missing; null when it has none. */
    public String baseFile(String module) {
        for (File f : scope(module)) {
            if (f.profile().isEmpty() && f.relPath().contains("application")) {
                return f.relPath();
            }
        }
        return null;
    }

    /** File names of the module's production config, for a detail that says where a key was looked for. */
    public List<String> looked(String module) {
        List<String> out = new ArrayList<>();
        for (File f : scope(module)) {
            if (f.profile().isEmpty() || !NON_PRODUCTION.matcher(f.profile()).matches()) {
                out.add(f.relPath().substring(f.relPath().lastIndexOf('/') + 1));
            }
        }
        return out;
    }

    private List<File> scope(String module) {
        String m = module == null || module.isEmpty() ? "." : module;
        while (true) {
            List<File> own = new ArrayList<>();
            for (File f : files) {
                if (f.module().equals(m)) {
                    own.add(f);
                }
            }
            if (!own.isEmpty()) {
                return own;
            }
            if (m.equals(".")) {
                return files;
            }
            int slash = m.lastIndexOf('/');
            m = slash < 0 ? "." : m.substring(0, slash);
        }
    }

    static String relaxed(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static List<Entry> properties(List<String> lines, String file, String profile) {
        List<Entry> out = new ArrayList<>();
        List<Entry> doc = new ArrayList<>();
        String docProfile = profile;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.equals("#---") || l.equals("!---")) {
                flush(out, doc, docProfile);
                docProfile = profile;
                continue;
            }
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) {
                continue;
            }
            int eq = l.indexOf('=');
            int colon = l.indexOf(':');
            int sep = eq < 0 ? colon : colon < 0 ? eq : Math.min(eq, colon);
            if (sep <= 0) {
                continue;
            }
            String key = l.substring(0, sep).trim();
            String value = unquote(l.substring(sep + 1).trim());
            if (PROFILE_KEYS.contains(relaxed(key))) {
                docProfile = value;
            }
            doc.add(new Entry(key, value, file, i + 1, profile));
        }
        flush(out, doc, docProfile);
        return out;
    }

    /** A flattening YAML reader for scalar keys; list items and multi-line values are skipped. */
    private static List<Entry> yaml(List<String> lines, String file, String profile) {
        List<Entry> out = new ArrayList<>();
        List<Entry> doc = new ArrayList<>();
        String docProfile = profile;
        Deque<Map.Entry<Integer, String>> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("---")) {
                flush(out, doc, docProfile);
                docProfile = profile;
                stack.clear();
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher m = YAML_KEY.matcher(line);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!stack.isEmpty() && stack.peekLast().getKey() >= indent) {
                stack.removeLast();
            }
            StringBuilder key = new StringBuilder();
            for (Map.Entry<Integer, String> e : stack) {
                key.append(e.getValue()).append('.');
            }
            key.append(m.group(2));
            String value = m.group(3).replaceAll("\\s+#.*$", "").trim();
            if (value.isEmpty() || value.equals("|") || value.equals(">")) {
                stack.addLast(Map.entry(indent, m.group(2)));
                continue;
            }
            String v = unquote(value);
            if (PROFILE_KEYS.contains(relaxed(key.toString()))) {
                docProfile = v;
            }
            doc.add(new Entry(key.toString(), v, file, i + 1, profile));
        }
        flush(out, doc, docProfile);
        return out;
    }

    /** A document that activates on a profile gives its keys that profile. */
    private static void flush(List<Entry> out, List<Entry> doc, String docProfile) {
        for (Entry e : doc) {
            out.add(docProfile.equals(e.profile()) ? e : new Entry(e.key(), e.value(), e.file(), e.line(), docProfile));
        }
        doc.clear();
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
