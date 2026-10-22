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
import java.util.List;
import java.util.Set;

/** SOAP, WEBSOCKET, GRAPHQL, GRPC and ACTUATOR records. */
final class ProtocolExtractor extends ExtractorBase {

    private static final Set<String> WS_HANDLERS = Set.of("WebSocketHandler", "TextWebSocketHandler",
            "BinaryWebSocketHandler", "AbstractWebSocketHandler");

    ProtocolExtractor(Context c) {
        super(c);
    }

    void extract(TypeDecl t) {
        soap(t);
        websocket(t);
        graphql(t);
        grpc(t);
        actuator(t);
    }

    private void soap(TypeDecl t) {
        if (t.kind != TypeDecl.Kind.CLASS) {
            return;
        }
        Ctx ctx = Ctx.of(t);
        Effective webService = t.isAbstract ? null : an.first(t.annos, ctx, "WebService");
        if (webService != null) {
            Arg nameArg = webService.attr("serviceName", "name");
            Value service = nameArg == null ? Value.of(t.simpleName, t.simpleName) : eval(nameArg);
            List<Decl> operations = new ArrayList<>();
            TypeDecl sei = null;
            Arg endpointInterface = webService.attr("endpointInterface");
            if (endpointInterface != null) {
                Value v = eval(endpointInterface);
                if (v.v() instanceof String name) {
                    sei = index.byFqn(name);
                    if (sei == null) {
                        Index.Resolved r = index.resolveType(name, t, t.file, null);
                        sei = r == null ? null : r.type();
                    }
                }
            }
            if (sei != null && sei.kind == TypeDecl.Kind.INTERFACE) {
                for (MethodDecl m : sei.methods) {
                    if (!m.isStatic()) {
                        Decl impl = find(t, m.name());
                        operations.add(impl != null ? impl : new Decl(sei, m));
                    }
                }
            } else {
                for (MethodDecl m : t.methods) {
                    if (m.isPublic() && !m.isStatic() && !m.isConstructor() && !excludedWebMethod(m)) {
                        operations.add(new Decl(t, m));
                    }
                }
            }
            for (Decl op : operations) {
                EntryPoint ep = record("SOAP", "JAX_WS", service.text(), t, op, "@" + webService.useSite().name(), service.status());
                applyEffective(ep, webService);
            }
        }
        if (an.has(t.annos, ctx, "Endpoint")) {
            for (MethodDecl m : t.methods) {
                for (Effective e : an.expandAll(m.annos(), ctx)) {
                    Value target;
                    switch (e.base()) {
                        case "PayloadRoot" -> {
                            Value ns = e.attr("namespace") == null ? Value.of("", "") : eval(e.attr("namespace"));
                            Value local = e.attr("localPart") == null ? Value.unknown("localPart") : eval(e.attr("localPart"));
                            target = new Value(null, "{" + ns.text() + "}" + local.text(), ns.status().worst(local.status()), Set.of());
                        }
                        case "SoapAction", "Action" -> {
                            Value action = e.attr("value") == null ? Value.unknown("value") : eval(e.attr("value"));
                            target = new Value(null, "action:" + action.text(), action.status(), Set.of());
                        }
                        default -> {
                            continue;
                        }
                    }
                    EntryPoint ep = record("SOAP", "SPRING_WS", target.text(), t, new Decl(t, m), "@" + e.useSite().name(),
                            target.status());
                    applyEffective(ep, e);
                }
            }
        }
    }

    private static boolean excludedWebMethod(MethodDecl m) {
        for (Anno a : m.annos()) {
            if (a.simpleName().equals("WebMethod") && a.args().get("exclude") instanceof Expr.Lit l
                    && Boolean.TRUE.equals(l.value())) {
                return true;
            }
        }
        return false;
    }

    private void websocket(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        Ctx ctx = Ctx.of(t);
        if (hierarchyHas(t, "Controller")) {
            Effective classLevel = an.first(t.annos, ctx, "MessageMapping");
            List<Value> prefixes = classLevel == null ? List.of(Value.of("", "")) : destinations(classLevel);
            boolean rsocket = ws.declares("rsocket");
            for (MethodDecl m : t.methods) {
                for (Effective e : an.expandAll(m.annos(), ctx)) {
                    String subkind = switch (e.base()) {
                        case "MessageMapping" -> rsocket ? "RSOCKET" : "STOMP";
                        case "SubscribeMapping" -> "STOMP";
                        case "ConnectMapping" -> "RSOCKET_CONNECT";
                        default -> null;
                    };
                    if (subkind == null) {
                        continue;
                    }
                    for (Value prefix : prefixes) {
                        for (Value destination : destinations(e)) {
                            String target;
                            Status status = prefix.status().worst(destination.status());
                            if (prefix.text().isEmpty() && destination.text().isEmpty()) {
                                target = "<unspecified>";
                                status = Status.UNKNOWN;
                            } else {
                                target = HttpExtractor.joinPaths(prefix.text(), destination.text());
                            }
                            applyEffective(record("WEBSOCKET", subkind, target, t, new Decl(t, m), "@" + e.useSite().name(),
                                    status), e);
                        }
                    }
                }
            }
        }
        SuperMatch handler = findSupertype(t, WS_HANDLERS);
        if (handler != null) {
            String method = "*";
            Decl body = null;
            for (String name : List.of("handleTextMessage", "handleBinaryMessage", "handleMessage")) {
                body = find(t, name);
                if (body != null) {
                    method = name;
                    break;
                }
            }
            inheritedFrom(record("WEBSOCKET", "HANDLER", t.simpleName, t, body, handler.simple(), Status.STATIC, method),
                    handler);
        }
    }

