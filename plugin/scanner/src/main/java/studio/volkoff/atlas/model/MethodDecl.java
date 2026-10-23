package studio.volkoff.atlas.model;

import java.util.List;
import java.util.Map;

/**
 * A method or annotation member.
 *
 * @param line           first line of the declaration, annotations included
 * @param endLine        last line of the declaration, its closing brace; 0 when unknown
 * @param returnType     return type text as written, {@code null} for constructors
 * @param params         parameters in declaration order
 * @param hasBody        false for abstract and interface methods without a default body
 * @param defaultValue   the {@code default} of an annotation member, otherwise {@code null}
 * @param calls          invoked method names in the body, with counts
 * @param newTypes       simple names of types instantiated in the body
 * @param routes         route-builder calls with a path, collected only in methods returning a {@code RouterFunction}
 * @param fromCalls      first arguments of {@code from(...)} calls, collected only in Camel route builders
 * @param permitAllPatterns arguments of matcher calls chained directly into {@code permitAll()}
 * @param body           call sites, local variable types and field assignments, for effect analysis
 */
public record MethodDecl(
        String name,
        List<Anno> annos,
        int line,
        int endLine,
        String returnType,
        List<Param> params,
        boolean isPublic,
        boolean isPrivate,
        boolean isStatic,
        boolean hasBody,
        Expr defaultValue,
        Map<String, Integer> calls,
        List<String> newTypes,
        List<Route> routes,
        List<Expr> fromCalls,
        List<Expr> permitAllPatterns,
        Body body) {

    /**
     * What a body does, as written: no types are attributed.
     *
     * @param sites       method calls and {@code new} expressions, in source order
     * @param locals      local variable and lambda parameter name to declared type text; {@code var}
     *                    takes the type of a {@code new} initialiser, otherwise it is absent
     * @param assignments names assigned with {@code =}, a compound operator, {@code ++} or {@code --},
     *                    when the target is a bare name ({@code receiver} null) or {@code this.name}
     *                    ({@code receiver} "this"). Assignments guarded by {@code if (name == null)}
     *                    are lazy initialisation and left out.
     */
    public record Body(List<Site> sites, Map<String, String> locals, List<Site> assignments) {
        public static final Body EMPTY = new Body(List.of(), Map.of(), List.of());
    }

    /**
     * One call, method reference or {@code new} expression.
     *
     * @param receiver the root of the call's receiver chain: {@code null} for an unqualified call,
     *                 {@code this}/{@code super}, a name ({@code this.x} gives {@code x}), a dotted
     *                 name, {@link #NEW} for a {@code new} expression (whose {@code name} is the
     *                 type), or {@code <new>Type} for a call on a fresh instance
     * @param chained  the call is made on the result of another call that starts at {@code receiver}
     * @param args     the number of arguments, or -1 for a method reference
     * @param encl     the calls or {@code new}s whose lambda or anonymous-class arguments contain this
     *                 site, outermost first and joined by {@code |}, each as {@code receiver.name} or
     *                 {@code <new>Type}; {@code null} when the site runs directly in the method body
     * @param argType  the simple type of a {@code new} expression passed as the first argument, or null
     * @param argHints one hint per argument, comma-separated, to tell same-arity overloads apart:
     *                 {@code v:name} for a variable, {@code n:Type} for a {@code new}, {@code c:method}
     *                 for a call's result, {@code s} for a string literal, {@code ?} otherwise
     * @param returned the call is the method's return value, so the caller decides whether it waits for it
     */
    public record Site(String receiver, String name, int line, boolean chained, int args, String encl, String argType,
                       String argHints, boolean returned) {
        public static final String NEW = "<new>";
        /** {@code encl} of a lambda stored in a variable: it runs later, at a point the parse does not show. */
        public static final String LATER = "<later>";
        /** Receiver prefix for a call made on the result of an unqualified call: {@code getExecutor().submit(..)}. */
        public static final String CALL = "<call>";
        /** Local type prefix for a {@code var} initialised from a call: {@code var f = executor.submit(..)}. */
        public static final String RESULT = "<result>";

        public Site(String receiver, String name, int line, boolean chained) {
            this(receiver, name, line, chained, 0, null, null, null, false);
        }
    }

    /** A method parameter: its name, type text as written, and annotations. */
    public record Param(String name, String type, List<Anno> annos) {
    }

    /**
     * A functional route: {@code verb} is the builder or predicate call ({@code GET}, ...),
     * {@code prefixes} the paths of enclosing {@code path}/{@code nest} calls, outermost first.
     */
    public record Route(String verb, List<Expr> prefixes, Expr path, String handler) {
    }

    public List<String> paramTypes() {
        return params.stream().map(Param::type).toList();
    }

    public String signature() {
        return name + "/" + params.size();
    }

    public boolean isConstructor() {
        return returnType == null;
    }
}
