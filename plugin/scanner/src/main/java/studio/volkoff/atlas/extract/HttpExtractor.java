package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.Status;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations.Arg;
import studio.volkoff.atlas.resolve.Annotations.Effective;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Evaluator.Value;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** HTTP records: controllers, server-side exchange interfaces, orphans, generated interfaces and functional routes. */
final class HttpExtractor extends ExtractorBase {

    private static final Set<String> FRAMEWORK_INTERFACES = Set.of("ErrorController", "Serializable", "Cloneable",
            "Comparable", "InitializingBean", "DisposableBean", "AutoCloseable", "Closeable", "SmartLifecycle", "Lifecycle",
            "ApplicationRunner", "CommandLineRunner", "ApplicationListener", "SmartApplicationListener", "WebMvcConfigurer",
            "HandlerInterceptor", "Ordered", "SchedulingConfigurer", "Runnable", "Callable", "Supplier", "Function",
            "Consumer", "MessageListener");
    private static final Pattern GENERATED_INTERFACE_NAME =
            Pattern.compile(".*(Api|Apis|Controller|Resource|Endpoint|Endpoints|Operations|Service)$");
    private static final Set<String> ROUTE_CALLS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "route", "nest");

    private final WebhookTagger webhooks;
    private final Set<TypeDecl> controllerSupertypes = new HashSet<>();

    HttpExtractor(Context c, WebhookTagger webhooks) {
        super(c);
        this.webhooks = webhooks;
    }

    void extract() {
        List<TypeDecl> controllers = new ArrayList<>();
        for (TypeDecl t : index.types()) {
            if (isController(t)) {
                controllers.add(t);
                controllerSupertypes.addAll(index.allSupertypes(t));
            }
        }
        Set<TypeDecl> clientContracts = new HashSet<>();
        for (TypeDecl t : index.types()) {
            if (isClient(t)) {
                clientContracts.add(t);
                clientContracts.addAll(index.allSupertypes(t));
            }
        }
        for (TypeDecl c : controllers) {
            controller(c);
        }
        for (TypeDecl t : index.types()) {
            boolean abstractType = t.kind == TypeDecl.Kind.INTERFACE || (t.kind == TypeDecl.Kind.CLASS && t.isAbstract);
            if (abstractType && !controllerSupertypes.contains(t) && !clientContracts.contains(t)) {
                orphan(t);
            }
            if (t.kind != TypeDecl.Kind.ANNOTATION) {
                routers(t);
            }
        }
    }

    private boolean isController(TypeDecl t) {
        if (!isConcrete(t)) {
            return false;
        }
        if (an.has(t.annos, Ctx.of(t), "Controller")) {
            return true;
        }
        for (TypeDecl s : index.allSupertypes(t)) {
            if (an.has(s.annos, Ctx.of(s), "Controller")) {
                return true;
            }
        }
        return false;
    }

    /** Feign interfaces, and exchange interfaces that no controller implements. */
    private boolean isClient(TypeDecl t) {
        if (t.kind != TypeDecl.Kind.INTERFACE) {
            return false;
        }
        Ctx ctx = Ctx.of(t);
        return an.has(t.annos, ctx, "FeignClient")
                || (an.has(t.annos, ctx, "HttpExchange") && !controllerSupertypes.contains(t));
    }

    /** A method's mapping: the first {@code @RequestMapping}-family annotation, else the {@code @HttpExchange} family. */
    private record Mapping(Effective effective, boolean exchange, boolean ambiguous) {
    }

    private Mapping mapping(MethodDecl m, TypeDecl owner) {
        Ctx ctx = Ctx.of(owner);
        List<Effective> request = an.find(m.annos(), ctx, "RequestMapping");
        if (!request.isEmpty()) {
            return new Mapping(request.get(0), false, request.size() > 1);
        }
        List<Effective> exchange = an.find(m.annos(), ctx, "HttpExchange");
        return exchange.isEmpty() ? null : new Mapping(exchange.get(0), true, exchange.size() > 1);
    }

    private void controller(TypeDecl controller) {
        List<TypeDecl> order = index.linearize(controller);
        Map<String, List<Decl>> bySignature = methodsBySignature(order);
        if (isPortlet(order)) {
            portlet(controller, order, bySignature);
            return;
        }
        for (List<Decl> decls : bySignature.values()) {
            Decl mostDerived = mostDerived(decls);
            for (Decl d : decls) {
                Mapping mapping = mapping(d.method(), d.owner());
                if (mapping != null) {
                    emit(controller, mostDerived, d, mapping, prefix(order, mapping.exchange()), false);
                    break;
                }
            }
        }
        missingInterfaces(controller, bySignature);
    }

    /** Spring Portlet MVC request modes: a class-level {@code @RequestMapping} with one of these is a portlet, not a URL. */
    private static final Set<String> PORTLET_MODES = Set.of("VIEW", "EDIT", "HELP", "CONFIG", "ABOUT", "PRINT", "PREVIEW",
            "EDIT_DEFAULTS", "EDIT_GUEST");
    private static final Map<String, String> PORTLET_PHASES = Map.of("RenderMapping", "RENDER", "ActionMapping", "ACTION",
            "ResourceMapping", "RESOURCE", "EventMapping", "EVENT");

    /**
     * A portlet controller has a portlet-mode class mapping or portlet phase handlers. Importing the
     * portlet API alone is not enough: an HTTP controller may use it in a helper.
     */
    private boolean isPortlet(List<TypeDecl> order) {
        String mode = portletMode(order);
        if (mode != null && PORTLET_MODES.contains(mode)) {
            return true;
        }
        for (TypeDecl t : order) {
            for (MethodDecl m : t.methods) {
                for (Anno a : m.annos()) {
                    if (PORTLET_PHASES.containsKey(a.simpleName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String portletMode(List<TypeDecl> order) {
        Prefix p = prefix(order, false);
        if (p == null) {
            return null;
        }
        List<Value> values = paths(p.effective());
        return values.isEmpty() ? null : values.get(0).text();
    }

    /**
     * One record per handler of a portlet controller: the phase is the subkind, and the target is
     * the portlet mode plus the handler's value or params, such as {@code VIEW:save}.
     */
    private void portlet(TypeDecl controller, List<TypeDecl> order, Map<String, List<Decl>> bySignature) {
        String mode = portletMode(order);
        if (mode == null || mode.isEmpty()) {
            mode = "VIEW";
        }
        for (List<Decl> decls : bySignature.values()) {
            Decl body = mostDerived(decls);
            for (Decl d : decls) {
                Ctx ctx = Ctx.of(d.owner());
                String phase = null;
                Effective e = null;
                for (Effective x : an.expandAll(d.method().annos(), ctx)) {
                    if (PORTLET_PHASES.containsKey(x.base())) {
                        phase = PORTLET_PHASES.get(x.base());
                        e = x;
                        break;
                    }
                    if (x.base().equals("RequestMapping")) {
                        phase = "REQUEST";
                        e = x;
                    }
                }
                if (e == null) {
                    continue;
                }
                String value = text(e, "value", "name");
                String params = text(e, "params");
                String target = mode + (value == null || value.isEmpty() ? "" : ":" + value)
                        + (params == null || params.isEmpty() ? "" : " [" + params + "]");
                EntryPoint ep = record("PORTLET", phase, target, controller, body, "@" + e.useSite().name(), Status.STATIC);
                applyEffective(ep, e);
                if (d.owner() != controller) {
                    ep.via("inherited:" + d.owner().simpleName).atLeast(Status.INFERRED);
                }
                break;
            }
        }
    }

    private void orphan(TypeDecl t) {
        List<TypeDecl> order = index.linearize(t);
        for (MethodDecl m : t.methods) {
            if (m.isConstructor() || m.isStatic()) {
                continue;
            }
            Mapping mapping = mapping(m, t);
            if (mapping != null) {
                Decl d = new Decl(t, m);
                emit(t, d, d, mapping, prefix(order, mapping.exchange()), true);
            }
        }
    }

    private record Prefix(Decl decl, Effective effective) {
    }

    private Prefix prefix(List<TypeDecl> order, boolean exchange) {
        for (TypeDecl t : order) {
            Effective e = an.first(t.annos, Ctx.of(t), "RequestMapping");
            if (e == null && exchange) {
                e = an.first(t.annos, Ctx.of(t), "HttpExchange");
            }
            if (e != null) {
                return new Prefix(new Decl(t, null), e);
            }
        }
        return null;
    }

    /**
     * Groups the hierarchy's methods into override chains. Name and arity identify a chain, which
     * also matches generic overrides ({@code save(T)} / {@code save(Order)}); erased parameter types
     * are added only where some type declares overloads of the same name and arity.
     */
    private Map<String, List<Decl>> methodsBySignature(List<TypeDecl> order) {
        Map<String, List<Decl>> byNameArity = new LinkedHashMap<>();
        for (TypeDecl t : order) {
            for (MethodDecl m : t.methods) {
                if (!m.isConstructor() && !m.isStatic()) {
                    byNameArity.computeIfAbsent(m.signature(), k -> new ArrayList<>()).add(new Decl(t, m));
                }
            }
        }
        Map<String, List<Decl>> chains = new LinkedHashMap<>();
        for (Map.Entry<String, List<Decl>> e : byNameArity.entrySet()) {
            Set<TypeDecl> owners = new HashSet<>();
            boolean overloaded = false;
            for (Decl d : e.getValue()) {
                overloaded |= !owners.add(d.owner());
            }
            for (Decl d : e.getValue()) {
                String key = overloaded ? e.getKey() + erasedParams(d.method()) : e.getKey();
                chains.computeIfAbsent(key, k -> new ArrayList<>()).add(d);
            }
        }
        return chains;
    }

    private static String erasedParams(MethodDecl m) {
        List<String> types = new ArrayList<>();
        for (String p : m.paramTypes()) {
            types.add(JavaSourceParser.simpleTypeName(p));
        }
        return "(" + String.join(",", types) + ")";
    }

    private static Decl mostDerived(List<Decl> decls) {
        for (Decl d : decls) {
            if (d.owner().kind != TypeDecl.Kind.INTERFACE) {
                return d;
            }
        }
        return decls.get(0);
    }

    private void emit(TypeDecl owner, Decl body, Decl mapped, Mapping mapping, Prefix prefix, boolean orphan) {
        Effective m = mapping.effective();
        Effective p = prefix == null ? null : prefix.effective();
        List<Value> classPaths = p == null ? List.of(Value.of("", "")) : paths(p);
        List<Value> methodPaths = paths(m);
        Set<String> methods = enumNames(m.attr("method"));
        if (methods.isEmpty() && p != null) {
            methods = enumNames(p.attr("method"));
        }
        String subkind = methods.isEmpty() ? "ANY" : String.join(",", methods);
        for (Value cp : classPaths) {
            for (Value mp : methodPaths) {
                EntryPoint ep = record("HTTP", subkind, joinPaths(cp.text(), mp.text()), owner, body,
                        "@" + m.useSite().name(), cp.status().worst(mp.status()));
                cp.via().forEach(ep::via);
                mp.via().forEach(ep::via);
                applyEffective(ep, m);
                if (p != null) {
                    applyEffective(ep, p);
                }
                if (mapping.exchange()) {
                    ep.via("http-exchange");
                }
                if (mapping.ambiguous()) {
                    ep.via("ambiguous-mapping");
                }
                if (mapped.owner() != owner) {
                    ep.via("inherited:" + mapped.owner().simpleName).atLeast(Status.INFERRED);
                }
                if (prefix != null && prefix.decl().owner() != owner) {
                    ep.via("inherited:" + prefix.decl().owner().simpleName).atLeast(Status.INFERRED);
                }
                if (orphan) {
                    ep.via("orphan").atLeast(Status.INFERRED);
                }
                for (String a : List.of("consumes", "produces", "params", "headers")) {
                    Arg arg = m.attr(a);
                    if (arg != null) {
                        ep.attr(a, eval(arg).text());
                    }
                }
                List<String> conditions = new ArrayList<>();
                for (Anno a : owner.annos) {
                    if (a.simpleName().equals("Profile") || a.simpleName().startsWith("Conditional")) {
                        conditions.add(a.text());
                    }
                }
                if (!conditions.isEmpty()) {
                    ep.attr("conditions", conditions);
                }
                webhooks.tag(ep, owner, List.of(body.method(), mapped.method()));
            }
        }
    }

    private void missingInterfaces(TypeDecl controller, Map<String, List<Decl>> bySignature) {
        List<String> missing = new ArrayList<>();
        boolean generatorDeclared = ws.declares("openapi-generator") || ws.declares("swagger-codegen");
        for (int i = 0; i < controller.superTypes.size(); i++) {
            if (i == 0 && controller.hasSuperclass) {
                continue;
            }
            String raw = Index.stripGenerics(controller.superTypes.get(i));
            if (index.resolveType(raw, controller.enclosing, controller.file, controller) != null) {
                continue;
            }
            String simple = JavaSourceParser.simpleTypeName(raw);
            if (FRAMEWORK_INTERFACES.contains(simple) || simple.endsWith("Aware")) {
                continue;
            }
            if (generatorDeclared || GENERATED_INTERFACE_NAME.matcher(simple).matches()) {
                missing.add(simple);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        for (MethodDecl m : controller.methods) {
            if (m.isConstructor() || m.isStatic() || !hasAnno(m.annos(), "Override")) {
                continue;
            }
            List<Decl> decls = bySignature.containsKey(m.signature()) ? bySignature.get(m.signature())
                    : bySignature.get(m.signature() + erasedParams(m));
            if (decls == null || decls.size() > 1 || mapping(m, controller) != null) {
                continue;
            }
            EntryPoint ep = record("HTTP", "ANY", "<generated:" + String.join("|", missing) + ">", controller,
                    new Decl(controller, m), "@Override", Status.UNKNOWN);
            missing.forEach(n -> ep.via("missing-interface:" + n));
        }
    }

    private void routers(TypeDecl t) {
        Ctx ctx = Ctx.of(t);
        for (MethodDecl m : t.methods) {
            if (m.returnType() == null || !JavaSourceParser.simpleTypeName(m.returnType()).equals("RouterFunction")
                    || !an.has(m.annos(), ctx, "Bean")) {
                continue;
            }
            Decl d = new Decl(t, m);
            if (m.routes().isEmpty()) {
                int calls = 0;
                for (Map.Entry<String, Integer> c : m.calls().entrySet()) {
                    if (ROUTE_CALLS.contains(c.getKey())) {
                        calls += c.getValue();
                    }
                }
                record("ROUTER", "ROUTER_FUNCTION", m.name(), t, d, "@Bean", Status.INFERRED).attr("routes", calls);
                continue;
            }
            for (MethodDecl.Route r : m.routes()) {
                String target = "";
                Status status = Status.INFERRED;
                for (Expr prefix : r.prefixes()) {
                    Value v = ev.eval(prefix, ctx);
                    target = joinPaths(target, v.text());
                    status = status.worst(v.status());
                }
                Value path = ev.eval(r.path(), ctx);
                EntryPoint ep = record("HTTP", r.verb(), joinPaths(target, path.text()), t, d, r.verb(),
                        status.worst(path.status()));
                ep.via("router-function");
                ep.attr("handler", r.handler());
                webhooks.tag(ep, t, List.of(m));
            }
        }
    }

    private List<Value> paths(Effective e) {
        Arg a = e.attr("path", "value", "url");
        if (a == null) {
            return List.of(Value.of("", ""));
        }
        List<Value> items = eval(a).elements();
        return items.isEmpty() ? List.of(Value.of("", "")) : items;
    }

    static String joinPaths(String a, String b) {
        String left = a == null ? "" : a.trim();
        String right = b == null ? "" : b.trim();
        String joined;
        if (left.isEmpty()) {
            joined = right;
        } else if (right.isEmpty()) {
            joined = left;
        } else {
            joined = stripTrailingSlashes(left) + "/" + stripLeadingSlashes(right);
        }
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String stripLeadingSlashes(String s) {
        int start = 0;
        while (start < s.length() && s.charAt(start) == '/') {
            start++;
        }
        return s.substring(start);
    }
}