    private List<Value> destinations(Effective e) {
        Arg a = e.attr("value");
        if (a == null) {
            return List.of(Value.of("", ""));
        }
        List<Value> items = eval(a).elements();
        return items.isEmpty() ? List.of(Value.of("", "")) : items;
    }

    private void graphql(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        Ctx ctx = Ctx.of(t);
        boolean controller = hierarchyHas(t, "Controller");
        boolean dgs = an.has(t.annos, ctx, "DgsComponent");
        if (!controller && !dgs) {
            return;
        }
        Effective classSchema = an.first(t.annos, ctx, "SchemaMapping");
        String classType = classSchema == null ? null : text(classSchema, "typeName");
        for (MethodDecl m : t.methods) {
            for (Effective e : an.expandAll(m.annos(), ctx)) {
                String subkind;
                String type;
                String field;
                if (controller && (e.base().equals("QueryMapping") || e.base().equals("MutationMapping")
                        || e.base().equals("SubscriptionMapping"))) {
                    subkind = e.base().replace("Mapping", "").toUpperCase();
                    type = e.base().replace("Mapping", "");
                    field = orDefault(text(e, "name", "value"), m.name());
                } else if (controller && (e.base().equals("SchemaMapping") || e.base().equals("BatchMapping"))) {
                    subkind = "FIELD";
                    type = orDefault(text(e, "typeName"), classType);
                    field = orDefault(text(e, "field", "value"), m.name());
                } else if (dgs && (e.base().equals("DgsQuery") || e.base().equals("DgsMutation")
                        || e.base().equals("DgsSubscription"))) {
                    String kind = e.base().substring("Dgs".length());
                    subkind = kind.toUpperCase();
                    type = kind;
                    field = orDefault(text(e, "field"), m.name());
                } else if (dgs && e.base().equals("DgsData")) {
                    subkind = "FIELD";
                    type = text(e, "parentType");
                    field = orDefault(text(e, "field"), m.name());
                } else {
                    continue;
                }
                Status status = type == null ? Status.UNKNOWN : Status.STATIC;
                String target = (type == null ? "<typeName>" : type) + "." + field;
                if (target.contains("<") && !target.startsWith("<typeName>")) {
                    status = Status.UNKNOWN;
                }
                applyEffective(record("GRAPHQL", subkind, target, t, new Decl(t, m), "@" + e.useSite().name(), status), e);
            }
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private void grpc(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        boolean annotated = an.has(t.annos, Ctx.of(t), "GrpcService");
        String base = null;
        for (TypeDecl x : index.linearize(t)) {
            for (String s : x.superTypes) {
                String simple = JavaSourceParser.simpleTypeName(s);
                if (simple.endsWith("ImplBase")) {
                    base = simple;
                    break;
                }
            }
            if (base != null) {
                break;
            }
        }
        if (!annotated && base == null) {
            return;
        }
        String service = base != null && base.length() > "ImplBase".length()
                ? base.substring(0, base.length() - "ImplBase".length()) : t.simpleName;
        for (MethodDecl m : t.methods) {
            if (!m.isPublic() || m.isStatic() || m.isConstructor()) {
                continue;
            }
            boolean rpc = m.params().stream().anyMatch(p -> JavaSourceParser.simpleTypeName(p.type()).equals("StreamObserver"));
            if (rpc) {
                record("GRPC", "SERVICE", service + "/" + m.name(), t, new Decl(t, m), annotated ? "@GrpcService" : base,
                        annotated ? Status.STATIC : Status.INFERRED);
            }
        }
    }

    private void actuator(TypeDecl t) {
        if (!isConcrete(t)) {
            return;
        }
        Ctx ctx = Ctx.of(t);
        for (Effective endpoint : an.expandAll(t.annos, ctx)) {
            String base = endpoint.base();
            String id = text(endpoint, "id");
            if (id == null) {
                continue;
            }
            if (base.equals("Endpoint") || base.equals("WebEndpoint") || base.equals("JmxEndpoint")) {
                for (MethodDecl m : t.methods) {
                    for (Effective op : an.expandAll(m.annos(), ctx)) {
                        String subkind = switch (op.base()) {
                            case "ReadOperation" -> "READ";
                            case "WriteOperation" -> "WRITE";
                            case "DeleteOperation" -> "DELETE";
                            default -> null;
                        };
                        if (subkind != null) {
                            record("ACTUATOR", subkind, id, t, new Decl(t, m), "@" + op.useSite().name(), Status.STATIC);
                        }
                    }
                }
            } else if (base.equals("RestControllerEndpoint") || base.equals("ControllerEndpoint")) {
                for (MethodDecl m : t.methods) {
                    Effective mapping = an.first(m.annos(), ctx, "RequestMapping");
                    if (mapping == null) {
                        continue;
                    }
                    Arg pathArg = mapping.attr("path", "value");
                    List<Value> paths = pathArg == null ? List.of(Value.of("", "")) : eval(pathArg).elements();
                    for (Value p : paths.isEmpty() ? List.of(Value.of("", "")) : paths) {
                        String target = p.text().isEmpty() ? id : id + HttpExtractor.joinPaths("", p.text());
                        record("ACTUATOR", "WEB", target, t, new Decl(t, m), "@" + mapping.useSite().name(), p.status());
                    }
                }
            }
        }
    }
}
