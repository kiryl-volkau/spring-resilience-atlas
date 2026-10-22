package studio.volkoff.atlas.effects;

import studio.volkoff.atlas.extract.EntryPoint;
import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.MethodDecl.Site;
import studio.volkoff.atlas.model.Origin;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations;
import studio.volkoff.atlas.resolve.Annotations.Effective;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Walks from an entry method through its own class and up to {@link #MAX_BEAN_HOPS} injected
 * beans, recording the side effects that reliability rules care about. Parse trees only: a
 * receiver's type comes from its declaration (local, parameter or field), never from inference,
 * so calls on expressions of unknown type are counted as unresolved rather than guessed.
 */
public final class EffectAnalyzer {

    static final int MAX_BEAN_HOPS = 3;
    static final int MAX_METHODS = 120;
    static final int MAX_IMPLEMENTATIONS = 3;

    /** The transaction a statement runs in. */
    enum Tx { NONE, READ_ONLY, READ_WRITE }

    /** What a {@code @Transactional} does to the caller's transaction. */
    private enum TxMode { OPENS, READ_ONLY, SUSPENDS, JOINS }

    /**
     * When a lambda or anonymous-class body runs relative to the method that creates it. NEVER is
     * an anonymous class that is only defined here: its methods run when some other code calls them.
     */
    private enum Deferral { INLINE, ASYNC, AFTER_COMMIT, TRANSACTION, LATER, NEVER }

    private static final Set<String> HTTP_CLIENT_ANNOTATIONS = Set.of("FeignClient", "HttpExchange", "GetExchange",
            "PostExchange", "PutExchange", "DeleteExchange", "PatchExchange", "RegisterRestClient");
    private static final Set<String> SETUP_ANNOTATIONS = Set.of("PostConstruct", "Bean", "Autowired", "Value", "Inject",
            "BeforeEach", "AfterPropertiesSet");
    private static final Set<String> SETUP_METHODS = Set.of("afterPropertiesSet", "init", "initialize", "setApplicationContext",
            "setBeanFactory", "setEnvironment", "destroy", "close", "start", "stop");
    /** Spring calls these on the raw bean, so proxy annotations on them have no effect. */
    private static final Set<String> INIT_CALLBACKS = Set.of("POST_CONSTRUCT", "AFTER_PROPERTIES_SET", "PRE_DESTROY", "DESTROY");
    private static final Set<String> DATA_ANNOTATIONS = Set.of("Entity", "Embeddable", "MappedSuperclass", "Document",
            "Table", "Data", "Value", "Getter", "Setter", "Builder", "ConfigurationProperties");
    private static final Set<String> BEAN_ANNOTATIONS = Set.of("Component", "Service", "Repository", "Controller",
            "Configuration");
    private static final Set<String> SINGLETON_ACCESSORS = Set.of("getInstance", "instance", "getDefault", "current");
    /** Calls whose result is a future of the work they start. */
    private static final Set<String> ASYNC_STARTERS = Set.of("submit", "supplyAsync", "runAsync", "submitCompletable",
            "submitListenable", "schedule");
    private static final Set<String> ASYNC_RESULT_TYPES = Set.of("Mono", "Flux", "CompletableFuture", "CompletionStage",
            "DeferredResult", "ListenableFuture", "Future", "WebAsyncTask");
    private static final Set<String> FUTURE_TYPES = Set.of("Future", "CompletableFuture", "ListenableFuture", "CompletionStage",
            "ForkJoinTask", "FutureTask", "ScheduledFuture");
    private static final Pattern STAND_IN = Pattern.compile("(Noop|NoOp|Fake|Mock|Stub|Dummy|Test|InMemory|Null|Disabled).*|.*(Stub|Mock|Fake)");

    /**
     * @param analysed        false when the entry method is not in the parsed sources (Kotlin, regex fallback, synthetic)
     * @param methods         method bodies read
     * @param files           files those bodies are in, entry file first
     * @param truncated       the walk stopped at the method budget or skipped implementations or overloads
     * @param budgetHit       the walk stopped at the method budget
     * @param unresolvedCalls calls whose receiver type the parse trees do not give
     * @param beyondReach     calls into project beans that the hop limit left unread
     * @param entryReturns    the simple return type of the entry method, such as {@code Mono}
     */
    public record Result(boolean analysed, List<Effect> effects, int methods, List<String> files, boolean truncated,
                         boolean budgetHit, int unresolvedCalls, int beyondReach, String entryReturns) {

        static final Result NOT_ANALYSED = new Result(false, List.of(), 0, List.of(), false, false, 0, 0, null);

        public Map<String, Object> toMap(String id) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("analysed", analysed);
            m.put("methods", methods);
            m.put("files", files);
            m.put("truncated", truncated);
            m.put("budgetHit", budgetHit);
            m.put("unresolvedCalls", unresolvedCalls);
            m.put("beyondReach", beyondReach);
            if (entryReturns != null) {
                m.put("entryReturns", entryReturns);
            }
            m.put("effects", effects.stream().map(Effect::toMap).toList());
            return m;
        }
    }

    private record Target(TypeDecl declaring, MethodDecl method) {
        String symbol() {
            return declaring.displayName + "." + method.name();
        }
    }

    private record TxAnno(TxMode mode, String detail, int line) {
    }

    /** A field's declared type and the type that declares it, whose file resolves the type name. */
    private record FieldRef(TypeDecl owner, String type) {
    }

    /**
     * What a call's receiver is. {@code dependency} for a field or a static call; a local or a
     * parameter of a project class is usually a domain object.
     */
    private record Receiver(String typeText, TypeDecl context, boolean dependency, boolean field) {
    }

    private final Index index;
    private final Annotations an;
    private final Evaluator ev;
    private final java.util.function.Predicate<String> springSession;
    private final boolean springSessionAnywhere;
    private final Map<MethodDecl, TxAnno> txCache = new IdentityHashMap<>();
    private final Map<MethodDecl, TxAnno> methodTxCache = new IdentityHashMap<>();
    private final Map<TypeDecl, List<TypeDecl>> implCache = new IdentityHashMap<>();
    private final Map<TypeDecl, Boolean> repositoryCache = new IdentityHashMap<>();

    /**
     * {@code springSession} tells, per build module, whether Spring Session is declared: HTTP
     * session attributes are then stored externally.
     */
    public EffectAnalyzer(Index index, java.util.function.Predicate<String> springSession, boolean springSessionAnywhere) {
        this.index = index;
        this.springSessionAnywhere = springSessionAnywhere;
        this.an = new Annotations(index);
        this.ev = new Evaluator(index);
        this.springSession = springSession;
    }

    public Result analyse(EntryPoint ep) {
        TypeDecl owner = index.byFqn(ep.cls, ep.file);
        if (owner == null) {
            return Result.NOT_ANALYSED;
        }
        Target entry = entryMethod(owner, ep);
        if (entry == null) {
            return Result.NOT_ANALYSED;
        }
        Walk w = new Walk(owner.file.module());
        String symbol = entry.symbol();
        String file = entry.declaring().file.relPath();
        boolean viaProxy = !(ep.kind.equals("LIFECYCLE") && INIT_CALLBACKS.contains(ep.subkind));
        if (!viaProxy) {
            TxAnno t = transactional(entry);
            if (t != null && t.mode() == TxMode.OPENS) {
                w.add(Signals.SELF_CALL, "@Transactional on an init callback is ignored: Spring calls it on the raw bean",
                        file, t.line(), symbol, 0, Tx.NONE);
            }
        }
        if (Boolean.TRUE.equals(ep.attrs.get("async")) || async(entry)) {
            w.add(Signals.ASYNC, "entry method runs on an executor (@Async)", file, asyncLine(entry), symbol, 0, Tx.NONE);
        }
        if (ep.attrs.get("schedulerLock") != null) {
            w.add(Signals.LOCK, "@SchedulerLock", file, annoLine(entry, "SchedulerLock"), symbol, 0, Tx.NONE);
        }
        if (Boolean.TRUE.equals(ep.attrs.get("disallowConcurrent"))) {
            w.add(Signals.LOCK, "@DisallowConcurrentExecution", ep.file, ep.line, symbol, 0, Tx.NONE);
        }
        if (ep.attrs.get("retryableTopic") != null) {
            w.add(Signals.RETRY, "@RetryableTopic", file, annoLine(entry, "RetryableTopic"), symbol, 0, Tx.NONE);
        }
        w.method(owner, entry, 0, Tx.NONE, viaProxy, !isScoped(owner));
        String returns = entry.method().returnType() == null ? null : JavaSourceParser.simpleTypeName(entry.method().returnType());
        return w.result(returns);
    }

    private Target entryMethod(TypeDecl owner, EntryPoint ep) {
        Target best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (TypeDecl x : index.linearize(owner)) {
            for (MethodDecl m : x.methods) {
                if (!m.name().equals(ep.method) || !m.hasBody()) {
                    continue;
                }
                int distance = x.file.relPath().equals(ep.file) ? Math.abs(m.line() - ep.line) : 100_000;
                if (distance < bestDistance) {
                    best = new Target(x, m);
                    bestDistance = distance;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    private final class Walk {
        private final List<Effect> effects = new ArrayList<>();
        private final Map<String, Integer> seen = new HashMap<>();
        /** Methods whose returned future the caller waits for. */
        private final Set<MethodDecl> resultWaited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean budgetHit;
        /** Per method, per (transaction, proxy, shared) context, the lowest hop it was read at. */
        private final Map<MethodDecl, Map<String, Integer>> visits = new IdentityHashMap<>();
        private final Map<MethodDecl, Map<String, Deferral>> deferrals = new IdentityHashMap<>();
        private final Set<String> files = new LinkedHashSet<>();
        private int methods;
        private boolean truncated;
        private int unresolved;
        private int beyondReach;
        private final String module;

        Walk(String module) {
            this.module = module;
        }

        Result result(String entryReturns) {
            return new Result(true, effects, methods, new ArrayList<>(files), truncated, budgetHit, unresolved, beyondReach, entryReturns);
        }

        /** A second sighting of the same effect keeps the lowest hop and the strongest transaction, whatever the walk order. */
        void add(String signal, String detail, String file, int line, String symbol, int hop, Tx tx) {
            String key = signal + "|" + file + "|" + line + "|" + detail;
            Integer at = seen.get(key);
            if (at == null) {
                seen.put(key, effects.size());
                effects.add(new Effect(signal, detail, file, line, symbol, hop, tx == Tx.READ_WRITE, tx == Tx.READ_ONLY));
                return;
            }
            Effect old = effects.get(at);
            boolean rw = old.inTx() || tx == Tx.READ_WRITE;
            effects.set(at, new Effect(signal, detail, file, line, old.symbol(), Math.min(old.hop(), hop), rw,
                    !rw && (old.inReadOnlyTx() || tx == Tx.READ_ONLY)));
        }

        /**
         * Reads one method body. {@code viaProxy} is false for self-invocations and init callbacks,
         * which bypass Spring's proxies. {@code shared} is false inside an object the path created
         * or received, whose fields are not shared state.
         */
        void method(TypeDecl bean, Target t, int hop, Tx tx, boolean viaProxy, boolean shared) {
            Map<String, Integer> byContext = visits.computeIfAbsent(t.method(), k -> new HashMap<>());
            String context = tx + "|" + viaProxy + "|" + shared;
            Integer best = byContext.get(context);
            if (best != null && best <= hop) {
                return;
            }
            if (methods >= MAX_METHODS) {
                truncated = true;
                budgetHit = true;
                return;
            }
            methods++;
            byContext.put(context, hop);
            String file = t.declaring().file.relPath();
            files.add(file);
            String symbol = t.symbol();
            Tx inner = tx;
            if (viaProxy) {
                TxAnno declared = transactional(t);
                if (declared != null) {
                    switch (declared.mode()) {
                        case OPENS -> {
                            inner = Tx.READ_WRITE;
                            add(Signals.TX, declared.detail(), file, declared.line(), symbol, hop, inner);
                        }
                        case READ_ONLY -> inner = tx == Tx.NONE ? Tx.READ_ONLY : tx;
                        case SUSPENDS -> inner = Tx.NONE;
                        case JOINS -> { }
                    }
                }
                for (Anno a : t.method().annos()) {
                    switch (a.simpleName()) {
                        case "Retryable", "Retry", "CircuitBreaker" -> add(Signals.RETRY, "@" + a.simpleName(), file, a.line(), symbol, hop, inner);
                        case "SchedulerLock" -> add(Signals.LOCK, "@SchedulerLock", file, a.line(), symbol, hop, inner);
                        default -> { }
                    }
                }
            }
            MethodDecl.Body body = t.method().body();
            for (Site s : body.sites()) {
                if (s.encl() != null && deferralOf(bean, t, s.encl()) == Deferral.NEVER) {
                    continue;
                }
                site(bean, t, s, hop, siteTx(bean, t, s, inner), shared);
            }
            if (shared && !isSetup(t.method())) {
                for (Site a : body.assignments()) {
                    boolean bare = a.receiver() == null;
                    if (bare && (body.locals().containsKey(a.name()) || paramType(t.method(), a.name()) != null)) {
                        continue;
                    }
                    FieldRef field = fieldType(bean, t.declaring(), a.name());
                    if (field != null && storesData(field.type())) {
                        add(Signals.STATE, "assigns field " + a.name() + " (" + JavaSourceParser.simpleTypeName(field.type()) + ")",
                                file, a.line(), symbol, hop, siteTx(bean, t, a, inner));
                    }
                }
            }
        }

        /** Lambdas handed to executors and after-commit callbacks run outside the transaction; TransactionTemplate callbacks inside one. */
        private Tx siteTx(TypeDecl bean, Target caller, Site s, Tx tx) {
            if (s.encl() == null) {
                return tx;
            }
            return switch (deferralOf(bean, caller, s.encl())) {
                case ASYNC, AFTER_COMMIT, LATER, NEVER -> Tx.NONE;
                case TRANSACTION -> Tx.READ_WRITE;
                case INLINE -> tx;
            };
        }

        private Deferral deferralOf(TypeDecl bean, Target caller, String encl) {
            return deferrals.computeIfAbsent(caller.method(), k -> new HashMap<>())
                    .computeIfAbsent(encl, e -> deferral(bean, caller, e));
        }

        /** The innermost context that is not inline decides: a send in {@code forEach} inside {@code afterCommit} runs after the commit. */
        private Deferral deferral(TypeDecl bean, Target caller, String chain) {
            String[] parts = chain.split("\\|");
            for (int i = parts.length - 1; i >= 0; i--) {
                Deferral d = deferralOne(bean, caller, parts[i]);
                if (d != Deferral.INLINE) {
                    return d;
                }
            }
            return Deferral.INLINE;
        }

        private Deferral deferralOne(TypeDecl bean, Target caller, String encl) {
            if (encl.equals(Site.LATER)) {
                return Deferral.LATER;
            }
            if (encl.startsWith(Site.NEW) && encl.indexOf('.') < 0) {
                String type = encl.substring(Site.NEW.length());
                if (type.startsWith("TransactionSynchronization")) {
                    return Deferral.AFTER_COMMIT;
                }
                // Only an anonymous class that is not passed anywhere gets its own type as the context.
                return type.equals("Thread") || type.equals("TimerTask") ? Deferral.ASYNC : Deferral.NEVER;
            }
            int dot = encl.lastIndexOf('.');
            String recv = encl.substring(0, dot);
            String name = encl.substring(dot + 1);
            if (name.equals("registerSynchronization") || name.equals("afterCommit")) {
                return Deferral.AFTER_COMMIT;
            }
            if (recv.isEmpty()) {
                return name.equals("runAsync") || name.equals("supplyAsync") ? Deferral.ASYNC : Deferral.INLINE;
            }
            Receiver r = recv.startsWith(Site.NEW) ? new Receiver(recv.substring(Site.NEW.length()), caller.declaring(), false, false)
                    : receiver(bean, caller, recv);
            if (r == null) {
                return Deferral.INLINE;
            }
            String simple = JavaSourceParser.simpleTypeName(r.typeText());
            boolean future = simple.equals("CompletableFuture") || simple.equals("CompletionStage");
            if (future && (name.startsWith("then") || name.startsWith("when") || name.equals("handle") || name.equals("exceptionally"))) {
                return Deferral.ASYNC;
            }
            Signals.Call c = Signals.library(simple, name);
            if (c != null && c.signal().equals(Signals.ASYNC)) {
                return Deferral.ASYNC;
            }
            if (c != null && c.signal().equals(Signals.TX)) {
                return Deferral.TRANSACTION;
            }
            if (name.endsWith("Async") && (simple.equals("CompletableFuture") || simple.equals("CompletionStage"))) {
                return Deferral.ASYNC;
            }
            boolean executorLike = simple.endsWith("Executor") || simple.endsWith("ExecutorService") || simple.endsWith("Scheduler");
            if (executorLike && (Signals.EXECUTOR_METHODS.contains(name) || Signals.TIMER_METHODS.contains(name))) {
                return Deferral.ASYNC;
            }
            return Deferral.INLINE;
        }

        private void site(TypeDecl bean, Target caller, Site s, int hop, Tx tx, boolean shared) {
            String file = caller.declaring().file.relPath();
            String symbol = caller.symbol();
            String receiver = s.receiver();
            if (Site.NEW.equals(receiver)) {
                if (s.name().equals("Thread")) {
                    add(Signals.ASYNC, "new Thread" + (threadAwaited(caller, s) ? Signals.WAITED : ""), file, s.line(), symbol, hop, tx);
                } else if (Signals.FILE_WRITERS.contains(s.name()) && !usesTempFiles(caller.method())) {
                    add(Signals.LOCAL_FILE, "local disk new " + s.name(), file, s.line(), symbol, hop, tx);
                }
                return;
            }
            if (receiver != null && receiver.startsWith(Site.NEW)) {
                String type = receiver.substring(Site.NEW.length());
                if (!type.equals("Thread")) {
                    callOn(bean, caller, s, hop, tx, shared, new Receiver(type, caller.declaring(), false, false));
                }
                return;
            }
            if (receiver == null || receiver.equals("this") || receiver.equals("super")) {
                if (!s.chained()) {
                    selfCall(bean, caller, s, hop, tx, shared, "super".equals(receiver));
                }
                return;
            }
            Receiver r = receiver(bean, caller, receiver);
            if (r == null) {
                unresolved++;
                return;
            }
            if (s.args() < 0 && !r.field() && r.dependency()) {
                // Invoice::markPaid on a list element: the object is the element, not a shared bean.
                r = new Receiver(r.typeText(), r.context(), false, false);
            }
            callOn(bean, caller, s, hop, tx, shared, r);
        }

        private void callOn(TypeDecl bean, Target caller, Site s, int hop, Tx tx, boolean shared, Receiver r) {
            String file = caller.declaring().file.relPath();
            String symbol = caller.symbol();
            String name = s.name();
            String simple = JavaSourceParser.simpleTypeName(r.typeText());
            if (r.field() && shared && !s.chained() && !caller.method().isConstructor()
                    && (Signals.STATE_TYPES.contains(simple) || Signals.CACHE_TYPES.contains(simple))
                    && Signals.STATE_MUTATORS.contains(name)) {
                add(Signals.STATE, s.receiver() + "." + name + " (" + simple
                        + (Signals.CACHE_TYPES.contains(simple) ? ", cache" : "") + ")", file, s.line(), symbol, hop, tx);
                return;
            }
            boolean sessionWrite = (name.equals("setAttribute") || name.equals("removeAttribute"))
                    && (simple.equals("HttpSession") || s.chained() && (simple.equals("HttpServletRequest") || simple.equals("ServletRequest")));
            if (sessionWrite && !springSession.test(module)) {
                add(Signals.STATE, "HTTP session HttpSession." + name + " (server memory"
                        + (springSessionAnywhere ? ", Spring Session is declared in another module)" : ", no Spring Session)"),
                        file, s.line(), symbol, hop, tx);
                return;
            }
            Index.Resolved resolved = index.resolveType(Index.stripGenerics(r.typeText()), r.context(), r.context().file, null);
            TypeDecl p = resolved == null || resolved.type().kind == TypeDecl.Kind.ANNOTATION ? null : resolved.type();
            if (p != null && isFrameworkCopy(p) && Signals.knownLibraryType(simple)) {
                p = null;
            }
            if (p != null) {
                projectCall(bean, p, caller, s, hop, tx, shared, r);
                return;
            }
            Signals.Call call = Signals.library(simple, name);
            if (call != null && call.signal().equals(Signals.LOCAL_FILE) && usesTempFiles(caller.method())) {
                return;
            }
            if (call != null) {
                String detail = call.detail();
                if (call.signal().equals(Signals.ASYNC)) {
                    if (waited(caller, s) || s.returned() && resultWaited.contains(caller.method())) {
                        detail += Signals.WAITED;
                    } else if (s.returned() || returnsAsyncResult(caller.method()) && ASYNC_STARTERS.contains(name)) {
                        detail += Signals.RETURNED;
                    }
                }
                String eventType = s.argType() != null ? s.argType() : variableType(bean, caller, s.argHints());
                if (call.signal().equals(Signals.EVENT) && eventType != null) {
                    detail += "(" + eventType + ")";
                }
                add(call.signal(), detail, file, s.line(), symbol, hop, tx);
                return;
            }
            if (s.chained() && (r.typeText().startsWith("WebClient") || r.typeText().startsWith("RestClient")) && Signals.HTTP_VERBS.contains(name)) {
                add(Signals.EXTERNAL, "HTTP " + JavaSourceParser.simpleTypeName(r.typeText().split("\\.")[0]) + "." + name,
                        file, s.line(), symbol, hop, tx);
                return;
            }
            if (simple.equals("Thread") && name.equals("start")) {
                boolean createdHere = caller.method().body().sites().stream()
                        .anyMatch(x -> Site.NEW.equals(x.receiver()) && x.name().equals("Thread"));
                if (!createdHere) {
                    add(Signals.ASYNC, "Thread.start" + (threadAwaited(caller, s) ? Signals.WAITED : ""), file, s.line(), symbol, hop, tx);
                }
                return;
            }
            String sdk = sdkPackage(r, simple);
            boolean awsRead = "software.amazon.awssdk".equals(sdk) && simple.endsWith("Client") && Signals.AWS_READ.matcher(name).matches();
            boolean factory = name.equals("create") && s.args() == 0 && r.dependency() && !r.field() || simple.contains("Template") && sdk != null;
            if (sdk != null && !factory && (awsRead || Signals.SDK_REMOTE_VERB.matcher(name).matches())
                    && !Signals.knownLibraryType(simple)) {
                add(Signals.EXTERNAL, "SDK " + sdk + " " + simple + "." + name, file, s.line(), symbol, hop, tx);
                return;
            }
            // A known library type's other methods (receive, delete, ack) are not effects the rules weigh.
            if (s.chained() || Signals.NOT_REMOTE.matcher(name).matches() || Signals.knownLibraryType(simple)) {
                return;
            }
            if (sdk == null && !simple.endsWith("HttpClient")
                    && (simple.endsWith("Client") || simple.endsWith("Stub") || simple.endsWith("Port") || simple.endsWith("PortType"))) {
                add(Signals.EXTERNAL, simple + "." + name + " (by type name)", file, s.line(), symbol, hop, tx);
            }
        }

        private void selfCall(TypeDecl bean, Target caller, Site s, int hop, Tx tx, boolean shared, boolean isSuper) {
            TypeDecl start = isSuper ? index.superclass(caller.declaring()) : bean;
            if (start == null) {
                return;
            }
            for (Target t : capped(pickOverloads(targets(start, s.name(), s.args()), s, bean, caller))) {
                if (t.method() == caller.method()) {
                    continue;
                }
                String file = caller.declaring().file.relPath();
                if (!t.method().isStatic() && !t.method().isPrivate()) {
                    if (an.has(t.method().annos(), Evaluator.Ctx.of(t.declaring()), "Async")) {
                        add(Signals.SELF_CALL, "@Async on " + t.symbol() + " is bypassed: called through this, runs synchronously",
                                file, s.line(), caller.symbol(), hop, tx);
                    }
                    TxAnno own = methodTx(t);
                    if (own != null && own.mode() == TxMode.OPENS && (tx == Tx.NONE || own.detail().contains("REQUIRES_NEW"))) {
                        add(Signals.SELF_CALL, own.detail() + " on " + t.symbol() + " is bypassed: called through this, "
                                + (tx == Tx.NONE ? "no transaction" : "no new transaction"), file, s.line(), caller.symbol(), hop, tx);
                    }
                }
                if (resultAwaited(caller, s, t)) {
                    resultWaited.add(t.method());
                }
                method(bean, t, hop, tx, false, shared);
            }
        }

        private void projectCall(TypeDecl bean, TypeDecl p, Target caller, Site s, int hop, Tx tx, boolean shared, Receiver r) {
            String file = caller.declaring().file.relPath();
            String symbol = caller.symbol();
            String name = s.name();
            if (isRepository(p) && (p.kind == TypeDecl.Kind.INTERFACE || hop >= MAX_BEAN_HOPS)) {
                repositoryCall(p, name, file, s.line(), symbol, hop, tx);
                return;
            }
            if (isHttpClientInterface(p)) {
                add(Signals.EXTERNAL, "HTTP client " + p.simpleName + "." + name, file, s.line(), symbol, hop, tx);
                return;
            }
            if (isSoapPort(p)) {
                add(Signals.EXTERNAL, "SOAP port " + p.simpleName + "." + name, file, s.line(), symbol, hop, tx);
                return;
            }
            if (p.file.origin() == Origin.GENERATED && (p.simpleName.endsWith("Api") || p.simpleName.endsWith("Client"))) {
                add(Signals.EXTERNAL, "generated client " + p.simpleName + "." + name, file, s.line(), symbol, hop, tx);
                return;
            }
            boolean singleton = s.chained() && r.dependency() && !r.field() && declaresSingletonAccessor(p);
            if (s.chained() && !singleton || isDataType(p)) {
                return;
            }
            if (hop >= MAX_BEAN_HOPS) {
                beyondReach++;
                return;
            }
            // A local or parameter is this call's own object; a prototype bean is a fresh one per lookup.
            boolean childShared = shared && r.dependency() && !isPrototype(p);
            for (TypeDecl impl : capped(implementations(p))) {
                for (Target t : capped(pickOverloads(targets(impl, name, s.args()), s, bean, caller))) {
                    boolean async = async(t);
                    if (resultAwaited(caller, s, t)) {
                        resultWaited.add(t.method());
                    }
                    if (async) {
                        String detail = "@Async " + t.symbol() + (waited(caller, s) && returnsFuture(t) ? Signals.WAITED : "");
                        add(Signals.ASYNC, detail, t.declaring().file.relPath(), asyncLine(t), t.symbol(), hop + 1, tx);
                    }
                    method(impl, t, hop + 1, async ? Tx.NONE : tx, true, childShared);
                }
            }
        }

        private void repositoryCall(TypeDecl repo, String name, String file, int line, String symbol, int hop, Tx tx) {
            MethodDecl decl = null;
            for (TypeDecl x : index.linearize(repo)) {
                for (MethodDecl md : x.methods) {
                    if (decl == null && md.name().equals(name)) {
                        decl = md;
                    }
                }
            }
            boolean rowLock = decl != null && decl.annos().stream().anyMatch(a -> a.simpleName().equals("Lock")
                    || a.simpleName().equals("Query") && lockingSql(a.text()));
            if (rowLock || Signals.LOCK_METHOD.matcher(name).matches()) {
                add(Signals.LOCK, "row lock " + repo.simpleName + "." + name, file, line, symbol, hop, tx);
            }
            boolean dedupStore = Signals.DEDUP_STORE.matcher(repo.simpleName).matches();
            if (Signals.IDEMPOTENCY_LOOKUP.matcher(name).matches()
                    || dedupStore && (name.startsWith("exists") || name.startsWith("find") || name.startsWith("count"))) {
                add(Signals.IDEMPOTENCY, repo.simpleName + "." + name, file, line, symbol, hop, tx);
            }
            boolean write = Signals.REPOSITORY_WRITE.matcher(name).matches()
                    || decl != null && decl.annos().stream().anyMatch(a -> a.simpleName().equals("Modifying"));
            if (write) {
                boolean outbox = repo.simpleName.toLowerCase(Locale.ROOT).contains("outbox");
                add(Signals.DB_WRITE, (outbox ? "outbox " : "") + repo.simpleName + "." + name, file, line, symbol, hop, tx);
            }
        }

        /**
         * Whether the async call at {@code s} is joined before the method returns: chained
         * {@code .get()}/{@code .join()} on the same call, or a future local joined later.
         */
        private boolean waited(Target caller, Site s) {
            List<Site> sites = caller.method().body().sites();
            for (Site o : sites) {
                if (o.chained() && o.line() == s.line() && Objects.equals(o.receiver(), s.receiver())
                        && (o.name().equals("join") || o.name().equals("get"))) {
                    return true;
                }
            }
            if (s.name().equals("execute")) {
                return false;
            }
            Map<String, String> locals = caller.method().body().locals();
            for (Site o : sites) {
                if (o.line() < s.line() || o.chained() || o.receiver() == null) {
                    continue;
                }
                boolean joins = o.name().equals("join") || o.name().equals("get");
                if (o.receiver().equals("CompletableFuture") && o.name().equals("allOf")
                        || o.args() < 0 && joins && FUTURE_TYPES.contains(o.receiver())) {
                    return true;
                }
                String type = locals.get(o.receiver());
                if (joins && type != null && (FUTURE_TYPES.contains(JavaSourceParser.simpleTypeName(type))
                        || type.startsWith(Site.RESULT) && ASYNC_STARTERS.contains(type.substring(Site.RESULT.length())))) {
                    return true;
                }
            }
            return false;
        }

        /** A thread variable joined in the method, directly or through a {@code join(thread)} helper: the thread is waited for. */
        private boolean threadJoined(Target caller) {
            Map<String, String> locals = caller.method().body().locals();
            return caller.method().body().sites().stream().anyMatch(o -> o.name().equals("join") && !o.chained()
                    && isThread(locals, o.receiver() != null ? o.receiver() : firstVariable(o.argHints())));
        }

        private boolean threadAwaited(Target caller, Site s) {
            return threadJoined(caller) || s.returned() && resultWaited.contains(caller.method());
        }

        /** The future or thread that {@code t} returns is waited for by the caller at {@code s}. */
        private boolean resultAwaited(Target caller, Site s, Target t) {
            return returnsFuture(t) && waited(caller, s) || returnsThread(t) && threadJoined(caller);
        }

        /** The declared type of the first argument when it is a plain variable ({@code v:name} hint). */
        private String variableType(TypeDecl bean, Target caller, String hints) {
            String name = firstVariable(hints);
            if (name == null) {
                return null;
            }
            Receiver r = receiver(bean, caller, name);
            return r == null ? null : JavaSourceParser.simpleTypeName(r.typeText());
        }

        private <T> List<T> capped(List<T> all) {
            if (all.size() <= MAX_IMPLEMENTATIONS) {
                return all;
            }
            truncated = true;
            return all.subList(0, MAX_IMPLEMENTATIONS);
        }
    }

    private Receiver receiver(TypeDecl bean, Target caller, String name) {
        MethodDecl m = caller.method();
        if (name.startsWith(Site.CALL)) {
            // getExecutor().submit(..): the type is what the bean's own no-argument method returns.
            for (Target t : targets(bean, name.substring(Site.CALL.length()), 0)) {
                String r = t.method().returnType();
                if (r != null && !r.equals("void")) {
                    return new Receiver(r, t.declaring(), true, false);
                }
            }
            return null;
        }
        String local = m.body().locals().get(name);
        if (local != null) {
            return local.isEmpty() || local.startsWith(Site.RESULT) ? null : new Receiver(local, caller.declaring(), false, false);
        }
        String param = paramType(m, name);
        if (param != null) {
            return new Receiver(param, caller.declaring(), false, false);
        }
        FieldRef f = fieldType(bean, caller.declaring(), name);
        if (f != null) {
            return new Receiver(f.type(), f.owner(), true, true);
        }
        String st = staticTypeName(name);
        return st == null ? null : new Receiver(st, caller.declaring(), true, false);
    }

    /**
     * Among same-arity overloads, the ones whose parameter types best match what the call passes:
     * a variable's declared type, a {@code new} type, or a call named after the type ({@code toOrderDto()}).
     */
    private List<Target> pickOverloads(List<Target> found, Site s, TypeDecl bean, Target caller) {
        if (found.size() < 2 || s.argHints() == null) {
            return found;
        }
        String[] hints = s.argHints().split(",");
        int best = Integer.MIN_VALUE;
        List<Target> out = new ArrayList<>();
        for (Target t : found) {
            int score = 0;
            List<MethodDecl.Param> ps = t.method().params();
            for (int i = 0; i < Math.min(hints.length, ps.size()); i++) {
                String declared = JavaSourceParser.simpleTypeName(ps.get(i).type());
                if (declared.length() < 3) {
                    continue;
                }
                String h = hints[i];
                String passed = null;
                if (h.startsWith("v:")) {
                    Receiver r = receiver(bean, caller, h.substring(2));
                    passed = r == null ? null : JavaSourceParser.simpleTypeName(r.typeText());
                } else if (h.startsWith("n:")) {
                    passed = h.substring(2);
                } else if (h.equals("s")) {
                    passed = "String";
                }
                if (passed != null) {
                    score += passed.equals(declared) ? 2 : -1;
                } else if (h.startsWith("c:") && h.substring(2).toLowerCase(Locale.ROOT).contains(declared.toLowerCase(Locale.ROOT))) {
                    score += 1;
                }
            }
            if (score > best) {
                best = score;
                out.clear();
                out.add(t);
            } else if (score == best) {
                out.add(t);
            }
        }
        return out;
    }

    /** Overloads are told apart by argument count; a method reference matches any. */
    private List<Target> targets(TypeDecl type, String name, int args) {
        for (TypeDecl x : index.linearize(type)) {
            List<Target> found = new ArrayList<>();
            for (MethodDecl m : x.methods) {
                if (m.name().equals(name) && m.hasBody() && arityMatches(m, args)) {
                    found.add(new Target(x, m));
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return List.of();
    }

    private static boolean arityMatches(MethodDecl m, int args) {
        if (args < 0) {
            return true;
        }
        int n = m.params().size();
        boolean varargs = n > 0 && m.params().get(n - 1).type().endsWith("...");
        return n == args || varargs && args >= n - 1;
    }

    /** Concrete implementations, beans first and stand-ins (Noop, Fake, Mock...) last. */
    private List<TypeDecl> implementations(TypeDecl p) {
        return implCache.computeIfAbsent(p, k -> {
            if (k.kind == TypeDecl.Kind.CLASS && !k.isAbstract || k.kind == TypeDecl.Kind.RECORD || k.kind == TypeDecl.Kind.ENUM) {
                return List.of(k);
            }
            List<TypeDecl> out = new ArrayList<>();
            Set<TypeDecl> seen = new LinkedHashSet<>();
            Deque<TypeDecl> queue = new ArrayDeque<>(index.subtypes(k));
            while (!queue.isEmpty()) {
                TypeDecl s = queue.poll();
                if (!seen.add(s)) {
                    continue;
                }
                if (s.kind == TypeDecl.Kind.CLASS && !s.isAbstract || s.kind == TypeDecl.Kind.RECORD) {
                    out.add(s);
                }
                queue.addAll(index.subtypes(s));
            }
            out.sort(java.util.Comparator.comparingInt((TypeDecl t) -> STAND_IN.matcher(t.simpleName).matches() ? 2 : isBeanLike(t) ? 0 : 1));
            return out;
        });
    }

    private boolean isRepository(TypeDecl t) {
        return repositoryCache.computeIfAbsent(t, k -> {
            String n = k.simpleName;
            if (n.endsWith("Repository") || n.endsWith("Repo") || n.endsWith("Dao") || n.endsWith("DAO")) {
                return true;
            }
            if (an.has(k.annos, Evaluator.Ctx.of(k), "Repository")) {
                return true;
            }
            if (k.annos.stream().anyMatch(a -> a.simpleName().equals("Mapper")) && k.file.importsFrom("org.apache.ibatis")) {
                return true;
            }
            for (TypeDecl x : index.linearize(k)) {
                for (String s : index.unresolvedSupertypes(x)) {
                    if (Signals.REPOSITORY_PARENTS.contains(s)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static boolean lockingSql(String annotationText) {
        String q = annotationText.toLowerCase(Locale.ROOT);
        return q.contains("for update") || q.contains("skip locked") || q.contains("advisory_lock") || q.contains("advisory_xact_lock");
    }

    private static boolean isDataType(TypeDecl p) {
        return p.kind == TypeDecl.Kind.RECORD || p.kind == TypeDecl.Kind.ENUM
                || p.annos.stream().anyMatch(a -> DATA_ANNOTATIONS.contains(a.simpleName()));
    }

    /** Request, session and prototype beans are not singletons: their fields are not shared between callers. */
    private static boolean isScoped(TypeDecl t) {
        for (Anno a : t.annos) {
            String n = a.simpleName();
            if (n.equals("RequestScope") || n.equals("SessionScope") || n.equals("Scope")
                    && a.text().toLowerCase(Locale.ROOT).matches(".*(request|session|prototype).*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrototype(TypeDecl p) {
        for (Anno a : p.annos) {
            if (a.simpleName().equals("Scope") && a.text().toLowerCase(Locale.ROOT).contains("prototype")) {
                return true;
            }
        }
        return false;
    }

    private boolean isBeanLike(TypeDecl p) {
        if (p.kind == TypeDecl.Kind.INTERFACE) {
            return true;
        }
        Evaluator.Ctx ctx = Evaluator.Ctx.of(p);
        for (String base : BEAN_ANNOTATIONS) {
            if (an.has(p.annos, ctx, base)) {
                return true;
            }
        }
        return false;
    }

    /** A Spring or JDK class copied into the sources still behaves like the library class. */
    private static boolean isFrameworkCopy(TypeDecl p) {
        String pkg = p.packageName();
        return pkg.startsWith("org.springframework.") || pkg.startsWith("java.") || pkg.startsWith("javax.") || pkg.startsWith("jakarta.");
    }

    private static boolean declaresSingletonAccessor(TypeDecl p) {
        for (MethodDecl m : p.methods) {
            if (m.isStatic() && SINGLETON_ACCESSORS.contains(m.name())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHttpClientInterface(TypeDecl p) {
        if (p.kind != TypeDecl.Kind.INTERFACE) {
            return false;
        }
        if (p.annos.stream().anyMatch(a -> HTTP_CLIENT_ANNOTATIONS.contains(a.simpleName()))) {
            return true;
        }
        if (p.file.importsFrom("retrofit2")) {
            return true;
        }
        for (MethodDecl m : p.methods) {
            if (m.annos().stream().anyMatch(a -> HTTP_CLIENT_ANNOTATIONS.contains(a.simpleName()))) {
                return true;
            }
        }
        return false;
    }

    /** A JAX-WS service interface with no implementation in the sources: calling it calls the remote service. */
    private boolean isSoapPort(TypeDecl p) {
        if (p.kind != TypeDecl.Kind.INTERFACE || !implementations(p).isEmpty()) {
            return false;
        }
        boolean webService = p.annos.stream().anyMatch(a -> a.simpleName().equals("WebService"));
        String n = p.simpleName;
        return webService || p.file.origin() == Origin.GENERATED && (n.endsWith("Port") || n.endsWith("PortType"));
    }

    /** The SDK package a type is imported from, or null. */
    private static String sdkPackage(Receiver r, String simple) {
        String text = Index.stripGenerics(r.typeText());
        for (String prefix : Signals.SDK_PACKAGES) {
            if (text.startsWith(prefix)) {
                return prefix.substring(0, prefix.length() - 1);
            }
        }
        for (String imp : r.context().file.imports()) {
            if (imp.endsWith("." + simple)) {
                for (String prefix : Signals.SDK_PACKAGES) {
                    if (imp.startsWith(prefix)) {
                        return prefix.substring(0, prefix.length() - 1);
                    }
                }
                return null;
            }
        }
        return null;
    }

    /** {@code @Transactional} on the method, else on its declaring type. */
    private TxAnno transactional(Target t) {
        return txCache.computeIfAbsent(t.method(), k -> {
            TxAnno own = methodTx(t);
            if (own != null) {
                return own;
            }
            Effective e = an.first(t.declaring().annos, Evaluator.Ctx.of(t.declaring()), "Transactional");
            return e == null ? null : txAnno(e);
        });
    }

    private TxAnno methodTx(Target t) {
        if (methodTxCache.containsKey(t.method())) {
            return methodTxCache.get(t.method());
        }
        Effective e = an.first(t.method().annos(), Evaluator.Ctx.of(t.declaring()), "Transactional");
        TxAnno result = e == null ? null : txAnno(e);
        methodTxCache.put(t.method(), result);
        return result;
    }

    private TxAnno txAnno(Effective e) {
        int line = e.useSite().line();
        Annotations.Arg readOnly = e.attr("readOnly");
        Annotations.Arg propagation = e.attr("propagation", "value");
        String p = propagation == null ? null : Evaluator.enumName(propagation.expr());
        if ("NOT_SUPPORTED".equals(p) || "NEVER".equals(p)) {
            return new TxAnno(TxMode.SUSPENDS, "@Transactional(" + p + ")", line);
        }
        if ("SUPPORTS".equals(p) || "MANDATORY".equals(p)) {
            return new TxAnno(TxMode.JOINS, "@Transactional(" + p + ")", line);
        }
        if (readOnly != null && "true".equals(ev.eval(readOnly.expr(), readOnly.ctx()).text())) {
            return new TxAnno(TxMode.READ_ONLY, "@Transactional(readOnly = true)", line);
        }
        return new TxAnno(TxMode.OPENS, "@Transactional" + (p != null && !p.equals("REQUIRED") ? "(" + p + ")" : ""), line);
    }

    private boolean async(Target t) {
        return an.has(t.method().annos(), Evaluator.Ctx.of(t.declaring()), "Async")
                || an.has(t.declaring().annos, Evaluator.Ctx.of(t.declaring()), "Async");
    }

    /** {@code Mono.fromFuture(future)} and the like: a method of this return type hands its futures back to the caller. */
    private static boolean returnsAsyncResult(MethodDecl m) {
        String r = m.returnType();
        return r != null && ASYNC_RESULT_TYPES.contains(JavaSourceParser.simpleTypeName(r));
    }

    private static boolean returnsThread(Target t) {
        return "Thread".equals(t.method().returnType());
    }

    private static boolean isThread(Map<String, String> locals, String name) {
        return name != null && "Thread".equals(JavaSourceParser.simpleTypeName(locals.getOrDefault(name, "")));
    }

    private static String firstVariable(String hints) {
        if (hints == null || !hints.startsWith("v:")) {
            return null;
        }
        int comma = hints.indexOf(',');
        return hints.substring(2, comma < 0 ? hints.length() : comma);
    }

    private static boolean returnsFuture(Target t) {
        String r = t.method().returnType();
        return r != null && FUTURE_TYPES.contains(JavaSourceParser.simpleTypeName(r));
    }

    /** Evidence for an annotation-driven effect points at the annotation, not at the start of the declaration. */
    private int asyncLine(Target t) {
        Evaluator.Ctx ctx = Evaluator.Ctx.of(t.declaring());
        Effective e = an.first(t.method().annos(), ctx, "Async");
        if (e == null) {
            e = an.first(t.declaring().annos, ctx, "Async");
        }
        return e == null ? t.method().line() : e.useSite().line();
    }

    private static int annoLine(Target t, String simpleName) {
        for (Anno a : t.method().annos()) {
            if (a.simpleName().equals(simpleName)) {
                return a.line();
            }
        }
        return t.method().line();
    }

    /** Scratch files: the method creates a temp file or reads the temp directory, so what it writes is not stored. */
    private static boolean usesTempFiles(MethodDecl m) {
        Map<String, Integer> calls = m.calls();
        return calls.containsKey("createTempFile") || calls.containsKey("createTempDirectory") || calls.containsKey("deleteOnExit");
    }

    private static boolean storesData(String fieldType) {
        String simple = JavaSourceParser.simpleTypeName(fieldType);
        return fieldType.contains("[]") || Signals.VALUE_TYPES.contains(simple) || Signals.STATE_TYPES.contains(simple);
    }

    private static boolean isSetup(MethodDecl m) {
        if (m.isConstructor() || SETUP_METHODS.contains(m.name())) {
            return true;
        }
        String n = m.name();
        if (n.length() > 3 && n.startsWith("set") && Character.isUpperCase(n.charAt(3)) && m.params().size() == 1) {
            return true;
        }
        return m.annos().stream().anyMatch(a -> SETUP_ANNOTATIONS.contains(a.simpleName()));
    }

    private static String paramType(MethodDecl m, String name) {
        for (MethodDecl.Param p : m.params()) {
            if (p.name().equals(name)) {
                return p.type();
            }
        }
        return null;
    }

    /**
     * A field of the declaring type's hierarchy, its enclosing types, else the bean's. The owner
     * is returned with the type text because the type name resolves in the owner's file.
     */
    private FieldRef fieldType(TypeDecl bean, TypeDecl declaring, String name) {
        for (TypeDecl x : index.linearize(declaring)) {
            String t = x.fieldTypes.get(name);
            if (t != null) {
                return new FieldRef(x, t);
            }
        }
        for (TypeDecl c = declaring.enclosing; c != null; c = c.enclosing) {
            String t = c.fieldTypes.get(name);
            if (t != null) {
                return new FieldRef(c, t);
            }
        }
        if (bean != declaring) {
            for (TypeDecl x : index.linearize(bean)) {
                String t = x.fieldTypes.get(name);
                if (t != null) {
                    return new FieldRef(x, t);
                }
            }
        }
        return null;
    }

    /** {@code Executors} or {@code java.util.concurrent.CompletableFuture} used as a static receiver. */
    private static String staticTypeName(String receiver) {
        int dot = receiver.lastIndexOf('.');
        String last = dot < 0 ? receiver : receiver.substring(dot + 1);
        if (last.isEmpty() || !Character.isUpperCase(last.charAt(0))) {
            return null;
        }
        if (dot >= 0 && !Character.isLowerCase(receiver.charAt(0))) {
            return null;
        }
        if (last.chars().allMatch(c -> Character.isUpperCase(c) || c == '_' || Character.isDigit(c)) && last.length() > 1) {
            return null;
        }
        return dot < 0 ? last : receiver;
    }

    /** Counts of operations per signal, for the scan summary. */
    public static Map<String, Integer> census(List<Result> results) {
        Map<String, Integer> counts = new HashMap<>();
        for (Result r : results) {
            Set<String> signals = new TreeSet<>();
            for (Effect e : r.effects()) {
                signals.add(e.signal());
            }
            signals.forEach(s -> counts.merge(s, 1, Integer::sum));
        }
        return counts;
    }
}
