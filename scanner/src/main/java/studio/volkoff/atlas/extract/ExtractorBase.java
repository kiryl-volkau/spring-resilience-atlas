package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.Status;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations;
import studio.volkoff.atlas.resolve.Annotations.Arg;
import studio.volkoff.atlas.resolve.Annotations.Effective;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Evaluator.Value;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.JavaSourceParser;
import studio.volkoff.atlas.scan.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** State and helpers shared by the per-kind extractors. */
abstract class ExtractorBase {

    record Decl(TypeDecl owner, MethodDecl method) {
    }

    /**
     * A supertype name found on {@code declaringType}, which is the type itself or one of its
     * source-set ancestors; {@code raw} is the supertype text as written, generics included.
     */
    record SuperMatch(TypeDecl declaringType, String raw, String simple, boolean inherited) {
    }

    /** What every extractor works on; {@code out} collects the records. */
    record Context(Index index, Evaluator ev, Annotations an, Workspace ws, List<EntryPoint> out) {
    }

    protected final Index index;
    protected final Evaluator ev;
    protected final Annotations an;
    protected final Workspace ws;
    private final List<EntryPoint> out;

    ExtractorBase(Context c) {
        this.index = c.index();
        this.ev = c.ev();
        this.an = c.an();
        this.ws = c.ws();
        this.out = c.out();
    }

    protected EntryPoint record(String kind, String subkind, String target, TypeDecl owner, Decl body, String anno,
                                Status status) {
        return record(kind, subkind, target, owner, body, anno, status, "*");
    }

    /** {@code methodIfAbsent} names the record's method when {@code body} is null, e.g. an inherited {@code onMessage}. */
    protected EntryPoint record(String kind, String subkind, String target, TypeDecl owner, Decl body, String anno,
                                Status status, String methodIfAbsent) {
        TypeDecl where = body == null ? owner : body.owner();
        int line = body == null ? owner.line : body.method().line();
        EntryPoint ep = new EntryPoint(kind, subkind, target, owner.file.module(), owner.fqn,
                body == null ? methodIfAbsent : body.method().name(), where.file.relPath(), line, anno, status,
                where.file.origin());
        out.add(ep);
        return ep;
    }

    /** Marks a record found through an ancestor, per the "Supertypes" rule. */
    protected static EntryPoint inheritedFrom(EntryPoint ep, SuperMatch m) {
        if (m != null && m.inherited()) {
            ep.via("inherited:" + m.declaringType().simpleName).atLeast(Status.INFERRED);
        }
        return ep;
    }

    protected static void applyEffective(EntryPoint ep, Effective e) {
        e.via().forEach(ep::via);
        if (e.convention()) {
            ep.via("convention-alias").atLeast(Status.INFERRED);
        }
        if (e.wildcard()) {
            ep.via("wildcard-import").atLeast(Status.INFERRED);
        }
    }

    protected Value eval(Arg a) {
        return ev.eval(a.expr(), a.ctx());
    }

    /** The text of an attribute, or {@code null} when absent. */
    protected String text(Effective e, String... names) {
        Arg a = e.attr(names);
        return a == null ? null : eval(a).text();
    }

    protected static String joined(Value v) {
        List<String> parts = new ArrayList<>();
        for (Value e : v.elements()) {
            parts.add(e.text());
        }
        return String.join(",", parts);
    }

    protected static Set<String> enumNames(Arg a) {
        Set<String> names = new TreeSet<>();
        if (a == null) {
            return names;
        }
        if (a.expr() instanceof Expr.Arr arr) {
            for (Expr e : arr.elements()) {
                String n = Evaluator.enumName(e);
                if (n != null) {
                    names.add(n);
                }
            }
        } else {
            String n = Evaluator.enumName(a.expr());
            if (n != null) {
                names.add(n);
            }
        }
        return names;
    }

    protected static Decl find(TypeDecl t, String methodName) {
        for (MethodDecl m : t.methods) {
            if (m.name().equals(methodName)) {
                return new Decl(t, m);
            }
        }
        return null;
    }

    /** The first declared method of {@code t} or a source-set ancestor with this name. */
    protected Decl findInHierarchy(TypeDecl t, String methodName) {
        for (TypeDecl x : index.linearize(t)) {
            Decl d = find(x, methodName);
            if (d != null) {
                return d;
            }
        }
        return null;
    }

    /** The first type in {@code t}'s linearisation (itself first) that declares one of these supertype names. */
    protected SuperMatch findSupertype(TypeDecl t, Set<String> simpleNames) {
        for (TypeDecl x : index.linearize(t)) {
            for (String s : x.superTypes) {
                String simple = JavaSourceParser.simpleTypeName(s);
                if (simpleNames.contains(simple)) {
                    return new SuperMatch(x, s.trim(), simple, x != t);
                }
            }
        }
        return null;
    }

    /** Whether {@code t} or a source-set ancestor carries an annotation with this framework base. */
    protected boolean hierarchyHas(TypeDecl t, String base) {
        for (TypeDecl x : index.linearize(t)) {
            if (an.has(x.annos, Evaluator.Ctx.of(x), base)) {
                return true;
            }
        }
        return false;
    }

    protected static boolean isConcrete(TypeDecl t) {
        return (t.kind == TypeDecl.Kind.CLASS || t.kind == TypeDecl.Kind.RECORD) && !t.isAbstract;
    }

    protected static boolean hasAnno(List<Anno> annos, String simpleName) {
        for (Anno a : annos) {
            if (a.simpleName().equals(simpleName)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the file imports {@code pkg} or a member of it, or names it in a wildcard import. */
    protected static boolean imports(SourceFile f, String pkg) {
        return f.importsFrom(pkg);
    }

    protected static List<String> mergeVia(List<String> a, List<String> b) {
        List<String> merged = new ArrayList<>(a);
        for (String s : b) {
            if (!merged.contains(s)) {
                merged.add(s);
            }
        }
        return merged;
    }
}
