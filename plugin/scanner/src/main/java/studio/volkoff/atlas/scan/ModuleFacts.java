package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.effects.Effect;
import studio.volkoff.atlas.effects.EffectAnalyzer;
import studio.volkoff.atlas.effects.Signals;
import studio.volkoff.atlas.extract.EntryPoint;
import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facts of an operation's module that its call path does not show: application config and the
 * newest migrations. Each becomes a {@link Signals#CONFIG} effect whose symbol is the config key
 * or the migration file, so one place covers every operation the fact reaches.
 */
public final class ModuleFacts {

    public static final String SHUTDOWN = "server.shutdown";
    public static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";
    public static final String AUTO_COMMIT = "spring.kafka.consumer.enable-auto-commit";
    public static final String DDL_AUTO = "spring.jpa.hibernate.ddl-auto";
    public static final String FEIGN = "spring.cloud.openfeign.client.config";
    /** Detail prefix of a destructive statement in the newest migration of its directory. */
    public static final String MIGRATION = "migration ";

    private static final Set<String> REQUEST_KINDS = Set.of("HTTP", "SOAP", "GRPC", "GRAPHQL", "WEBSOCKET", "ROUTER", "PORTLET");
    private static final Set<String> READ_VERBS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> RISKY_DDL = Set.of("update", "create", "create-drop");
    private static final Pattern OTHER_STORE = Pattern.compile("^(Redis|MongoDB|DynamoDB) ");
    private static final Pattern MIGRATION_ROOT = Pattern.compile("(^|/)(db/migration|db/changelog|liquibase|flyway|migrations?)(?=/)");
    /** Liquibase master files only include other changelogs. */
    private static final Pattern INCLUDES_ONLY = Pattern.compile("(?i)(.*(master|root|index|main).*|changelog)\\.(xml|ya?ml|json)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern FLYWAY_VERSION = Pattern.compile("^[VR](\\d+(?:[._]\\d+)*)__");
    private static final Pattern DESTRUCTIVE_SQL = Pattern.compile("(?i)\\b(DROP\\s+(TABLE|COLUMN)|RENAME\\s+(COLUMN|TO)|ALTER\\s+COLUMN\\s+\\S+\\s+(SET\\s+DATA\\s+)?TYPE"
            + "|SET\\s+NOT\\s+NULL|MODIFY\\s+(COLUMN\\s+)?\\w+|CHANGE\\s+(COLUMN\\s+)?\\w+)\\b");
    private static final Pattern SQL_TABLE = Pattern.compile("(?i)\\b(?:ALTER|DROP)\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?([\\w.\"`\\[\\]]+)");
    private static final Pattern LIQUIBASE_CHANGE = Pattern.compile("(?i)(dropColumn|renameColumn|dropTable|modifyDataType|addNotNullConstraint|renameTable)\\b"
            + "[^\\n]*?tableName\\s*[=:]\\s*[\"']?([\\w.]+)");
    private static final Pattern ANNO_NAME = Pattern.compile("(?:name|value|collection)\\s*=\\s*\"([^\"]+)\"|\\(\\s*\"([^\"]+)\"");

    /** One destructive statement: the table it changes, where it is, and the statement as written. */
    private record Change(String table, String file, int line, String text) {
    }

    private final Workspace ws;
    private final AppConfig config;
    private final Set<String> feignClients = new HashSet<>();
    private final boolean feignOptionsInCode;
    /** Repository simple name to the table its entity maps to, lower case. */
    private final Map<String, String> tableByRepository = new HashMap<>();
    private final Map<String, List<Change>> changesByTable = new LinkedHashMap<>();

    public ModuleFacts(Workspace ws, AppConfig config, Index index) {
        this.ws = ws;
        this.config = config;
        boolean options = false;
        Map<String, String> tableByEntity = new HashMap<>();
        List<TypeDecl> repositories = new ArrayList<>();
        for (TypeDecl t : index.types()) {
            if (t.annos.stream().anyMatch(a -> a.simpleName().equals("FeignClient"))) {
                feignClients.add(t.simpleName);
            }
            if (t.file.importsFrom("feign")) {
                for (MethodDecl m : t.methods) {
                    options |= m.returnType() != null && m.returnType().endsWith("Options");
                }
            }
            String table = table(t);
            if (table != null) {
                tableByEntity.put(t.simpleName, table);
            }
            if (t.kind == TypeDecl.Kind.INTERFACE && !t.superTypes.isEmpty()) {
                repositories.add(t);
            }
        }
        this.feignOptionsInCode = options;
        for (TypeDecl r : repositories) {
            for (String s : r.superTypes) {
                int lt = s.indexOf('<');
                if (lt < 0) {
                    continue;
                }
                String entity = JavaSourceParser.simpleTypeName(s.substring(lt + 1).split("[,>]")[0].strip());
                String table = tableByEntity.get(entity);
                if (table != null) {
                    tableByRepository.put(r.simpleName, table);
                }
            }
        }
        readMigrations();
    }

    /** The result with this operation's module facts appended as effects. */
    public EffectAnalyzer.Result attach(EntryPoint ep, EffectAnalyzer.Result r) {
        if (!r.analysed()) {
            return r;
        }
        List<Effect> extra = new ArrayList<>();
        Set<String> signals = new HashSet<>();
        for (Effect e : r.effects()) {
            signals.add(e.signal());
        }
        boolean changes = signals.contains(Signals.DB_WRITE) || signals.contains(Signals.MESSAGE) || signals.contains(Signals.EXTERNAL)
                || signals.contains(Signals.ASYNC);
        boolean mutating = !ep.kind.equals("HTTP") && !ep.kind.equals("ROUTER")
                || java.util.Arrays.stream(ep.subkind.split(",")).anyMatch(v -> !READ_VERBS.contains(v.strip()));
        if (REQUEST_KINDS.contains(ep.kind) && mutating && changes) {
            gracefulShutdown(ep.module, extra);
        }
        boolean database = r.effects().stream().anyMatch(e -> e.signal().equals(Signals.DB_WRITE) || e.signal().equals(Signals.TX) || e.inReadOnlyTx());
        if (ep.kind.equals("HTTP") && signals.contains(Signals.EXTERNAL) && database && ws.declaresFor(ep.module, "data-jpa")) {
            openInView(ep.module, extra);
        }
        if (ep.kind.equals("MESSAGING") && ep.subkind.contains("KAFKA")) {
            setTo(ep.module, AUTO_COMMIT, Set.of("true"), extra);
        }
        boolean jpaWrite = r.effects().stream().anyMatch(e -> e.signal().equals(Signals.DB_WRITE) && !OTHER_STORE.matcher(e.detail()).find());
        if (jpaWrite) {
            setTo(ep.module, DDL_AUTO, RISKY_DDL, extra);
        }
        Effect feignCall = null;
        for (Effect e : r.effects()) {
            if (feignCall == null && e.signal().equals(Signals.EXTERNAL) && e.detail().startsWith("HTTP client ")
                    && feignClients.contains(e.detail().substring("HTTP client ".length()).split("\\.")[0])) {
                feignCall = e;
            }
        }
        if (feignCall != null) {
            feignTimeouts(ep.module, feignCall, extra);
        }
        Set<String> tables = new java.util.LinkedHashSet<>();
        for (Effect e : r.effects()) {
            if (e.signal().equals(Signals.DB_WRITE)) {
                String repo = e.detail().replaceFirst("^outbox ", "").split("\\.")[0];
                String table = tableByRepository.get(repo);
                if (table != null) {
                    tables.add(table);
                }
            }
        }
        for (String table : tables) {
            for (Change c : changesByTable.getOrDefault(table, List.of())) {
                String name = c.file().substring(c.file().lastIndexOf('/') + 1);
                extra.add(fact(MIGRATION + name + ": " + c.text(), c.file(), c.line(), name));
            }
        }
        if (extra.isEmpty()) {
            return r;
        }
        List<Effect> all = new ArrayList<>(r.effects());
        all.addAll(extra);
        return new EffectAnalyzer.Result(true, all, r.methods(), r.files(), r.truncated(), r.budgetHit(), r.unresolvedCalls(),
                r.beyondReach(), r.entryReturns());
    }

    /** Whether the module's config sets the timeouts Spring Boot 3.4+ gives the HTTP client builders it provides. */
    public boolean builderTimeouts(String module) {
        return config.anyKey(module, "spring.http.client", "timeout");
    }

    private void gracefulShutdown(String module, List<Effect> out) {
        AppConfig.Entry e = config.effective(module, SHUTDOWN);
        if (e != null) {
            if (e.value().equalsIgnoreCase("immediate")) {
                out.add(fact(SHUTDOWN + ": immediate", e.file(), e.line(), SHUTDOWN));
            }
            return;
        }
        String base = config.baseFile(module);
        if (base != null && bootBefore(3, 4)) {
            out.add(fact(SHUTDOWN + " is not set in " + String.join(", ", config.looked(module)) + ": Spring Boot "
                    + ws.springBootVersion + " stops the web server at once, cutting requests in flight", base, 1, SHUTDOWN));
        }
    }

    private void openInView(String module, List<Effect> out) {
        AppConfig.Entry e = config.effective(module, OPEN_IN_VIEW);
        if (e != null) {
            if (e.value().equalsIgnoreCase("true")) {
                out.add(fact(OPEN_IN_VIEW + ": true", e.file(), e.line(), OPEN_IN_VIEW));
            }
            return;
        }
        String base = config.baseFile(module);
        if (base != null) {
            out.add(fact(OPEN_IN_VIEW + " is not set in " + String.join(", ", config.looked(module))
                    + ": it defaults to true, so the request keeps its database connection while it calls out", base, 1, OPEN_IN_VIEW));
        }
    }

    private void setTo(String module, String key, Set<String> bad, List<Effect> out) {
        AppConfig.Entry e = config.effective(module, key);
        if (key.equals(DDL_AUTO) && e == null) {
            e = config.effective(module, "spring.jpa.properties.hibernate.hbm2ddl.auto");
        }
        if (e != null && bad.contains(e.value().toLowerCase(Locale.ROOT))) {
            out.add(fact(e.key() + ": " + e.value(), e.file(), e.line(), key));
        }
    }

    private void feignTimeouts(String module, Effect call, List<Effect> out) {
        if (feignOptionsInCode || config.anyKey(module, FEIGN, "timeout") || config.anyKey(module, "feign.client.config", "timeout")) {
            return;
        }
        String base = config.baseFile(module);
        String detail = "no Feign connect or read timeout under " + FEIGN + (base == null ? "" : " in " + String.join(", ", config.looked(module)))
                + ": Feign waits up to 60 s for each response";
        out.add(base != null ? fact(detail, base, 1, FEIGN) : fact(detail, call.file(), call.line(), FEIGN));
    }

    private boolean bootBefore(int major, int minor) {
        if (ws.springBootVersion == null) {
            return false;
        }
        Matcher m = Pattern.compile("^(\\d+)\\.(\\d+)").matcher(ws.springBootVersion);
        if (!m.find()) {
            return false;
        }
        int ma = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        return ma < major || ma == major && mi < minor;
    }

    private static Effect fact(String detail, String file, int line, String symbol) {
        return new Effect(Signals.CONFIG, detail, file, line, symbol, 0, false, false, null, null, null, null);
    }

    /** The table an entity or document maps to, lower case; null for other types. */
    private static String table(TypeDecl t) {
        boolean entity = false;
        String named = null;
        for (Anno a : t.annos) {
            String n = a.simpleName();
            if (!n.equals("Entity") && !n.equals("Table") && !n.equals("Document")) {
                continue;
            }
            entity = true;
            Matcher m = ANNO_NAME.matcher(a.text());
            // @Entity(name = ...) names the entity, not the table.
            if (!n.equals("Entity") && m.find()) {
                named = m.group(1) != null ? m.group(1) : m.group(2);
            }
        }
        if (!entity) {
            return null;
        }
        return (named != null ? named : t.simpleName.replaceAll("([a-z0-9])([A-Z])", "$1_$2")).toLowerCase(Locale.ROOT);
    }

    /** The destructive statements of the newest migration under each migration root: the one the next deploy applies. */
    private void readMigrations() {
        Map<String, List<Workspace.SourceEntry>> byRoot = new LinkedHashMap<>();
        for (Workspace.SourceEntry e : ws.migrationFiles) {
            if (INCLUDES_ONLY.matcher(e.relPath().substring(e.relPath().lastIndexOf('/') + 1)).matches()) {
                continue;
            }
            Matcher root = MIGRATION_ROOT.matcher(e.relPath());
            byRoot.computeIfAbsent(root.find() ? e.relPath().substring(0, root.end()) : e.relPath().substring(0, Math.max(0, e.relPath().lastIndexOf('/'))),
                    k -> new ArrayList<>()).add(e);
        }
        for (List<Workspace.SourceEntry> files : byRoot.values()) {
            Workspace.SourceEntry newest = files.stream().max(Comparator.comparing(ModuleFacts::versionKey)).orElse(null);
            if (newest == null) {
                continue;
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(newest.path(), StandardCharsets.ISO_8859_1);
            } catch (IOException ignored) {
                continue;
            }
            String table = null;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).strip();
                // Comments, Liquibase's --rollback lines among them, change nothing on deploy.
                if (l.startsWith("--") || l.startsWith("#") || l.startsWith("/*") || l.startsWith("*")) {
                    continue;
                }
                Matcher t = SQL_TABLE.matcher(l);
                if (t.find()) {
                    table = t.group(1);
                }
                Matcher lq = LIQUIBASE_CHANGE.matcher(l);
                String changed = lq.find() ? lq.group(2) : DESTRUCTIVE_SQL.matcher(l).find() ? table : null;
                if (changed != null) {
                    String name = changed.replaceAll("[\"`\\[\\]]", "");
                    name = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    changesByTable.computeIfAbsent(name, k -> new ArrayList<>()).add(new Change(name, newest.relPath(), i + 1, l));
                }
                if (l.contains(";")) {
                    table = null;
                }
            }
        }
    }

    /** Flyway versions compare numerically; other paths compare with their numbers padded, so v_1.10 follows v_1.9. */
    private static String versionKey(Workspace.SourceEntry e) {
        String name = e.relPath().substring(e.relPath().lastIndexOf('/') + 1);
        Matcher m = FLYWAY_VERSION.matcher(name);
        if (!m.find()) {
            Matcher digits = DIGITS.matcher(e.relPath());
            StringBuilder key = new StringBuilder("0|");
            while (digits.find()) {
                digits.appendReplacement(key, String.format("%012d", Long.parseLong(digits.group().length() > 12 ? digits.group().substring(0, 12) : digits.group())));
            }
            digits.appendTail(key);
            return key.toString();
        }
        StringBuilder key = new StringBuilder("1|");
        for (String part : m.group(1).split("[._]")) {
            key.append(String.format("%012d", Long.parseLong(part))).append('.');
        }
        return key.toString();
    }
}
