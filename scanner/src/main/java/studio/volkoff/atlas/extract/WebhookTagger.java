package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.FieldDecl;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Evaluator.Value;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.JavaSourceParser;
import studio.volkoff.atlas.scan.Workspace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The webhook rule of {@code schemas/entrypoint-rules.md}: scores an HTTP record and stores {@code attrs.webhook}. */
final class WebhookTagger {

    private static final Pattern STRONG = Pattern.compile("webhooks?|hooks?|ipn|inbound|incoming");
    private static final Pattern WEAK = Pattern.compile("callbacks?|notify|notifications?|events?|push|deliver(y|ies)?|listener");
    private static final List<String> NAME_WORDS = List.of("webhook", "callback", "inbound", "notification", "hook", "ipn");
    private static final Pattern SIGNATURE_HEADER = Pattern.compile(
            "x-hub-signature(-256)?|stripe-signature|x-slack-signature|x-twilio-signature|x-shopify-hmac-sha256"
                    + "|paypal-transmission-sig|svix-signature|x-github-(event|delivery)|x-atlassian-webhook-[a-z-]+"
                    + "|x-[a-z-]*-(signature|hmac|sig)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VERIFY_CALLS = Set.of("constructEvent", "verifySignature", "validateSignature",
            "verifyRequest", "isValidRequest", "isValidSignature");
    private static final List<String> VERIFY_IMPORTS = List.of("com.stripe.net.Webhook", "com.svix", "org.kohsuke.github.GHEventPayload");
    private static final List<String> VERIFIER_NAME_WORDS = List.of("Webhook", "Signature", "Delivery", "Hook");
    private static final Set<String> MANAGEMENT_LAST = Set.of("secret", "rotate", "config", "settings", "test", "ping",
            "register", "subscription", "subscriptions", "status");

    private final Index index;
    private final Evaluator ev;
    private final List<Pattern> permitAll = new ArrayList<>();
    private final List<String> basePaths = new ArrayList<>();

    WebhookTagger(Index index, Evaluator ev, Workspace ws) {
        this.index = index;
        this.ev = ev;
        for (String v : ws.basePaths.values()) {
            if (!v.isBlank() && !v.equals("/")) {
                basePaths.add(v);
            }
        }
        for (TypeDecl t : index.types()) {
            if (!isSecurityConfig(t)) {
                continue;
            }
            for (MethodDecl m : t.methods) {
                for (Expr e : m.permitAllPatterns()) {
                    for (Value v : ev.eval(e, Ctx.of(t)).elements()) {
                        if (v.v() instanceof String s && !s.isBlank()) {
                            permitAll.add(antPattern(s));
                            String stripped = withoutFirstSegment(s);
                            if (stripped != null) {
                                permitAll.add(antPattern(stripped));
                            }
                        }
                    }
                }
            }
        }
    }

    void tag(EntryPoint ep, TypeDecl controller, List<MethodDecl> methods) {
        List<String> segments = new ArrayList<>();
        for (String s : ep.target.split("/")) {
            if (!s.isEmpty() && !s.startsWith("{") && !s.startsWith("<")) {
                segments.add(s.toLowerCase(Locale.ROOT));
            }
        }
        String[] raw = ep.target.split("/");
        String last = raw.length == 0 ? "" : raw[raw.length - 1].toLowerCase(Locale.ROOT);
        boolean write = ep.subkind.equals("ANY") || ep.subkind.contains("POST") || ep.subkind.contains("PUT")
                || ep.subkind.contains("PATCH") || ep.subkind.contains("DELETE");

        Map<String, Integer> signals = new LinkedHashMap<>();
        if (segments.stream().anyMatch(s -> STRONG.matcher(s).matches())) {
            signals.put("path:strong", 3);
        }
        if (segments.stream().anyMatch(s -> WEAK.matcher(s).matches())) {
            signals.put("path:weak", 1);
        }
        String names = (controller.simpleName + " " + ep.method).toLowerCase(Locale.ROOT);
        boolean webhookName = names.contains("webhook");
        if (NAME_WORDS.stream().anyMatch(names::contains)) {
            signals.put("name", 2);
        }
        if (signatureHeader(controller, methods)) {
            signals.put("sig-header", 3);
        }
        if (verifyCall(controller, methods)) {
            signals.put("verify-call", 3);
        }
        if (rawBody(methods)) {
            signals.put("raw-body", 2);
        }
        if (permitted(ep.target)) {
            signals.put("permit-all", write ? 2 : 1);
        }
        int score = signals.values().stream().mapToInt(Integer::intValue).sum();

        String verdict = null;
        String confidence = null;
        if (segments.contains("callback") && !write && namedParam(methods, "code", "state")) {
            verdict = "OAUTH_CALLBACK";
        } else if ((signals.containsKey("path:strong") || webhookName)
                && (MANAGEMENT_LAST.contains(last) || (!write && !signals.containsKey("sig-header")
                && !signals.containsKey("verify-call") && !signals.containsKey("raw-body")))) {
            verdict = "WEBHOOK_MANAGEMENT";
        } else if (write && score >= 3 && signals.keySet().stream().anyMatch(s -> !s.equals("permit-all"))) {
            verdict = "WEBHOOK";
            confidence = score >= 6 ? "HIGH" : "MEDIUM";
        } else if (write && signals.size() == 1 && signals.containsKey("permit-all")) {
            verdict = "UNAUTHENTICATED_WRITE";
        }
        if (verdict == null) {
            return;
        }
        Map<String, Object> tag = new LinkedHashMap<>();
        tag.put("verdict", verdict);
        if (confidence != null) {
            tag.put("confidence", confidence);
        }
        tag.put("score", score);
        tag.put("signals", new ArrayList<>(signals.keySet()));
        ep.attr("webhook", tag);
    }

