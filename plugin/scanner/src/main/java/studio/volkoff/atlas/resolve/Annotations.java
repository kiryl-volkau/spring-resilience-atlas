package studio.volkoff.atlas.resolve;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Expands annotation uses to the framework annotations they stand for, following composed
 * annotations defined in the source set, {@code @AliasFor}, Spring's attribute conventions and
 * the built-in shortcuts ({@code @GetMapping} → {@code @RequestMapping(method = GET)}).
 */
public final class Annotations {

    /** An attribute expression together with the scope it must be evaluated in. */
    public record Arg(Expr expr, Ctx ctx) {
    }

    /**
     * A framework annotation in effect at a use site.
     *
     * @param base     the framework annotation's simple name, such as {@code RequestMapping}
     * @param useSite  the annotation as written at the declaration
     * @param via      {@code meta:@Name} tags for each composed annotation passed through
     * @param convention true if an attribute was mapped by name rather than by {@code @AliasFor}
     */
    public record Effective(String base, Map<String, Arg> attrs, Anno useSite, List<String> via,
                            boolean convention, boolean wildcard) {

        public Arg attr(String... names) {
            for (String n : names) {
                Arg a = attrs.get(n);
                if (a != null) {
                    return a;
                }
            }
            return null;
        }
    }

    private static final Map<String, String> SHORTCUT_METHOD = Map.of(
            "GetMapping", "GET", "PostMapping", "POST", "PutMapping", "PUT",
            "DeleteMapping", "DELETE", "PatchMapping", "PATCH");
    private static final Map<String, String> EXCHANGE_METHOD = Map.of(
            "GetExchange", "GET", "PostExchange", "POST", "PutExchange", "PUT",
            "DeleteExchange", "DELETE", "PatchExchange", "PATCH");

    private static final Set<String> META_NOISE =
            Set.of("Retention", "Target", "Documented", "Inherited", "Repeatable", "AliasFor");

    /** Attributes of framework annotations that a same-named composed attribute may override. */
    private static final Map<String, Set<String>> KNOWN_ATTRIBUTES = Map.of(
            "RequestMapping", Set.of("path", "method", "params", "headers", "consumes", "produces", "name"),
            "Scheduled", Set.of("cron", "zone", "fixedDelay", "fixedDelayString", "fixedRate", "fixedRateString",
                    "initialDelay", "initialDelayString", "timeUnit", "scheduler"),
            "EventListener", Set.of("classes", "condition", "id"),
            "TransactionalEventListener", Set.of("phase", "fallbackExecution", "classes", "condition", "id"),
            "KafkaListener", Set.of("topics", "topicPattern", "id", "groupId", "containerFactory", "concurrency"),
            "SqsListener", Set.of("queueNames", "factory", "id", "maxConcurrentMessages"),
            "RabbitListener", Set.of("queues", "bindings", "id", "containerFactory", "concurrency"),
            "JmsListener", Set.of("destination", "containerFactory", "concurrency", "subscription", "id"));

    private final Index index;

    public Annotations(Index index) {
        this.index = index;
    }

    public List<Effective> expand(Anno use, Ctx ctx) {
        Map<String, Arg> args = new LinkedHashMap<>();
        use.args().forEach((k, v) -> args.put(k, new Arg(v, ctx)));
        return expand(use, args, ctx, new HashSet<>());
    }

    public List<Effective> expandAll(List<Anno> annos, Ctx ctx) {
        List<Effective> out = new ArrayList<>();
        for (Anno a : annos) {
            out.addAll(expand(a, ctx));
        }
        return out;
    }

    /** Effective annotations with the given base on the given annotations. */
    public List<Effective> find(List<Anno> annos, Ctx ctx, String base) {
        List<Effective> out = new ArrayList<>();
        for (Effective e : expandAll(annos, ctx)) {
            if (e.base().equals(base)) {
                out.add(e);
            }
        }
        return out;
    }

    public Effective first(List<Anno> annos, Ctx ctx, String base) {
        List<Effective> all = find(annos, ctx, base);
        return all.isEmpty() ? null : all.get(0);
    }

    public boolean has(List<Anno> annos, Ctx ctx, String base) {
        return first(annos, ctx, base) != null;
    }

    public static String builtinBase(String simpleName) {
        if (SHORTCUT_METHOD.containsKey(simpleName)) {
            return "RequestMapping";
        }
        if (EXCHANGE_METHOD.containsKey(simpleName)) {
            return "HttpExchange";
        }
        return simpleName.equals("RestController") ? "Controller" : simpleName;
    }

