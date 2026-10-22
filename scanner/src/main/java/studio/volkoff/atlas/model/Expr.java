package studio.volkoff.atlas.model;

import java.util.List;
import java.util.stream.Collectors;

/** The subset of Java expressions that can appear in annotation attributes and constant initialisers. */
public sealed interface Expr {

    /** A String, Long, Double, Boolean or Character literal. */
    record Lit(Object value) implements Expr {
    }

    /** A simple or dotted name: {@code NAME}, {@code Paths.Orders.BASE}, {@code RequestMethod.GET}. */
    record Ref(String name) implements Expr {
    }

    /** {@code Foo.class}; {@code type} is the type text as written. */
    record ClassLit(String type) implements Expr {
    }

    record Binary(String op, Expr left, Expr right) implements Expr {
    }

    record Unary(String op, Expr operand) implements Expr {
    }

    record Arr(List<Expr> elements) implements Expr {
    }

    record AnnoValue(Anno anno) implements Expr {
    }

    /** Anything that is not a constant expression, kept as source text. */
    record Other(String text) implements Expr {
    }

    static String render(Expr e) {
        if (e instanceof Lit l) {
            return l.value() instanceof String s ? '"' + s + '"' : String.valueOf(l.value());
        } else if (e instanceof Ref r) {
            return r.name();
        } else if (e instanceof ClassLit c) {
            return c.type() + ".class";
        } else if (e instanceof Binary b) {
            return render(b.left()) + " " + b.op() + " " + render(b.right());
        } else if (e instanceof Unary u) {
            return u.op() + render(u.operand());
        } else if (e instanceof Arr a) {
            return a.elements().stream().map(Expr::render).collect(Collectors.joining(", ", "{", "}"));
        } else if (e instanceof AnnoValue a) {
            return a.anno().text();
        }
        return ((Other) e).text();
    }
}
