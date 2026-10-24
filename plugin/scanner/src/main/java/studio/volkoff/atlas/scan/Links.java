package studio.volkoff.atlas.scan;

import studio.volkoff.atlas.effects.Effect;
import studio.volkoff.atlas.effects.EffectAnalyzer;
import studio.volkoff.atlas.effects.Signals;
import studio.volkoff.atlas.extract.EntryPoint;
import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Candidate links between entry points for {@code atlas/links.jsonl}: a send and the consumers of
 * its destination, an event and its listeners, an outbound call and the webhooks of the same
 * provider. Each row says what matched and how; whether the work really flows along it is the
 * tracer's decision.
 */
final class Links {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Pattern EVENT_TYPE = Pattern.compile("\\(([A-Z][\\w.$]*)\\)$");
    private static final Pattern SIGNATURE_HEADER = Pattern.compile("(?i)^(?:x-)?([a-z]+)-(?:signature|hmac|sig|transmission-sig|webhook-[a-z-]+)");
    private static final Map<String, String> HEADER_PROVIDER = Map.of("hub", "github", "svix", "svix");
    private static final Pattern WEBHOOK_SEGMENT = Pattern.compile("^(?:webhooks?|hooks?|callbacks?|ipn)$");
    /** Words that name no provider: shared by many clients, or part of every config key. */
    private static final Set<String> GENERIC = Set.of("payment", "payments", "provider", "providers", "api", "apis", "gateway", "client",
            "clients", "notify", "notification", "notifications", "event", "events", "service", "services", "base", "url", "uri",
            "host", "endpoint", "webhook", "webhooks", "hook", "hooks", "callback", "secret", "key", "token", "http", "https",
            "server", "remote", "external", "internal", "integration", "integrations", "app", "application", "spring", "cloud",
            "config", "timeout", "v1", "v2", "v3", "public", "private", "admin");
    private static final int MAX_INBOUND_PER_TOKEN = 3;

    private final Index index;
    private final AppConfig config;

    Links(Index index, AppConfig config) {
        this.index = index;
        this.config = config;
    }

