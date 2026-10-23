package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.extract.EntryPoint;
import studio.volkoff.atlas.extract.EntryPointExtractor;
import studio.volkoff.atlas.model.Origin;
import studio.volkoff.atlas.model.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The regex path for Kotlin and Groovy sources and Java files javac could not parse. It recognises framework
 * annotations by name with literal attribute values only; every record is {@code INFERRED} at best.
 */
public final class RegexEntryPoints {

    private record Annotation(String name, String args, int start) {
    }

    private record ClassDecl(String name, int start, List<Annotation> annos) {
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:class|interface|object)\\s+([A-Za-z_]\\w*)");
    private static final Pattern KOTLIN_FUN = Pattern.compile("\\bfun\\s+(?:<[^>]*>\\s*)?(?:[\\w.]+\\.)?([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern JAVA_METHOD = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern STRING = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern KOTLIN_PARAM = Pattern.compile("\\(\\s*(?:@\\w+\\s+)*\\w+\\s*:\\s*([\\w.]+)");
    private static final Pattern JAVA_PARAM = Pattern.compile("\\(\\s*(?:@\\w+\\s+)*(?:final\\s+)?([\\w.]+)(?:<[^>]*>)?\\s+\\w+");
    private static final Map<String, String> SHORTCUTS = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "return", "new", "synchronized");
    private static final Pattern CONSTANT = Pattern.compile(
            "const\\s+val\\s+(\\w+)\\s*(?::\\s*String)?\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
                    + "|(?:static\\s+final|final\\s+static)\\s+(?:String\\s+)?(\\w+)\\s*=\\s*"
                    + "(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^'\\\\]|\\\\.)*)')[ \\t]*(?:;|\\r?\\n|\\z)");
    private static final Pattern KOTLIN_TEMPLATE = Pattern.compile("(?<!\\\\)\\$\\{([\\w.]+)\\}|(?<!\\\\)\\$([A-Za-z_]\\w*)");

    private final Map<String, String> constants = new java.util.HashMap<>();
    private final Set<String> ambiguous = new java.util.HashSet<>();

    public List<EntryPoint> scan(Workspace.SourceEntry entry, String source, Origin origin) {
        String text = stripComments(source);
        Matcher pm = PACKAGE.matcher(text);
        String pkg = pm.find() ? pm.group(1) + "." : "";
        List<EntryPoint> out = new ArrayList<>();
        List<ClassDecl> classes = new ArrayList<>();
        List<Annotation> pending = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
                continue;
            }
            if (c == '\'') {
                i = skipChar(text, i);
                continue;
            }
            if (c == '@' && i + 1 < text.length() && Character.isJavaIdentifierStart(text.charAt(i + 1))) {
                int nameEnd = i + 1;
                while (nameEnd < text.length() && (Character.isJavaIdentifierPart(text.charAt(nameEnd)) || text.charAt(nameEnd) == '.')) {
                    nameEnd++;
                }
                if (nameEnd < text.length() && text.charAt(nameEnd) == ':') {
                    // Kotlin use-site target, @field:NotBlank: the annotation is the part after the colon.
                    int targetEnd = nameEnd + 1;
                    while (targetEnd < text.length() && (Character.isJavaIdentifierPart(text.charAt(targetEnd)) || text.charAt(targetEnd) == '.')) {
                        targetEnd++;
                    }
                    i = nameEnd;
                    nameEnd = targetEnd;
                }
                String name = text.substring(i + 1, nameEnd);
                int after = skipSpaces(text, nameEnd);
                String args = "";
                int next = nameEnd;
                if (after < text.length() && text.charAt(after) == '(') {
                    int close = matchParen(text, after);
                    args = text.substring(after + 1, Math.max(after + 1, close));
                    next = close + 1;
                }
                pending.add(new Annotation(simple(name), args, i));
                i = next;
                continue;
            }
            if (!pending.isEmpty() && !Character.isWhitespace(c)) {
                int headerEnd = headerEnd(text, i);
                String header = text.substring(i, headerEnd);
                Matcher cm = CLASS_DECL.matcher(header);
                Matcher km = KOTLIN_FUN.matcher(header);
                if (cm.find() && (header.indexOf('(') < 0 || cm.start() < header.indexOf('('))) {
                    classes.add(new ClassDecl(cm.group(1), i, List.copyOf(pending)));
                } else if (km.find()) {
                    method(entry, origin, pkg, text, classes, pending, km.group(1), header, i, out);
                } else {
                    Matcher jm = JAVA_METHOD.matcher(header);
                    if (jm.find() && !KEYWORDS.contains(jm.group(1))) {
                        method(entry, origin, pkg, text, classes, pending, jm.group(1), header, i, out);
                    }
                }
                pending.clear();
                i = headerEnd;
                continue;
            }
            if (pending.isEmpty() && Character.isJavaIdentifierStart(c)) {
                Matcher cm = CLASS_DECL.matcher(text).region(i, Math.min(text.length(), i + 200));
                if (cm.lookingAt()) {
                    classes.add(new ClassDecl(cm.group(1), i, List.of()));
                    i = cm.end();
                    continue;
                }
                while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                    i++;
                }
                continue;
            }
            i++;
        }
        return out;
    }

    private void method(Workspace.SourceEntry entry, Origin origin, String pkg, String text, List<ClassDecl> classes,
                        List<Annotation> annos, String methodName, String header, int pos, List<EntryPoint> out) {
        ClassDecl cls = null;
        for (ClassDecl c : classes) {
            if (c.start() < pos) {
                cls = c;
            }
        }
        if (cls == null) {
            return;
        }
        int line = lineOf(text, pos);
        String fqn = pkg + cls.name();
        boolean kotlin = origin == Origin.KOTLIN;
        boolean controller = cls.annos().stream().anyMatch(a -> a.name().equals("RestController") || a.name().equals("Controller"));
        for (Annotation a : annos) {
            String name = a.name();
            if (controller && (name.equals("RequestMapping") || SHORTCUTS.containsKey(name))) {
                List<String> prefixes = List.of("");
                Status status = Status.INFERRED;
                for (Annotation ca : cls.annos()) {
                    if (ca.name().equals("RequestMapping")) {
                        Values v = values(attribute(ca.args(), "path", "value"), kotlin);
                        prefixes = v.items();
                        status = status.worst(v.status());
                    }
                }
                Values paths = values(attribute(a.args(), "path", "value"), kotlin);
                Set<String> methods = new TreeSet<>();
                if (SHORTCUTS.containsKey(name)) {
                    methods.add(SHORTCUTS.get(name));
                } else {
                    Matcher m = Pattern.compile("RequestMethod\\.(\\w+)").matcher(a.args());
                    while (m.find()) {
                        methods.add(m.group(1));
                    }
                }
                for (String p : prefixes) {
                    for (String q : paths.items()) {
                        add(out, entry, origin, "HTTP", methods.isEmpty() ? "ANY" : String.join(",", methods),
                                EntryPointExtractor.joinPaths(p, q), fqn, methodName, line, name, status.worst(paths.status()));
                    }
                }
            } else if (name.equals("Scheduled")) {
                String cron = attribute(a.args(), "cron");
                String subkind = cron != null ? "CRON"
                        : attribute(a.args(), "fixedRate") != null || attribute(a.args(), "fixedRateString") != null ? "FIXED_RATE"
                        : attribute(a.args(), "fixedDelay") != null || attribute(a.args(), "fixedDelayString") != null ? "FIXED_DELAY"
                        : "ONE_TIME";
                String raw = cron != null ? cron : firstNonNull(attribute(a.args(), "fixedRate"), attribute(a.args(), "fixedRateString"),
                        attribute(a.args(), "fixedDelay"), attribute(a.args(), "fixedDelayString"),
                        attribute(a.args(), "initialDelay"), attribute(a.args(), "initialDelayString"));
                Values v = values(raw, kotlin);
                String target = String.join(",", v.items());
                if (raw != null && !raw.trim().startsWith("\"") && raw.trim().matches("[\\d_L]+")) {
                    target = raw.trim().replace("_", "").replace("L", "") + " MILLISECONDS";
                }
                add(out, entry, origin, "SCHEDULED", subkind, target, fqn, methodName, line, name, v.status());
            } else if (name.equals("KafkaListener") || name.equals("SqsListener") || name.equals("RabbitListener")
                    || name.equals("JmsListener")) {
                String subkind = switch (name) {
                    case "KafkaListener" -> "KAFKA";
                    case "SqsListener" -> "SQS";
                    case "RabbitListener" -> "RABBIT";
                    default -> "JMS";
                };
                Values v = values(attribute(a.args(), "topics", "queues", "queueNames", "destination", "value"), kotlin);
                add(out, entry, origin, "MESSAGING", subkind, String.join(",", v.items()), fqn, methodName, line, name, v.status());
            } else if (name.equals("EventListener") || name.equals("TransactionalEventListener")) {
                Matcher pm = (origin == Origin.KOTLIN ? KOTLIN_PARAM : JAVA_PARAM).matcher(header);
                String event = pm.find() ? simple(pm.group(1)) : "<none>";
                add(out, entry, origin, "EVENT", name.equals("EventListener") ? "EVENT_LISTENER" : "TRANSACTIONAL_EVENT_LISTENER",
                        event, fqn, methodName, line, name, Status.INFERRED);
            }
        }
    }

    private static void add(List<EntryPoint> out, Workspace.SourceEntry entry, Origin origin, String kind, String subkind,
                            String target, String cls, String method, int line, String anno, Status status) {
        EntryPoint ep = new EntryPoint(kind, subkind, target, entry.module(), cls, method, entry.relPath(), line,
                "@" + anno, status.worst(Status.INFERRED), origin);
        ep.via("regex");
        out.add(ep);
    }

    private record Values(List<String> items, Status status) {
    }

    /**
     * Collects string constants ({@code const val} in Kotlin, {@code static final String} in Java,
     * single-line {@code static final} strings in Groovy) from every regex-path file, keyed by {@code Owner.NAME}, and by bare {@code NAME} when unique.
     */
    public void collectConstants(String source) {
        String text = stripComments(source);
        List<int[]> owners = new ArrayList<>();
        List<String> ownerNames = new ArrayList<>();
        Matcher cm = CLASS_DECL.matcher(text);
        while (cm.find()) {
            owners.add(new int[] {cm.start()});
            ownerNames.add(cm.group(1));
        }
        Matcher m = CONSTANT.matcher(text);
        while (m.find()) {
            String owner = null;
            for (int i = 0; i < owners.size(); i++) {
                if (owners.get(i)[0] < m.start()) {
                    owner = ownerNames.get(i);
                }
            }
            String name = m.group(1) != null ? m.group(1) : m.group(3);
            String value = (m.group(1) != null ? m.group(2) : m.group(4) != null ? m.group(4) : m.group(5)).replace("\\$", "$");
            if (owner != null) {
                constants.put(owner + "." + name, value);
            }
            String previous = constants.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) {
                ambiguous.add(name);
            }
        }
    }

    /**
     * Values of an attribute: array elements, each a {@code +} concatenation of literals and
     * constants found by {@link #collectConstants}. Anything unresolvable becomes {@code <text>}
     * and UNKNOWN.
     */
    private Values values(String raw, boolean kotlin) {
        if (raw == null || raw.isBlank()) {
            return new Values(List.of(""), Status.INFERRED);
        }
        String body = raw.trim();
        if (body.startsWith("arrayOf(") && body.endsWith(")")) {
            body = body.substring("arrayOf(".length(), body.length() - 1);
        } else if ((body.startsWith("[") && body.endsWith("]")) || (body.startsWith("{") && body.endsWith("}"))) {
            body = body.substring(1, body.length() - 1);
        }
        List<String> items = new ArrayList<>();
        Status status = Status.INFERRED;
        for (String element : splitTopLevel(body)) {
            if (element.isBlank()) {
                continue;
            }
            StringBuilder value = new StringBuilder();
            for (String part : splitConcatenation(element)) {
                String p = part.trim();
                Matcher lit = STRING.matcher(p);
                if (lit.matches()) {
                    String s = lit.group(1) != null ? lit.group(1) : lit.group(2);
                    if (kotlin) {
                        Matcher t = KOTLIN_TEMPLATE.matcher(s);
                        StringBuilder sb = new StringBuilder();
                        while (t.find()) {
                            String name = t.group(1) != null ? t.group(1) : t.group(2);
                            String resolved = constant(name);
                            if (resolved == null) {
                                status = Status.UNKNOWN;
                            }
                            t.appendReplacement(sb, Matcher.quoteReplacement(resolved != null ? resolved : "<" + name + ">"));
                        }
                        t.appendTail(sb);
                        s = sb.toString();
                    }
                    // Kotlin and Groovy escape a Spring placeholder's dollar; Java has no such escape.
                    value.append(s.replace("\\$", "$"));
                } else {
                    String resolved = constant(p);
                    if (resolved == null) {
                        status = Status.UNKNOWN;
                        value.append('<').append(p).append('>');
                    } else {
                        value.append(resolved);
                    }
                }
            }
            items.add(value.toString());
        }
        return items.isEmpty() ? new Values(List.of(""), status) : new Values(items, status);
    }

    private String constant(String name) {
        String v = constants.get(name);
        if (v == null && name.contains(".")) {
            String[] segs = name.split("\\.");
            v = constants.get(segs[segs.length - 2] + "." + segs[segs.length - 1]);
        }
        return ambiguous.contains(name) ? null : v;
    }

    private static List<String> splitConcatenation(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipString(s, i) - 1;
            } else if (c == '\'') {
                i = skipChar(s, i) - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '+' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** The raw text of a named attribute, or of the unnamed argument when {@code value} is among the names. */
    private static String attribute(String args, String... names) {
        List<String> parts = splitTopLevel(args);
        for (String n : names) {
            for (String p : parts) {
                int eq = topLevelEquals(p);
                if (eq > 0 && p.substring(0, eq).trim().equals(n)) {
                    return p.substring(eq + 1).trim();
                }
            }
        }
        // Kotlin allows a positional value before named arguments: @PostMapping("/x", consumes = [...]).
        for (String n : names) {
            if (n.equals("value") && !parts.isEmpty() && topLevelEquals(parts.get(0)) < 0 && !parts.get(0).isBlank()) {
                return parts.get(0).trim();
            }
        }
        return null;
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipString(s, i) - 1;
            } else if (c == '\'') {
                i = skipChar(s, i) - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static int topLevelEquals(String part) {
        int depth = 0;
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c == '"') {
                i = skipString(part, i) - 1;
            } else if (c == '\'') {
                i = skipChar(part, i) - 1;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == '=' && depth == 0 && (i + 1 >= part.length() || part.charAt(i + 1) != '=')) {
                return i;
            }
        }
        return -1;
    }

    private static int headerEnd(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i) - 1;
            } else if (c == '\'') {
                i = skipChar(text, i) - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (--depth < 0) {
                    return i;
                }
            } else if (depth == 0 && (c == '{' || c == ';' || c == '=')) {
                return i;
            } else if (depth == 0 && c == '@' && i > from) {
                return i;
            }
        }
        return text.length();
    }

    private static int matchParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i) - 1;
            } else if (c == '\'') {
                i = skipChar(text, i) - 1;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return text.length() - 1;
    }

    private static int skipString(String text, int quote) {
        if (text.startsWith("\"\"\"", quote)) {
            int end = text.indexOf("\"\"\"", quote + 3);
            return end < 0 ? text.length() : end + 3;
        }
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"' || c == '\n') {
                return i + 1;
            }
        }
        return text.length();
    }

    /**
     * Skips a char literal such as {@code '"'} or {@code '\''}, or a Groovy single-quoted string
     * such as {@code '/api'} or {@code '''text'''}; returns the index after it.
     */
    private static int skipChar(String text, int quote) {
        if (text.startsWith("'''", quote)) {
            int end = text.indexOf("'''", quote + 3);
            return end < 0 ? text.length() : end + 3;
        }
        if (quote + 1 < text.length() && text.charAt(quote + 1) == '\\') {
            int end = text.indexOf('\'', quote + 3);
            return end < 0 ? quote + 1 : end + 1;
        }
        if (quote + 2 < text.length() && text.charAt(quote + 2) == '\'') {
            return quote + 3;
        }
        // Only Groovy gets here: a Java or Kotlin char literal is one character or an escape.
        for (int i = quote + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '\'' || c == '\n') {
                return i + 1;
            }
        }
        return text.length();
    }

    private static int skipSpaces(String text, int i) {
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Replaces comments with spaces so offsets and line numbers stay valid. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                i = skipString(s, i) - 1;
            } else if (c == '\'') {
                i = skipChar(s, i) - 1;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') {
                    out.setCharAt(i++, ' ');
                }
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? s.length() : end + 2;
                for (int j = i; j < end; j++) {
                    if (s.charAt(j) != '\n') {
                        out.setCharAt(j, ' ');
                    }
                }
                i = end - 1;
            }
        }
        return out.toString();
    }

    private static int lineOf(String text, int pos) {
        int line = 1;
        for (int i = 0; i < pos && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String simple(String name) {
        int lt = name.indexOf('<');
        String s = lt >= 0 ? name.substring(0, lt) : name;
        return s.substring(s.lastIndexOf('.') + 1);
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
