package studio.volkoff.atlas.resolve;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.FieldDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.Status;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Evaluates annotation attributes and constant initialisers per the value-resolution rules. */
public final class Evaluator {

    /** Where an expression appears: the type whose scope applies, and its file. */
    public record Ctx(TypeDecl type, SourceFile file) {
        public static Ctx of(TypeDecl t) {
            return new Ctx(t, t.file);
        }
    }

    public record EnumConst(String name) {
    }

    public record ClassRef(String simpleName) {
    }

    public record AnnoRef(Anno anno, Ctx ctx) {
    }

    /**
     * An evaluated value. {@code v} is a String, Long, Double, Boolean, Character, {@link EnumConst},
     * {@link ClassRef}, {@link AnnoRef}, a {@code List<Value>}, or {@code null} when unresolvable;
     * {@code text} renders it, with unresolvable parts as {@code <expression>}.
     */
    public record Value(Object v, String text, Status status, Set<String> via) {

        public static Value of(Object v, String text) {
            return new Value(v, text, Status.STATIC, Set.of());
        }

        public static Value unknown(String exprText) {
            return new Value(null, "<" + exprText + ">", Status.UNKNOWN, Set.of());
        }

        Value with(Status s, Set<String> extraVia) {
            Set<String> v2 = new TreeSet<>(via);
            v2.addAll(extraVia);
            return new Value(v, text, status.worst(s), v2);
        }

        public boolean known() {
            return v != null;
        }

        /** The elements of an array value, or the value itself as a one-element list. */
        @SuppressWarnings("unchecked")
        public List<Value> elements() {
            return v instanceof List<?> l ? (List<Value>) l : List.of(this);
        }
    }

    private record FieldHit(FieldDecl field, TypeDecl owner, String enumConstant) {
    }

    private final Index index;
    private final Map<FieldDecl, Value> memo = new IdentityHashMap<>();
    private final Set<FieldDecl> inProgress = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public Evaluator(Index index) {
        this.index = index;
    }

    public Value eval(Expr e, Ctx ctx) {
        if (e instanceof Expr.Lit l) {
            return Value.of(l.value(), String.valueOf(l.value()));
        }
        if (e instanceof Expr.Unary u) {
            Value o = eval(u.operand(), ctx);
            if (u.op().equals("-") && o.v() instanceof Long n) {
                return new Value(-n, String.valueOf(-n), o.status(), o.via());
            }
            if (u.op().equals("-") && o.v() instanceof Double d) {
                return new Value(-d, String.valueOf(-d), o.status(), o.via());
            }
            return o.known() ? o : Value.unknown(Expr.render(e));
        }
        if (e instanceof Expr.Binary b) {
            return binary(b, eval(b.left(), ctx), eval(b.right(), ctx));
        }
        if (e instanceof Expr.Arr a) {
            List<Value> items = new ArrayList<>();
            Status s = Status.STATIC;
            Set<String> via = new TreeSet<>();
            StringBuilder text = new StringBuilder();
            for (Expr x : a.elements()) {
                Value v = eval(x, ctx);
                items.add(v);
                s = s.worst(v.status());
                via.addAll(v.via());
                if (!text.isEmpty()) {
                    text.append(',');
                }
                text.append(v.text());
            }
            return new Value(items, text.toString(), s, via);
        }
        if (e instanceof Expr.ClassLit c) {
            String simple = JavaSourceParser.simpleTypeName(c.type());
            return Value.of(new ClassRef(simple), simple);
        }
        if (e instanceof Expr.AnnoValue a) {
            return Value.of(new AnnoRef(a.anno(), ctx), a.anno().text());
        }
        if (e instanceof Expr.Ref r) {
            return ref(r.name(), ctx);
        }
        return Value.unknown(Expr.render(e));
    }

    /** The constant name of an enum-valued attribute, resolved or not: {@code RequestMethod.GET} → {@code GET}. */
    public static String enumName(Expr e) {
        if (e instanceof Expr.Ref r) {
            String n = r.name();
            return n.substring(n.lastIndexOf('.') + 1);
        }
        return null;
    }