    List<Map<String, Object>> derive(List<EntryPoint> eps, List<EffectAnalyzer.Result> effects) {
        Map<String, List<EntryPoint>> consumers = new LinkedHashMap<>();
        Map<String, String> consumerStatus = new LinkedHashMap<>();
        Map<String, List<EntryPoint>> listeners = new LinkedHashMap<>();
        for (EntryPoint ep : eps) {
            if (ep.kind.equals("MESSAGING")) {
                for (String t : ep.target.split(",")) {
                    String[] key = destination(t.strip(), ep.module);
                    if (key != null) {
                        consumers.computeIfAbsent(key[0], k -> new ArrayList<>()).add(ep);
                        consumerStatus.put(key[0] + "|" + ep.id, key[1]);
                    }
                }
            } else if (ep.kind.equals("EVENT")) {
                for (String t : ep.target.split(",")) {
                    listeners.computeIfAbsent(simple(t.strip()), k -> new ArrayList<>()).add(ep);
                }
            }
        }
        Map<String, Set<String>> inbound = inboundTokens(eps);
        Map<String, Integer> tokenUse = new LinkedHashMap<>();
        inbound.values().forEach(ts -> ts.forEach(t -> tokenUse.merge(t, 1, Integer::sum)));

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < eps.size(); i++) {
            EntryPoint from = eps.get(i);
            List<Effect> list = effects.get(i).effects();
            for (int e = 0; e < list.size(); e++) {
                Effect fx = list.get(e);
                if (fx.signal().equals(Signals.MESSAGE) && fx.target() != null && fx.target().get("key") != null) {
                    String key = String.valueOf(fx.target().get("key"));
                    for (EntryPoint to : consumers.getOrDefault(key, List.of())) {
                        String status = "STATIC".equals(fx.target().get("status")) && "STATIC".equals(consumerStatus.get(key + "|" + to.id)) ? "STATIC" : "INFERRED";
                        add(rows, seen, "SEND_CONSUME", from, e, fx, to, key, String.valueOf(fx.target().get("basis")), status,
                                List.of("send: " + fx.target().get("via"), "consumer: " + to.target));
                    }
                } else if (fx.signal().equals(Signals.EVENT)) {
                    Matcher m = EVENT_TYPE.matcher(fx.detail());
                    if (!m.find()) {
                        continue;
                    }
                    String type = simple(m.group(1));
                    for (EntryPoint to : listeners.getOrDefault(type, List.of())) {
                        add(rows, seen, "EVENT_LISTEN", from, e, fx, to, type, "EVENT_TYPE", "STATIC", List.of("listener of " + type));
                    }
                    for (String parent : supertypes(type)) {
                        for (EntryPoint to : listeners.getOrDefault(parent, List.of())) {
                            add(rows, seen, "EVENT_LISTEN", from, e, fx, to, type, "EVENT_TYPE", "INFERRED", List.of("listener of " + parent + ", a supertype of " + type));
                        }
                    }
                } else if (fx.signal().equals(Signals.EXTERNAL)) {
                    Map<String, String> out = outboundTokens(fx);
                    for (EntryPoint to : eps) {
                        Set<String> in = inbound.get(to.id);
                        if (in == null || to.id.equals(from.id)) {
                            continue;
                        }
                        for (Map.Entry<String, String> t : out.entrySet()) {
                            if (in.contains(t.getKey()) && tokenUse.getOrDefault(t.getKey(), 0) <= MAX_INBOUND_PER_TOKEN) {
                                add(rows, seen, "OUTBOUND_WEBHOOK", from, e, fx, to, t.getKey(), "PROVIDER_TOKEN", "INFERRED",
                                        List.of("outbound: " + t.getValue(), "inbound: " + inboundWhy(to, t.getKey())));
                                break;
                            }
                        }
                    }
                }
            }
        }
        return rows;
    }

    private void add(List<Map<String, Object>> rows, Set<String> seen, String kind, EntryPoint from, int effect, Effect fx,
                     EntryPoint to, String key, String basis, String status, List<String> via) {
        String id = "LK-" + sha1(kind + "|" + from.id + "|" + fx.file() + ":" + fx.line() + "|" + to.id + "|" + key);
        if (!seen.add(id)) {
            return;
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("entrypointId", from.id);
        f.put("effect", effect);
        f.put("file", fx.file());
        f.put("line", fx.line());
        f.put("symbol", fx.symbol());
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("entrypointId", to.id);
        t.put("file", to.file);
        t.put("line", to.line);
        t.put("symbol", simple(to.cls) + "." + to.method);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", kind);
        row.put("from", f);
        row.put("to", t);
        row.put("key", key);
        row.put("basis", basis);
        row.put("status", status);
        row.put("via", via);
        rows.add(row);
    }

    /** A consumer's destination and whether the config gives it ({@code STATIC}) or only a default ({@code INFERRED}). */
    private String[] destination(String target, String module) {
        Matcher ph = PLACEHOLDER.matcher(target);
        if (!ph.find()) {
            return target.isEmpty() || target.contains("<") ? null : new String[] {target, "STATIC"};
        }
        AppConfig.Entry e = config.effective(module, ph.group(1));
        if (e != null) {
            return new String[] {e.value(), "STATIC"};
        }
        return ph.group(2) != null && !ph.group(2).isEmpty() ? new String[] {ph.group(2), "INFERRED"} : null;
    }

    private List<String> supertypes(String simpleName) {
        List<String> out = new ArrayList<>();
        for (TypeDecl t : index.types()) {
            if (t.simpleName.equals(simpleName)) {
                for (TypeDecl s : index.supertypes(t)) {
                    out.add(s.simpleName);
                }
                for (String s : index.unresolvedSupertypes(t)) {
                    out.add(simple(s));
                }
            }
        }
        return out;
    }

    /** Provider tokens of an outbound call, each with where it came from: the SDK package, or the base-URL key of its client. */
    private Map<String, String> outboundTokens(Effect fx) {
        Map<String, String> out = new LinkedHashMap<>();
        String d = fx.detail();
        if (d.startsWith("SDK ")) {
            String[] pkg = d.substring(4).split(" ")[0].split("\\.");
            if (pkg.length >= 2) {
                token(out, pkg[1], "SDK package " + String.join(".", pkg));
            }
        }
        String cls = fx.symbol().contains(".") ? fx.symbol().substring(0, fx.symbol().lastIndexOf('.')) : fx.symbol();
        for (TypeDecl t : index.types()) {
            if (!t.displayName.equals(cls) || !t.file.relPath().equals(fx.file())) {
                continue;
            }
            for (MethodDecl m : t.methods) {
                if (!m.isConstructor()) {
                    continue;
                }
                for (MethodDecl.Param p : m.params()) {
                    for (Anno a : p.annos()) {
                        Matcher ph = PLACEHOLDER.matcher(a.text());
                        if (a.simpleName().equals("Value") && ph.find() && ph.group(1).toLowerCase(Locale.ROOT).matches(".*(url|uri|host|endpoint).*")) {
                            for (String seg : ph.group(1).split("[.\\-_]")) {
                                token(out, seg, "base-URL key " + ph.group(1));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Provider tokens of every webhook or callback: its signature header, the path segment after webhooks, a verifier import. */
    private Map<String, Set<String>> inboundTokens(List<EntryPoint> eps) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (EntryPoint ep : eps) {
            if (!ep.kind.equals("HTTP") || !(ep.attrs.get("webhook") instanceof Map<?, ?> w)
                    || !("WEBHOOK".equals(w.get("verdict")) || "OAUTH_CALLBACK".equals(w.get("verdict")))) {
                continue;
            }
            Map<String, String> tokens = new LinkedHashMap<>();
            String[] segs = ep.target.split("/");
            for (int i = 0; i + 1 < segs.length; i++) {
                if (WEBHOOK_SEGMENT.matcher(segs[i].toLowerCase(Locale.ROOT)).matches()) {
                    token(tokens, segs[i + 1], "path");
                }
            }
            MethodDecl m = method(ep);
            if (m != null) {
                for (MethodDecl.Param p : m.params()) {
                    for (Anno a : p.annos()) {
                        Matcher h = Pattern.compile("\"([^\"]+)\"").matcher(a.text());
                        if (a.simpleName().equals("RequestHeader") && h.find()) {
                            Matcher sig = SIGNATURE_HEADER.matcher(h.group(1));
                            if (sig.find()) {
                                String word = sig.group(1).toLowerCase(Locale.ROOT);
                                token(tokens, HEADER_PROVIDER.getOrDefault(word, word), "signature header");
                            }
                        }
                    }
                }
            }
            TypeDecl owner = index.byFqn(ep.cls, ep.file);
            if (owner != null) {
                for (String imp : owner.file.imports()) {
                    String[] parts = imp.split("\\.");
                    if (parts.length >= 3 && (imp.contains(".webhook") || imp.contains(".Webhook") || imp.endsWith("GHEventPayload"))) {
                        token(tokens, parts[1], "verifier import");
                    }
                }
            }
            if (!tokens.isEmpty()) {
                out.put(ep.id, new TreeSet<>(tokens.keySet()));
            }
        }
        return out;
    }

    private String inboundWhy(EntryPoint to, String token) {
        return to.target + " (" + token + ")";
    }

    private MethodDecl method(EntryPoint ep) {
        TypeDecl owner = index.byFqn(ep.cls, ep.file);
        if (owner == null) {
            return null;
        }
        for (TypeDecl t : index.linearize(owner)) {
            for (MethodDecl m : t.methods) {
                if (m.name().equals(ep.method)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void token(Map<String, String> out, String raw, String why) {
        String t = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (t.length() >= 4 && !GENERIC.contains(t) && !t.matches("\\d+")) {
            out.putIfAbsent(t, why);
        }
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8))).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