    private boolean signatureHeader(TypeDecl controller, List<MethodDecl> methods) {
        for (MethodDecl m : methods) {
            for (MethodDecl.Param p : m.params()) {
                for (Anno a : p.annos()) {
                    if (!a.simpleName().equals("RequestHeader")) {
                        continue;
                    }
                    for (String key : List.of("value", "name")) {
                        Expr e = a.args().get(key);
                        if (e != null && SIGNATURE_HEADER.matcher(ev.eval(e, Ctx.of(controller)).text()).matches()) {
                            return true;
                        }
                    }
                }
            }
        }
        for (FieldDecl f : controller.fields) {
            if (f.init() instanceof Expr.Lit l && l.value() instanceof String s && SIGNATURE_HEADER.matcher(s).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean verifyCall(TypeDecl controller, List<MethodDecl> methods) {
        for (MethodDecl m : methods) {
            if (callsVerifier(m)) {
                return true;
            }
        }
        if (verifies(controller)) {
            return true;
        }
        for (String ref : controller.referencedTypes) {
            if (VERIFIER_NAME_WORDS.stream().noneMatch(ref::contains)) {
                continue;
            }
            Index.Resolved r = index.resolveType(ref, controller, controller.file, null);
            if (r != null && verifies(r.type())) {
                return true;
            }
        }
        return false;
    }

    private static boolean verifies(TypeDecl t) {
        for (MethodDecl m : t.methods) {
            if (callsVerifier(m)) {
                return true;
            }
        }
        SourceFile f = t.file;
        for (String imp : VERIFY_IMPORTS) {
            if (f.importsFrom(imp)) {
                return true;
            }
        }
        return false;
    }

    private static boolean callsVerifier(MethodDecl m) {
        for (String c : m.calls().keySet()) {
            if (VERIFY_CALLS.contains(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean rawBody(List<MethodDecl> methods) {
        for (MethodDecl m : methods) {
            for (MethodDecl.Param p : m.params()) {
                String type = p.type().replace(" ", "");
                boolean body = p.annos().stream().anyMatch(a -> a.simpleName().equals("RequestBody"));
                if (body && (type.equals("byte[]") || JavaSourceParser.simpleTypeName(type).equals("String") && !type.contains("<"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean namedParam(List<MethodDecl> methods, String... names) {
        Set<String> wanted = Set.of(names);
        for (MethodDecl m : methods) {
            for (MethodDecl.Param p : m.params()) {
                if (wanted.contains(p.name())) {
                    return true;
                }
                for (Anno a : p.annos()) {
                    if (a.simpleName().equals("RequestParam")) {
                        for (String key : List.of("value", "name")) {
                            if (a.args().get(key) instanceof Expr.Lit l && wanted.contains(String.valueOf(l.value()))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean permitted(String target) {
        List<String> candidates = new ArrayList<>();
        candidates.add(target);
        for (String base : basePaths) {
            candidates.add(HttpExtractor.joinPaths(base, target));
        }
        for (Pattern p : permitAll) {
            for (String c : candidates) {
                if (p.matcher(c).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSecurityConfig(TypeDecl t) {
        if (t.file.importsFrom("org.springframework.security.config.annotation.web.builders.HttpSecurity")
                || t.referencedTypes.contains("HttpSecurity")) {
            return true;
        }
        for (MethodDecl m : t.methods) {
            for (String type : m.paramTypes()) {
                if (JavaSourceParser.simpleTypeName(type).equals("HttpSecurity")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code /api/github/webhook} → {@code /github/webhook}: a servlet prefix set in code is not in
     * the manifest, so the security pattern is also tried without it. Only when the first segment
     * is literal and a literal segment remains, so {@code /api/**} never widens to {@code /**}.
     */
    static String withoutFirstSegment(String pattern) {
        String[] segs = pattern.replaceAll("^/+", "").split("/");
        if (segs.length < 2 || segs[0].isEmpty() || segs[0].contains("*") || segs[0].contains("{")) {
            return null;
        }
        boolean literalLeft = false;
        for (int i = 1; i < segs.length; i++) {
            literalLeft |= !segs[i].isEmpty() && !segs[i].contains("*") && !segs[i].contains("{") && !segs[i].contains("?");
        }
        return literalLeft ? "/" + String.join("/", java.util.Arrays.copyOfRange(segs, 1, segs.length)) : null;
    }

    /** Ant-style pattern: {@code **} spans segments, {@code *} and {@code ?} stay inside one. */
    static Pattern antPattern(String ant) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < ant.length(); i++) {
            char c = ant.charAt(i);
            if (c == '*' && i + 1 < ant.length() && ant.charAt(i + 1) == '*') {
                re.append(".*");
                i++;
            } else if (c == '*') {
                re.append("[^/]*");
            } else if (c == '?') {
                re.append("[^/]");
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(re.toString());
    }
}