    private Value binary(Expr.Binary b, Value l, Value r) {
        Status s = l.status().worst(r.status());
        Set<String> via = new TreeSet<>(l.via());
        via.addAll(r.via());
        if (b.op().equals("+")) {
            if (l.v() instanceof Long x && r.v() instanceof Long y) {
                return new Value(x + y, String.valueOf(x + y), s, via);
            }
            if (isNumber(l.v()) && isNumber(r.v())) {
                double d = ((Number) l.v()).doubleValue() + ((Number) r.v()).doubleValue();
                return new Value(d, String.valueOf(d), s, via);
            }
            String text = l.text() + r.text();
            return new Value(l.known() && r.known() ? text : null, text, s, via);
        }
        if (l.v() instanceof Long x && r.v() instanceof Long y) {
            Long result = switch (b.op()) {
                case "-" -> x - y;
                case "*" -> x * y;
                case "/" -> y == 0 ? null : x / y;
                default -> null;
            };
            if (result != null) {
                return new Value(result, String.valueOf(result), s, via);
            }
        }
        return new Value(null, "<" + Expr.render(b) + ">", Status.UNKNOWN, via);
    }

    private static boolean isNumber(Object o) {
        return o instanceof Long || o instanceof Double;
    }

    private Value ref(String name, Ctx ctx) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            FieldHit hit = lexical(name, ctx.type());
            if (hit != null) {
                return value(hit);
            }
            for (String si : ctx.file().staticImports()) {
                if (si.endsWith("." + name)) {
                    TypeDecl t = typeByName(si.substring(0, si.length() - name.length() - 1));
                    FieldHit h = t == null ? null : member(t, name, new HashSet<>());
                    return h == null ? Value.unknown(name) : value(h);
                }
            }
            FieldHit match = null;
            int matches = 0;
            for (String sw : ctx.file().staticWildcardImports()) {
                TypeDecl t = typeByName(sw);
                FieldHit h = t == null ? null : member(t, name, new HashSet<>());
                if (h != null) {
                    match = h;
                    matches++;
                }
            }
            if (matches == 1) {
                return value(match).with(Status.INFERRED, Set.of("wildcard-import"));
            }
            return Value.unknown(name);
        }
        String typePart = name.substring(0, dot);
        String member = name.substring(dot + 1);
        Index.Resolved r = index.resolveType(typePart, ctx.type(), ctx.file(), null);
        if (r == null) {
            return Value.unknown(name);
        }
        FieldHit hit = member(r.type(), member, new HashSet<>());
        if (hit == null) {
            return Value.unknown(name);
        }
        Value v = value(hit);
        return r.viaWildcard() ? v.with(Status.INFERRED, Set.of("wildcard-import")) : v;
    }

    private TypeDecl typeByName(String fqn) {
        TypeDecl t = index.byFqn(fqn);
        if (t != null) {
            return t;
        }
        Index.Resolved r = index.resolveType(fqn, null, emptyFile(), null);
        return r == null ? null : r.type();
    }

    private static SourceFile emptyFile() {
        return new SourceFile("", ".", "", null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private FieldHit lexical(String name, TypeDecl type) {
        for (TypeDecl c = type; c != null; c = c.enclosing) {
            FieldHit h = member(c, name, new HashSet<>());
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    private FieldHit member(TypeDecl t, String name, Set<TypeDecl> visited) {
        if (!visited.add(t)) {
            return null;
        }
        for (FieldDecl f : t.fields) {
            if (f.name().equals(name)) {
                return new FieldHit(f, t, null);
            }
        }
        if (t.enumConstants.contains(name)) {
            return new FieldHit(null, t, name);
        }
        for (TypeDecl s : index.supertypes(t)) {
            FieldHit h = member(s, name, visited);
            if (h != null) {
                return h;
            }
        }
        return null;
    }

    private Value value(FieldHit hit) {
        if (hit.enumConstant() != null) {
            return Value.of(new EnumConst(hit.enumConstant()), hit.enumConstant());
        }
        Value cached = memo.get(hit.field());
        if (cached != null) {
            return cached;
        }
        if (!inProgress.add(hit.field())) {
            return Value.unknown(hit.owner().simpleName + "." + hit.field().name());
        }
        try {
            Value v = eval(hit.field().init(), Ctx.of(hit.owner()));
            boolean whollyUnknown = !v.known() && v.text().startsWith("<") && v.text().indexOf('>') == v.text().length() - 1;
            if (whollyUnknown) {
                v = Value.unknown(hit.owner().simpleName + "." + hit.field().name());
            }
            memo.put(hit.field(), v);
            return v;
        } finally {
            inProgress.remove(hit.field());
        }
    }
}