    private List<Effective> expand(Anno use, Map<String, Arg> args, Ctx nameCtx, Set<TypeDecl> visiting) {
        Index.Resolved r = index.resolveType(use.name(), nameCtx.type(), nameCtx.file(), null);
        if (r == null || r.type().kind != TypeDecl.Kind.ANNOTATION) {
            return List.of(builtin(use, args));
        }
        TypeDecl custom = r.type();
        if (!visiting.add(custom)) {
            return List.of();
        }
        try {
            Ctx customCtx = Ctx.of(custom);
            Map<String, Arg> members = memberValues(custom, args, customCtx);
            List<Effective> out = new ArrayList<>();
            for (Anno meta : custom.annos) {
                if (META_NOISE.contains(meta.simpleName())) {
                    continue;
                }
                Map<String, Arg> metaArgs = new LinkedHashMap<>();
                meta.args().forEach((k, v) -> metaArgs.put(k, new Arg(v, customCtx)));
                boolean convention = false;
                String metaBase = builtinBase(meta.simpleName());
                for (MethodDecl member : custom.methods) {
                    Arg value = members.get(member.name());
                    if (value == null) {
                        continue;
                    }
                    String target = aliasTarget(member, meta.simpleName(), metaBase);
                    if (target == null && !member.name().equals("value") && !hasAliasFor(member)
                            && KNOWN_ATTRIBUTES.getOrDefault(metaBase, Set.of()).contains(member.name())) {
                        target = member.name();
                        convention = true;
                    }
                    if (target != null) {
                        metaArgs.put(target, value);
                    }
                }
                for (Effective e : expand(meta, metaArgs, customCtx, visiting)) {
                    List<String> via = new ArrayList<>();
                    via.add("meta:@" + custom.simpleName);
                    via.addAll(e.via());
                    out.add(new Effective(e.base(), e.attrs(), use, via, e.convention() || convention,
                            e.wildcard() || r.viaWildcard()));
                }
            }
            return out;
        } finally {
            visiting.remove(custom);
        }
    }

    private Effective builtin(Anno use, Map<String, Arg> args) {
        String simple = use.simpleName();
        Map<String, Arg> attrs = new LinkedHashMap<>(args);
        String method = SHORTCUT_METHOD.containsKey(simple) ? SHORTCUT_METHOD.get(simple) : EXCHANGE_METHOD.get(simple);
        if (method != null && !attrs.containsKey("method")) {
            attrs.put("method", new Arg(new Expr.Ref("RequestMethod." + method), null));
        }
        return new Effective(builtinBase(simple), attrs, use, List.of(), false, false);
    }

    /**
     * Values of a composed annotation's members at a use site: explicit values, values copied
     * across intra-annotation aliases ({@code value} ↔ {@code path}), then declared defaults.
     */
    private static Map<String, Arg> memberValues(TypeDecl custom, Map<String, Arg> useArgs, Ctx customCtx) {
        Map<String, Arg> values = new LinkedHashMap<>();
        for (MethodDecl m : custom.methods) {
            Arg a = useArgs.get(m.name());
            if (a != null) {
                values.put(m.name(), a);
            }
        }
        for (MethodDecl m : custom.methods) {
            Anno alias = aliasFor(m);
            if (alias == null || alias.args().containsKey("annotation")) {
                continue;
            }
            String other = stringArg(alias, "attribute", "value");
            if (other == null) {
                continue;
            }
            if (values.containsKey(m.name()) && !values.containsKey(other)) {
                values.put(other, values.get(m.name()));
            } else if (values.containsKey(other) && !values.containsKey(m.name())) {
                values.put(m.name(), values.get(other));
            }
        }
        for (MethodDecl m : custom.methods) {
            if (!values.containsKey(m.name()) && m.defaultValue() != null && !isEmptyArray(m.defaultValue())) {
                values.put(m.name(), new Arg(m.defaultValue(), customCtx));
            }
        }
        return values;
    }

    private static boolean isEmptyArray(Expr e) {
        return e instanceof Expr.Arr a && a.elements().isEmpty()
                || e instanceof Expr.Lit l && "".equals(l.value());
    }

    private static String aliasTarget(MethodDecl member, String metaSimple, String metaBase) {
        Anno alias = aliasFor(member);
        if (alias == null) {
            return null;
        }
        Expr annotation = alias.args().get("annotation");
        if (!(annotation instanceof Expr.ClassLit c)) {
            return null;
        }
        String target = JavaSourceParser.simpleTypeName(c.type());
        if (!target.equals(metaSimple) && !target.equals(metaBase)) {
            return null;
        }
        String attribute = stringArg(alias, "attribute", "value");
        return attribute != null ? attribute : member.name();
    }

    private static boolean hasAliasFor(MethodDecl m) {
        return aliasFor(m) != null;
    }

    private static Anno aliasFor(MethodDecl m) {
        for (Anno a : m.annos()) {
            if (a.simpleName().equals("AliasFor")) {
                return a;
            }
        }
        return null;
    }

    private static String stringArg(Anno a, String... names) {
        for (String n : names) {
            if (a.args().get(n) instanceof Expr.Lit l && l.value() instanceof String s) {
                return s;
            }
        }
        return null;
    }
}
