package studio.volkoff.atlas.query;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks every {@code {file, line, snippet}} object in a flow or finding document against the
 * working tree. This is what keeps fabricated evidence out of flows and findings, so it is
 * strict:
 * <ul>
 *   <li>the snippet, whitespace-normalised, must occur within two lines of {@code line};</li>
 *   <li>an evidence object with a missing or mistyped field is MALFORMED;</li>
 *   <li>a snippet without a non-keyword identifier ({@code }}, {@code public}) is TRIVIAL;</li>
 *   <li>every hop and boundary of a flow must carry evidence;</li>
 *   <li>a document without any evidence fails.</li>
 * </ul>
 */
public final class EvidenceVerifier {

    public record Result(String report, boolean passed) {
    }

    /** The outcome for one evidence object. */
    public enum Check { OK, MISMATCH, TRIVIAL, MALFORMED, MISSING_FILE }

    private static final int WINDOW = 2;
    private static final String BOM = String.valueOf((char) 0xFEFF);
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");
    private static final Set<String> KEYWORDS = Set.of("abstract", "boolean", "break", "byte", "case", "catch", "char",
            "class", "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for",
            "if", "implements", "import", "instanceof", "int", "interface", "long", "new", "null", "package", "private",
            "protected", "public", "return", "short", "static", "super", "switch", "this", "throw", "throws", "true",
            "false", "try", "void", "var", "while", "record", "yield");

    private final Path repo;
    private final Map<String, List<String>> cache = new HashMap<>();

    public EvidenceVerifier(Path repo) {
        this.repo = repo;
    }

    /**
     * Checks one {@code {file, line, snippet}} object: the snippet, whitespace-normalised, must occur
     * within two lines of {@code line} in the working tree and contain a non-keyword identifier.
     * Never throws for bad input; a missing or mistyped field is {@link Check#MALFORMED}.
     */
    public Check check(Map<?, ?> evidence) throws IOException {
        if (!(evidence.get("file") instanceof String file) || !(evidence.get("line") instanceof Number n) || n.intValue() < 1
                || !(evidence.get("snippet") instanceof String snippet)) {
            return Check.MALFORMED;
        }
        if (!meaningful(snippet)) {
            return Check.TRIVIAL;
        }
        List<String> lines = lines(file);
        if (lines == null) {
            return Check.MISSING_FILE;
        }
        int line = n.intValue();
        StringBuilder window = new StringBuilder();
        for (int i = Math.max(1, line - WINDOW); i <= Math.min(lines.size(), line + WINDOW); i++) {
            window.append(lines.get(i - 1)).append(' ');
        }
        return normalize(window.toString()).contains(normalize(snippet)) ? Check.OK : Check.MISMATCH;
    }

    public Result verify(Path document) throws IOException {
        String text = Files.readString(document, StandardCharsets.UTF_8);
        if (text.startsWith(BOM)) {
            text = text.substring(1);
        }
        text = text.trim();
        List<Object> roots = new ArrayList<>();
        Object whole = null;
        try {
            whole = Json.parse(text);
        } catch (IllegalArgumentException jsonLines) {
            // One JSON value per line: parsed line by line below.
        }
        if (whole != null) {
            roots.add(whole);
        } else {
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    roots.add(Json.parse(line));
                }
            }
        }
        StringBuilder out = new StringBuilder();
        int malformed = 0;
        for (Object r : roots) {
            malformed += missingFlowEvidence(r, out);
        }
        List<Map<?, ?>> items = new ArrayList<>();
        for (Object r : roots) {
            malformed += collect(r, items, out);
        }
        int ok = 0;
        int mismatched = 0;
        int missing = 0;
        int trivial = 0;
        for (Map<?, ?> e : items) {
            String file = (String) e.get("file");
            int line = ((Number) e.get("line")).intValue();
            String snippet = (String) e.get("snippet");
            switch (check(e)) {
                case OK -> ok++;
                case TRIVIAL -> {
                    trivial++;
                    out.append("TRIVIAL\t").append(file).append(':').append(line).append('\t').append(shown(snippet)).append('\n');
                }
                case MISSING_FILE -> {
                    missing++;
                    out.append("MISSING_FILE\t").append(file).append(':').append(line).append('\n');
                }
                default -> {
                    mismatched++;
                    out.append("MISMATCH\t").append(file).append(':').append(line).append('\t').append(shown(snippet)).append('\n');
                }
            }
        }
        if (items.isEmpty()) {
            out.append("NO_EVIDENCE\tthe document contains no {file, line, snippet} objects\n");
        }
        boolean passed = !items.isEmpty() && mismatched == 0 && missing == 0 && trivial == 0 && malformed == 0;
        String summary = "# evidence: " + items.size() + " checked, " + ok + " ok, " + mismatched + " mismatched, "
                + missing + " missing files, " + malformed + " malformed, " + trivial + " trivial"
                + (passed ? "" : " - FAILED") + "\n";
        return new Result(summary + out, passed);
    }

    /** Every hop and boundary of a flow document must carry an evidence object. */
    private static int missingFlowEvidence(Object root, StringBuilder out) {
        if (!(root instanceof Map<?, ?> doc)) {
            return 0;
        }
        int count = 0;
        for (String key : List.of("hops", "boundaries")) {
            if (doc.get(key) instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    if (!(list.get(i) instanceof Map<?, ?> m) || !(m.get("evidence") instanceof Map<?, ?>)) {
                        count++;
                        out.append("MALFORMED\t").append(key).append('[').append(i).append("] has no evidence object\n");
                    }
                }
            }
        }
        return count;
    }

    /** Collects well-formed evidence objects; returns how many malformed ones were found. */
    private static int collect(Object node, List<Map<?, ?>> out, StringBuilder report) {
        int malformed = 0;
        if (node instanceof Map<?, ?> m) {
            boolean looksLikeEvidence = m.containsKey("snippet") || (m.containsKey("file") && m.containsKey("line"));
            if (looksLikeEvidence) {
                if (m.get("file") instanceof String && m.get("line") instanceof Number n && n.intValue() >= 1
                        && m.get("snippet") instanceof String) {
                    out.add(m);
                } else {
                    malformed++;
                    report.append("MALFORMED\t").append(Json.write(m).replace('\n', ' '), 0,
                            Math.min(160, Json.write(m).length())).append('\n');
                }
            }
            for (Object v : m.values()) {
                malformed += collect(v, out, report);
            }
        } else if (node instanceof List<?> l) {
            for (Object v : l) {
                malformed += collect(v, out, report);
            }
        }
        return malformed;
    }

    private static boolean meaningful(String snippet) {
        Matcher m = IDENTIFIER.matcher(snippet);
        while (m.find()) {
            if (!KEYWORDS.contains(m.group())) {
                return true;
            }
        }
        return false;
    }

    private static String shown(String snippet) {
        String s = snippet.replace('\n', ' ');
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }

    private List<String> lines(String file) throws IOException {
        if (cache.containsKey(file)) {
            return cache.get(file);
        }
        Path p = repo.resolve(file).normalize();
        List<String> lines = null;
        if (p.startsWith(repo) && Files.isRegularFile(p)) {
            byte[] bytes = Files.readAllBytes(p);
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException e) {
                text = new String(bytes, Charset.isSupported("windows-1251") ? Charset.forName("windows-1251") : StandardCharsets.ISO_8859_1);
            }
            lines = (text.startsWith(BOM) ? text.substring(1) : text).lines().toList();
        }
        cache.put(file, lines);
        return lines;
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
