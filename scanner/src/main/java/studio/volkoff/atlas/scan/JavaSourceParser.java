package studio.volkoff.atlas.scan;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.FieldDecl;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Parses Java sources with the running JDK's javac (parse only, no attribution) into the atlas model.
 * Files with parse errors are returned separately so the caller can scan them with the regex path.
 */
public final class JavaSourceParser {

    public record Result(List<SourceFile> parsed, List<Workspace.SourceEntry> failed, List<String> failureSamples) {
    }

    private static final int BATCH_SIZE = 400;

    /**
     * Parses at the running JDK's language level, then re-parses the failures at the project's
     * declared level, then at the oldest level javac supports: newer levels reject some legacy
     * code, such as extraneous import semicolons (an error since Java 21) or {@code _} as a name.
     */
    public Result parse(List<Workspace.SourceEntry> entries, String projectJavaVersion) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("javac is not available: run the scanner with a JDK, not a JRE");
        }
        Result first = parseAll(compiler, entries, List.of());
        Result result = first;
        for (String level : legacyLevels(compiler, projectJavaVersion)) {
            if (result.failed().isEmpty()) {
                break;
            }
            Result retry = parseAll(compiler, result.failed(), List.of("-source", level));
            List<SourceFile> parsed = new ArrayList<>(result.parsed());
            parsed.addAll(retry.parsed());
            result = new Result(parsed, retry.failed(), retry.failureSamples());
        }
        // The error at the current level names the real problem; a legacy level adds its own noise.
        List<String> samples = new ArrayList<>();
        for (String sample : first.failureSamples()) {
            if (result.failed().stream().anyMatch(e -> sample.startsWith(e.relPath() + ":"))) {
                samples.add(sample);
            }
        }
        return new Result(result.parsed(), result.failed(), samples.isEmpty() ? result.failureSamples() : samples);
    }

    /** The project's declared level (when older than the running JDK), then the oldest supported one. */
    private static List<String> legacyLevels(JavaCompiler compiler, String projectJavaVersion) {
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (javax.lang.model.SourceVersion v : compiler.getSourceVersions()) {
            String n = v.name();
            if (n.startsWith("RELEASE_")) {
                int r = Integer.parseInt(n.substring("RELEASE_".length()));
                if (r >= 8) {
                    min = Math.min(min, r);
                    max = Math.max(max, r);
                }
            }
        }
        if (min == Integer.MAX_VALUE) {
            return List.of();
        }
        List<String> levels = new ArrayList<>();
        if (projectJavaVersion != null) {
            String v = projectJavaVersion.startsWith("1.") ? projectJavaVersion.substring(2) : projectJavaVersion;
            try {
                int declared = Integer.parseInt(v.replaceAll("[^0-9].*$", ""));
                if (declared > min && declared < max) {
                    levels.add(String.valueOf(declared));
                }
            } catch (NumberFormatException ignored) {
                // An unparseable declared version (a property reference) only skips that retry.
            }
        }
        levels.add(String.valueOf(min));
        return levels;
    }

    private Result parseAll(JavaCompiler compiler, List<Workspace.SourceEntry> entries, List<String> extraOptions)
            throws Exception {
        List<List<Workspace.SourceEntry>> batches = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += BATCH_SIZE) {
            batches.add(entries.subList(i, Math.min(entries.size(), i + BATCH_SIZE)));
        }
        int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (List<Workspace.SourceEntry> batch : batches) {
                futures.add(pool.submit(() -> parseBatch(compiler, batch, extraOptions)));
            }
            List<SourceFile> parsed = new ArrayList<>();
            List<Workspace.SourceEntry> failed = new ArrayList<>();
            List<String> samples = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Result r;
                try {
                    r = futures.get(i).get();
                } catch (java.util.concurrent.ExecutionException e) {
                    r = parseOneByOne(compiler, batches.get(i), extraOptions);
                }
                parsed.addAll(r.parsed());
                failed.addAll(r.failed());
                samples.addAll(r.failureSamples());
            }
            return new Result(parsed, failed, samples.subList(0, Math.min(5, samples.size())));
        } finally {
            pool.shutdownNow();
        }
    }

    /** After javac itself crashed on a batch: isolates the file that crashes it, so one file cannot void the scan. */
    private Result parseOneByOne(JavaCompiler compiler, List<Workspace.SourceEntry> batch, List<String> extraOptions) {
        List<SourceFile> parsed = new ArrayList<>();
        List<Workspace.SourceEntry> failed = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        for (Workspace.SourceEntry e : batch) {
            try {
                Result r = parseBatch(compiler, List.of(e), extraOptions);
                parsed.addAll(r.parsed());
                failed.addAll(r.failed());
                samples.addAll(r.failureSamples());
            } catch (Throwable t) {
                failed.add(e);
                samples.add(e.relPath() + ": javac crashed: " + t.getClass().getSimpleName());
            }
        }
        return new Result(parsed, failed, samples);
    }

    private Result parseBatch(JavaCompiler compiler, List<Workspace.SourceEntry> batch, List<String> extraOptions)
            throws IOException {
        // Keyed by URI: javac wraps client file objects, so identity does not survive the round trip.
        Map<URI, Workspace.SourceEntry> byUri = new HashMap<>();
        List<JavaFileObject> objects = new ArrayList<>();
        for (Workspace.SourceEntry e : batch) {
            JavaFileObject o = new InMemorySource(e.path().toUri(), readSource(e));
            byUri.put(o.toUri(), e);
            objects.add(o);
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        List<String> options = new ArrayList<>(List.of("-proc:none", "-nowarn", "-Xlint:none"));
        options.addAll(extraOptions);
        JavacTask task = (JavacTask) compiler.getTask(null, null, diagnostics, options, null, objects);
        Iterable<? extends CompilationUnitTree> units = task.parse();

        Set<URI> broken = new HashSet<>();
        List<String> samples = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() != null) {
                if (broken.add(d.getSource().toUri()) && samples.size() < 5) {
                    Workspace.SourceEntry e = byUri.get(d.getSource().toUri());
                    samples.add((e == null ? "?" : e.relPath()) + ":" + d.getLineNumber() + ": "
                            + d.getMessage(java.util.Locale.ROOT).lines().findFirst().orElse(""));
                }
            }
        }

        Trees trees = Trees.instance(task);
        List<SourceFile> parsed = new ArrayList<>();
        List<Workspace.SourceEntry> failed = new ArrayList<>();
        for (CompilationUnitTree cu : units) {
            Workspace.SourceEntry entry = byUri.get(cu.getSourceFile().toUri());
            if (entry == null) {
                continue;
            }
            if (broken.contains(entry.path().toUri())) {
                failed.add(entry);
                continue;
            }
            parsed.add(new UnitConverter(cu, trees.getSourcePositions(), entry).convert());
        }
        return new Result(parsed, failed, samples);
    }

    /** Reads UTF-8, falling back to windows-1251 or ISO-8859-1 so legacy encodings still parse. */
    static String readSource(Workspace.SourceEntry e) throws IOException {
        byte[] bytes = Files.readAllBytes(e.path());
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            Charset fallback = Charset.isSupported("windows-1251") ? Charset.forName("windows-1251") : StandardCharsets.ISO_8859_1;
            text = new String(bytes, fallback);
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private static final class InMemorySource extends SimpleJavaFileObject {
        private final String content;

        InMemorySource(URI uri, String content) {
            super(uri, Kind.SOURCE);
            this.content = content;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }
    }

    private static final class UnitConverter {
        private final CompilationUnitTree cu;
        private final SourcePositions positions;
        private final Workspace.SourceEntry entry;
        private final List<TypeDecl> types = new ArrayList<>();

        UnitConverter(CompilationUnitTree cu, SourcePositions positions, Workspace.SourceEntry entry) {
            this.cu = cu;
            this.positions = positions;
            this.entry = entry;
        }

        SourceFile convert() {
            String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
            List<String> imports = new ArrayList<>();
            List<String> wildcard = new ArrayList<>();
            List<String> staticImports = new ArrayList<>();
            List<String> staticWildcard = new ArrayList<>();
            for (ImportTree i : cu.getImports()) {
                String q = i.getQualifiedIdentifier().toString();
                boolean star = q.endsWith(".*");
                String base = star ? q.substring(0, q.length() - 2) : q;
                if (i.isStatic()) {
                    (star ? staticWildcard : staticImports).add(base);
                } else {
                    (star ? wildcard : imports).add(base);
                }
            }
            for (Tree t : cu.getTypeDecls()) {
                if (t instanceof ClassTree ct) {
                    convertType(ct, pkg.isEmpty() ? "" : pkg + ".", "", null);
                }
            }
            SourceFile file = new SourceFile(entry.relPath(), entry.module(), pkg, entry.origin(),
                    imports, wildcard, staticImports, staticWildcard, types);
            for (TypeDecl t : types) {
                t.file = file;
            }
            return file;
        }

        private TypeDecl convertType(ClassTree ct, String fqnPrefix, String displayPrefix, TypeDecl enclosing) {
            String simple = ct.getSimpleName().toString();
            TypeDecl.Kind kind = switch (ct.getKind()) {
                case INTERFACE -> TypeDecl.Kind.INTERFACE;
                case ENUM -> TypeDecl.Kind.ENUM;
                case ANNOTATION_TYPE -> TypeDecl.Kind.ANNOTATION;
                case RECORD -> TypeDecl.Kind.RECORD;
                default -> TypeDecl.Kind.CLASS;
            };
            boolean isInterfaceLike = kind == TypeDecl.Kind.INTERFACE || kind == TypeDecl.Kind.ANNOTATION;
            List<String> supers = new ArrayList<>();
            boolean hasSuperclass = ct.getExtendsClause() != null;
            if (hasSuperclass) {
                supers.add(ct.getExtendsClause().toString());
            }
            for (Tree i : ct.getImplementsClause()) {
                supers.add(i.toString());
            }
            boolean camelBuilder = supers.stream().anyMatch(t -> simpleTypeName(t).endsWith("RouteBuilder"));
            List<FieldDecl> fields = new ArrayList<>();
            Map<String, String> fieldTypes = new LinkedHashMap<>();
            List<String> enumConstants = new ArrayList<>();
            List<MethodDecl> methods = new ArrayList<>();
            Set<String> referenced = new LinkedHashSet<>();
            List<ClassTree> nestedTrees = new ArrayList<>();
            for (Tree member : ct.getMembers()) {
                if (member instanceof VariableTree vt) {
                    if (kind == TypeDecl.Kind.ENUM && vt.getInitializer() instanceof NewClassTree nc
                            && simpleTypeName(nc.getIdentifier().toString()).equals(simple)) {
                        enumConstants.add(vt.getName().toString());
                        continue;
                    }
                    String type = vt.getType() == null ? "" : vt.getType().toString();
                    referenced.add(simpleTypeName(type));
                    fieldTypes.put(vt.getName().toString(), type);
                    boolean isStatic = isInterfaceLike || vt.getModifiers().getFlags().contains(Modifier.STATIC);
                    if (isStatic && vt.getInitializer() != null) {
                        fields.add(new FieldDecl(vt.getName().toString(), type, expr(vt.getInitializer())));
                    }
                    if (vt.getInitializer() != null) {
                        collectNewTypes(vt.getInitializer(), referenced);
                    }
                } else if (member instanceof MethodTree mt) {
                    MethodDecl md = method(mt, isInterfaceLike, camelBuilder);
                    referenced.addAll(md.newTypes());
                    methods.add(md);
                } else if (member instanceof ClassTree nested) {
                    nestedTrees.add(nested);
                }
            }
            Set<Modifier> flags = ct.getModifiers().getFlags();
            TypeDecl type = new TypeDecl(fqnPrefix + simple, simple, displayPrefix + simple, kind,
                    flags.contains(Modifier.ABSTRACT) || isInterfaceLike, line(ct), annos(ct.getModifiers()),
                    supers, hasSuperclass, fields, fieldTypes, enumConstants, methods, referenced, enclosing);
            types.add(type);
            for (ClassTree nested : nestedTrees) {
                type.nested.add(convertType(nested, type.fqn + ".", type.displayName + ".", type));
            }
            return type;
        }

        private MethodDecl method(MethodTree mt, boolean inInterface, boolean camelBuilder) {
            Set<Modifier> flags = mt.getModifiers().getFlags();
            List<MethodDecl.Param> params = new ArrayList<>();
            for (VariableTree p : mt.getParameters()) {
                params.add(new MethodDecl.Param(p.getName().toString(), p.getType() == null ? "" : p.getType().toString(),
                        annos(p.getModifiers())));
            }
            String returnType = mt.getReturnType() == null ? null : mt.getReturnType().toString();
            boolean collectRoutes = returnType != null && returnType.contains("RouterFunction");
            Map<String, Integer> calls = new LinkedHashMap<>();
            Set<String> newTypes = new LinkedHashSet<>();
            List<MethodDecl.Route> routes = new ArrayList<>();
            List<Expr> fromCalls = new ArrayList<>();
            List<Expr> permitAll = new ArrayList<>();
            MethodDecl.Body body = MethodDecl.Body.EMPTY;
            if (mt.getBody() != null) {
                BodyScanner scanner = new BodyScanner(calls, newTypes, routes, fromCalls, permitAll, collectRoutes, camelBuilder);
                scanner.scan(mt.getBody(), null);
                body = new MethodDecl.Body(scanner.sites, scanner.locals, scanner.assignments);
            }
            boolean isPublic = flags.contains(Modifier.PUBLIC) || (inInterface && !flags.contains(Modifier.PRIVATE));
            return new MethodDecl(mt.getName().toString(), annos(mt.getModifiers()), line(mt), returnType, params,
                    isPublic, flags.contains(Modifier.PRIVATE), flags.contains(Modifier.STATIC), mt.getBody() != null,
                    mt.getDefaultValue() == null ? null : expr(mt.getDefaultValue()), calls, new ArrayList<>(newTypes),
                    routes, fromCalls, permitAll, body);
        }

        /** Collects call names and the call shapes later rules need: routes, Camel endpoints, permitAll matchers. */
        private final class BodyScanner extends TreeScanner<Void, Void> {
            private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");
            private static final Set<String> MATCHERS = Set.of("requestMatchers", "antMatchers", "mvcMatchers");

            private final Map<String, Integer> calls;
            private final Set<String> newTypes;
            private final List<MethodDecl.Route> routes;
            private final List<Expr> fromCalls;
            private final List<Expr> permitAll;
            private final boolean collectRoutes;
            private final boolean camelBuilder;
            private final java.util.Deque<Expr> prefixes = new java.util.ArrayDeque<>();
            private String handlerHint;
            final List<MethodDecl.Site> sites = new ArrayList<>();
            final Map<String, String> locals = new LinkedHashMap<>();
            final List<MethodDecl.Site> assignments = new ArrayList<>();
            /** The innermost call or {@code new} whose lambda or anonymous-class argument is being scanned. */
            private String encl;
            /** The expression of the {@code return} statement being scanned. */
            private Tree returning;
            /** Names checked against {@code null} by enclosing {@code if} statements: assigning them is lazy initialisation. */
            private final java.util.Deque<Set<String>> nullChecked = new java.util.ArrayDeque<>();

            BodyScanner(Map<String, Integer> calls, Set<String> newTypes, List<MethodDecl.Route> routes, List<Expr> fromCalls,
                        List<Expr> permitAll, boolean collectRoutes, boolean camelBuilder) {
                this.calls = calls;
                this.newTypes = newTypes;
                this.routes = routes;
                this.fromCalls = fromCalls;
                this.permitAll = permitAll;
                this.collectRoutes = collectRoutes;
                this.camelBuilder = camelBuilder;
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                ExpressionTree sel = node.getMethodSelect();
                String name = invokedName(node);
                if (name == null) {
                    return super.visitMethodInvocation(node, unused);
                }
                calls.merge(name, 1, Integer::sum);
                String receiver = site(node, name);
                List<? extends ExpressionTree> args = node.getArguments();
                if (name.equals("permitAll") && sel instanceof MemberSelectTree ms
                        && ms.getExpression() instanceof MethodInvocationTree matcher && MATCHERS.contains(invokedName(matcher))) {
                    for (ExpressionTree a : matcher.getArguments()) {
                        Expr e = stringLike(a);
                        if (e != null) {
                            permitAll.add(e);
                        } else if (a instanceof MethodInvocationTree factory) {
                            // Spring Security 6.5+: requestMatchers(withDefaults().matcher(HttpMethod.POST, "/path"))
                            for (ExpressionTree inner : factory.getArguments()) {
                                Expr ie = stringLike(inner);
                                if (ie != null) {
                                    permitAll.add(ie);
                                }
                            }
                        }
                    }
                }
                if (camelBuilder && name.equals("from") && !args.isEmpty()) {
                    fromCalls.add(expr(args.get(0)));
                }
                if (collectRoutes) {
                    if ((name.equals("path") || name.equals("nest")) && args.size() >= 2) {
                        Expr prefix = name.equals("path") ? stringLike(args.get(0)) : nestPrefix(args.get(0));
                        if (prefix != null) {
                            scan(sel, unused);
                            prefixes.addLast(prefix);
                            for (int i = 1; i < args.size(); i++) {
                                scan(args.get(i), unused);
                            }
                            prefixes.removeLast();
                            return null;
                        }
                    }
                    if ((name.equals("route") || name.equals("andRoute")) && args.size() == 2) {
                        scan(sel, unused);
                        String previous = handlerHint;
                        handlerHint = args.get(1).toString();
                        scan(args.get(0), unused);
                        handlerHint = previous;
                        scan(args.get(1), unused);
                        return null;
                    }
                    if (VERBS.contains(name) && !args.isEmpty()) {
                        Expr path = stringLike(args.get(0));
                        if (path != null) {
                            String handler = args.size() >= 2 ? args.get(args.size() - 1).toString() : handlerHint;
                            routes.add(new MethodDecl.Route(name, List.copyOf(prefixes), path, handler));
                        }
                    }
                }
                scan(node.getTypeArguments(), unused);
                scan(sel, unused);
                scanArguments(args, (receiver == null ? "" : receiver) + "." + name);
                return null;
            }

            @Override
            public Void visitNewClass(NewClassTree node, Void unused) {
                String type = simpleTypeName(node.getIdentifier().toString());
                newTypes.add(type);
                sites.add(new MethodDecl.Site(MethodDecl.Site.NEW, intern(type), line(node), false, node.getArguments().size(),
                        encl, firstArgType(node.getArguments()), null, false));
                scan(node.getEnclosingExpression(), unused);
                scan(node.getIdentifier(), unused);
                scan(node.getTypeArguments(), unused);
                scanArguments(node.getArguments(), MethodDecl.Site.NEW + type);
                if (node.getClassBody() != null) {
                    // An anonymous Runnable keeps the context of the call it is passed to; a callback type sets its own.
                    String previous = encl;
                    if (type.startsWith("TransactionSynchronization") || type.equals("Thread") || type.equals("TimerTask")) {
                        encl = intern(previous == null ? MethodDecl.Site.NEW + type : previous + "|" + MethodDecl.Site.NEW + type);
                    } else if (encl == null) {
                        encl = intern(MethodDecl.Site.NEW + type);
                    }
                    scan(node.getClassBody(), unused);
                    encl = previous;
                }
                return null;
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree node, Void unused) {
                String name = node.getName().toString();
                if (!name.equals("<init>")) {
                    ExpressionTree q = node.getQualifierExpression();
                    String receiver = q instanceof IdentifierTree id ? id.getName().toString()
                            : q instanceof MemberSelectTree ms ? (isThis(ms.getExpression()) ? ms.getIdentifier().toString() : ms.toString())
                            : null;
                    if (receiver != null) {
                        sites.add(new MethodDecl.Site(intern(receiver), intern(name), line(node), false, -1, encl, null, null, false));
                    }
                }
                return super.visitMemberReference(node, unused);
            }

            /** Scans arguments, marking the sites inside lambda and anonymous-class arguments with the call that receives them. */
            private void scanArguments(List<? extends ExpressionTree> args, String call) {
                for (ExpressionTree a : args) {
                    boolean deferred = a instanceof LambdaExpressionTree || a instanceof MemberReferenceTree
                            || a instanceof NewClassTree nc && nc.getClassBody() != null;
                    if (deferred) {
                        String previous = encl;
                        encl = intern(previous == null ? call : previous + "|" + call);
                        scan(a, null);
                        encl = previous;
                    } else {
                        scan(a, null);
                    }
                }
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                String type = node.getType() == null ? null : node.getType().toString();
                if (type == null || type.equals("var")) {
                    // var and implicit lambda parameters: javac gives no type; the name still shadows fields.
                    ExpressionTree i = node.getInitializer();
                    type = i instanceof NewClassTree nc ? nc.getIdentifier().toString()
                            : i instanceof MethodInvocationTree mi && invokedName(mi) != null ? MethodDecl.Site.RESULT + invokedName(mi)
                            : "";
                }
                locals.put(node.getName().toString(), intern(type));
                ExpressionTree init = node.getInitializer();
                if (encl == null && (init instanceof LambdaExpressionTree || init instanceof MemberReferenceTree
                        || init instanceof NewClassTree nci && nci.getClassBody() != null)) {
                    // A lambda kept in a variable runs later, wherever it is passed.
                    scan(node.getModifiers(), unused);
                    scan(node.getType(), unused);
                    encl = MethodDecl.Site.LATER;
                    scan(init, unused);
                    encl = null;
                    return null;
                }
                return super.visitVariable(node, unused);
            }

            @Override
            public Void visitReturn(ReturnTree node, Void unused) {
                ExpressionTree e = node.getExpression();
                while (e instanceof ParenthesizedTree p) {
                    e = p.getExpression();
                }
                Tree previous = returning;
                returning = e;
                super.visitReturn(node, unused);
                returning = previous;
                return null;
            }

            @Override
            public Void visitIf(IfTree node, Void unused) {
                scan(node.getCondition(), unused);
                Set<String> names = new java.util.HashSet<>();
                comparedToNull(node.getCondition(), names);
                nullChecked.push(names);
                scan(node.getThenStatement(), unused);
                nullChecked.pop();
                scan(node.getElseStatement(), unused);
                return null;
            }

            private void comparedToNull(ExpressionTree t, Set<String> out) {
                if (t instanceof ParenthesizedTree p) {
                    comparedToNull(p.getExpression(), out);
                } else if (t instanceof BinaryTree b) {
                    if (b.getKind() == Tree.Kind.EQUAL_TO) {
                        ExpressionTree other = b.getLeftOperand().getKind() == Tree.Kind.NULL_LITERAL ? b.getRightOperand()
                                : b.getRightOperand().getKind() == Tree.Kind.NULL_LITERAL ? b.getLeftOperand() : null;
                        String n = assignedName(other);
                        if (n != null) {
                            out.add(n);
                        }
                    } else if (b.getKind() == Tree.Kind.CONDITIONAL_AND) {
                        comparedToNull(b.getLeftOperand(), out);
                        comparedToNull(b.getRightOperand(), out);
                    }
                }
            }

            @Override
            public Void visitAssignment(AssignmentTree node, Void unused) {
                assigned(node.getVariable());
                return super.visitAssignment(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                assigned(node.getVariable());
                return super.visitCompoundAssignment(node, unused);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                switch (node.getKind()) {
                    case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> assigned(node.getExpression());
                    default -> { }
                }
                return super.visitUnary(node, unused);
            }

            private void assigned(ExpressionTree target) {
                String name = assignedName(target);
                if (name == null) {
                    return;
                }
                for (Set<String> lazy : nullChecked) {
                    if (lazy.contains(name)) {
                        return;
                    }
                }
                boolean qualified = target instanceof MemberSelectTree;
                assignments.add(new MethodDecl.Site(qualified ? "this" : null, intern(name), line(target), false, 0, encl, null, null, false));
            }

            private static String assignedName(ExpressionTree target) {
                return target instanceof IdentifierTree id ? id.getName().toString()
                        : target instanceof MemberSelectTree ms && isThis(ms.getExpression()) ? ms.getIdentifier().toString()
                        : null;
            }

            /** Records the call site and returns its receiver root, or null for an unqualified call or an untracked receiver. */
            private String site(MethodInvocationTree node, String name) {
                int args = node.getArguments().size();
                String argType = firstArgType(node.getArguments());
                String hints = argHints(node.getArguments());
                boolean returned = node == returning;
                if (!(node.getMethodSelect() instanceof MemberSelectTree ms)) {
                    sites.add(new MethodDecl.Site(null, intern(name), line(node), false, args, encl, argType, hints, returned));
                    return null;
                }
                ExpressionTree e = ms.getExpression();
                boolean chained = false;
                while (true) {
                    if (e instanceof ParenthesizedTree p) {
                        e = p.getExpression();
                    } else if (e instanceof TypeCastTree c) {
                        e = c.getExpression();
                    } else if (e instanceof MethodInvocationTree mi && mi.getMethodSelect() instanceof MemberSelectTree inner) {
                        chained = true;
                        e = inner.getExpression();
                    } else {
                        break;
                    }
                }
                String receiver;
                if (e instanceof IdentifierTree id) {
                    receiver = id.getName().toString();
                } else if (e instanceof MemberSelectTree inner) {
                    receiver = isThis(inner.getExpression()) ? inner.getIdentifier().toString() : inner.toString();
                } else if (e instanceof NewClassTree nc) {
                    receiver = MethodDecl.Site.NEW + simpleTypeName(nc.getIdentifier().toString());
                } else if (e instanceof MethodInvocationTree rootCall && rootCall.getMethodSelect() instanceof IdentifierTree rid) {
                    receiver = MethodDecl.Site.CALL + rid.getName();
                } else {
                    return null;
                }
                receiver = intern(receiver);
                sites.add(new MethodDecl.Site(receiver, intern(name), line(node), chained, args, encl, argType, hints, returned));
                return receiver;
            }

            /** Only calls with two or more same-named candidates need these, but the parser cannot know which. */
            private static String argHints(List<? extends ExpressionTree> args) {
                if (args.isEmpty()) {
                    return null;
                }
                StringBuilder b = new StringBuilder();
                for (ExpressionTree a : args) {
                    if (b.length() > 0) {
                        b.append(',');
                    }
                    if (a instanceof IdentifierTree id) {
                        b.append("v:").append(id.getName());
                    } else if (a instanceof NewClassTree nc) {
                        b.append("n:").append(simpleTypeName(nc.getIdentifier().toString()));
                    } else if (a instanceof MethodInvocationTree mi && invokedName(mi) != null) {
                        b.append("c:").append(invokedName(mi));
                    } else if (a instanceof LiteralTree l && l.getValue() instanceof String) {
                        b.append('s');
                    } else {
                        b.append('?');
                    }
                }
                return intern(b.toString());
            }

            private static String firstArgType(List<? extends ExpressionTree> args) {
                return !args.isEmpty() && args.get(0) instanceof NewClassTree nc
                        ? intern(simpleTypeName(nc.getIdentifier().toString())) : null;
            }

            private static boolean isThis(ExpressionTree e) {
                return e instanceof IdentifierTree id && id.getName().contentEquals("this");
            }

            /** {@code path("/p")} inside {@code nest(...)}, possibly followed by {@code .and(...)}. */
            private Expr nestPrefix(ExpressionTree t) {
                ExpressionTree cur = t;
                while (cur instanceof MethodInvocationTree mi) {
                    if ("path".equals(invokedName(mi)) && !mi.getArguments().isEmpty()) {
                        return stringLike(mi.getArguments().get(0));
                    }
                    cur = mi.getMethodSelect() instanceof MemberSelectTree ms ? ms.getExpression() : null;
                }
                return null;
            }

            /** An argument that can evaluate to a string: a literal, a constant reference or a concatenation. */
            private Expr stringLike(ExpressionTree t) {
                if (t instanceof LiteralTree l && l.getValue() instanceof String) {
                    return expr(t);
                }
                if (t instanceof IdentifierTree || t instanceof MemberSelectTree || t instanceof BinaryTree
                        || t instanceof ParenthesizedTree) {
                    return expr(t);
                }
                return null;
            }
        }

        private static String invokedName(MethodInvocationTree mi) {
            ExpressionTree sel = mi.getMethodSelect();
            return sel instanceof MemberSelectTree ms ? ms.getIdentifier().toString()
                    : sel instanceof IdentifierTree id ? id.getName().toString() : null;
        }

        private List<Anno> annos(ModifiersTree mods) {
            List<Anno> list = new ArrayList<>();
            for (AnnotationTree a : mods.getAnnotations()) {
                list.add(anno(a));
            }
            return list;
        }

        private Anno anno(AnnotationTree a) {
            Map<String, Expr> args = new LinkedHashMap<>();
            for (ExpressionTree arg : a.getArguments()) {
                if (arg instanceof AssignmentTree as) {
                    args.put(as.getVariable().toString(), expr(as.getExpression()));
                } else {
                    args.put("value", expr(arg));
                }
            }
            return new Anno(a.getAnnotationType().toString(), args, line(a), a.toString());
        }

        private Expr expr(Tree t) {
            if (t instanceof LiteralTree l) {
                Object v = l.getValue();
                if (v instanceof Integer || v instanceof Short || v instanceof Byte) {
                    v = ((Number) v).longValue();
                } else if (v instanceof Float f) {
                    v = f.doubleValue();
                }
                return new Expr.Lit(v);
            }
            if (t instanceof IdentifierTree id) {
                return new Expr.Ref(id.getName().toString());
            }
            if (t instanceof MemberSelectTree ms) {
                if (ms.getIdentifier().contentEquals("class")) {
                    return new Expr.ClassLit(ms.getExpression().toString());
                }
                String text = ms.toString();
                return isDottedName(ms) ? new Expr.Ref(text) : new Expr.Other(text);
            }
            if (t instanceof ParenthesizedTree p) {
                return expr(p.getExpression());
            }
            if (t instanceof TypeCastTree c) {
                return expr(c.getExpression());
            }
            if (t instanceof BinaryTree b) {
                String op = switch (b.getKind()) {
                    case PLUS -> "+";
                    case MINUS -> "-";
                    case MULTIPLY -> "*";
                    case DIVIDE -> "/";
                    default -> null;
                };
                return op == null ? new Expr.Other(b.toString()) : new Expr.Binary(op, expr(b.getLeftOperand()), expr(b.getRightOperand()));
            }
            if (t instanceof UnaryTree u && (u.getKind() == Tree.Kind.UNARY_MINUS || u.getKind() == Tree.Kind.UNARY_PLUS)) {
                return new Expr.Unary(u.getKind() == Tree.Kind.UNARY_MINUS ? "-" : "+", expr(u.getExpression()));
            }
            if (t instanceof NewArrayTree na) {
                List<Expr> elements = new ArrayList<>();
                if (na.getInitializers() != null) {
                    for (ExpressionTree e : na.getInitializers()) {
                        elements.add(expr(e));
                    }
                }
                return new Expr.Arr(elements);
            }
            if (t instanceof AnnotationTree at) {
                return new Expr.AnnoValue(anno(at));
            }
            return new Expr.Other(t.toString());
        }

        private static boolean isDottedName(MemberSelectTree ms) {
            ExpressionTree e = ms.getExpression();
            while (e instanceof MemberSelectTree inner) {
                e = inner.getExpression();
            }
            return e instanceof IdentifierTree;
        }

        private void collectNewTypes(Tree t, Set<String> out) {
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    out.add(simpleTypeName(node.getIdentifier().toString()));
                    return super.visitNewClass(node, unused);
                }
            }.scan(t, null);
        }

        private int line(Tree t) {
            long pos = positions.getStartPosition(cu, t);
            return pos < 0 ? 0 : (int) cu.getLineMap().getLineNumber(pos);
        }
    }

    /** Names and receivers repeat across thousands of call sites; one copy each keeps big repositories in memory. */
    static String intern(String s) {
        return s == null ? null : s.intern();
    }

    /** {@code java.util.List<Foo>} → {@code List}. */
    public static String simpleTypeName(String typeText) {
        String s = typeText;
        int lt = s.indexOf('<');
        if (lt >= 0) {
            s = s.substring(0, lt);
        }
        s = s.replace("[]", "").replace("...", "").trim();
        int at = s.lastIndexOf(' ');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int dot = s.lastIndexOf('.');
        return dot < 0 ? s : s.substring(dot + 1);
    }
}
