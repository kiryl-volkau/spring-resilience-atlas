package studio.volkoff.atlas.effects;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.MethodDecl.Site;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How the HTTP clients and executors a path calls through are built, and what that construction
 * leaves unbounded. By declaration only: the field's own initialiser or a constructor that builds
 * it, else the {@code @Bean} methods of that type in the module (the whole repository when the
 * module has none), picked by qualifier or name, or the only one. Several candidates that do not
 * all share the problem resolve to nothing.
 */
final class Construction {

    /** Where the client or executor is built and what it lacks, null when nothing is missing. */
    record Built(String file, int line, String symbol, String problem) {
    }

    private record Candidate(Built built, Set<String> names) {
    }

    private static final Map<String, String> FAMILY = new HashMap<>();

    static {
        for (String t : List.of("RestTemplate", "RestOperations", "OAuth2RestTemplate")) {
            FAMILY.put(t, "RestTemplate");
        }
        FAMILY.put("WebClient", "WebClient");
        FAMILY.put("RestClient", "RestClient");
        for (String t : List.of("Executor", "ExecutorService", "ThreadPoolExecutor", "TaskExecutor", "AsyncTaskExecutor",
                "AsyncListenableTaskExecutor", "ThreadPoolTaskExecutor", "SimpleAsyncTaskExecutor", "ListeningExecutorService")) {
            FAMILY.put(t, "Executor");
        }
    }

    /** Calls and types that set timeouts, or hand the client a request factory or connector built elsewhere. */
    private static final Pattern TIMEOUTS = Pattern.compile("(?i)(set)?(connect|read|response|socket|connectionRequest|request)Timeout\\w*"
            + "|ReadTimeoutHandler|WriteTimeoutHandler|CONNECT_TIMEOUT_MILLIS|RequestConfig|requestFactory|setRequestFactory"
            + "|clientConnector|ClientHttpRequestFactorySettings|ClientHttpRequestFactoryBuilder|HttpClientSettings");
    private static final Pattern BEAN_NAME = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern UNBOUNDED_QUEUE_INIT = Pattern.compile("Executors\\s*\\.\\s*(newFixedThreadPool|newSingleThreadExecutor)"
            + "|new\\s+(LinkedBlockingQueue|LinkedBlockingDeque|LinkedTransferQueue|PriorityBlockingQueue)\\s*<[^>]*>\\s*\\(\\s*\\)");
    private static final Pattern UNBOUNDED_THREADS_INIT = Pattern.compile("Executors\\s*\\.\\s*(newCachedThreadPool|newVirtualThreadPerTaskExecutor"
            + "|newThreadPerTaskExecutor)|new\\s+SimpleAsyncTaskExecutor");
    private static final Pattern HTTP_INIT = Pattern.compile("new\\s+(OAuth2)?RestTemplate\\s*\\(\\s*\\)|(WebClient|RestClient)\\s*\\.\\s*(create|builder)\\s*\\(");
    private static final Set<String> UNBOUNDED_QUEUES = Set.of("LinkedBlockingQueue", "LinkedBlockingDeque", "LinkedTransferQueue",
            "PriorityBlockingQueue");

    private final Index index;
    /** Per module, whether its application config sets Spring Boot's client timeouts for auto-configured builders. */
    private final Predicate<String> builderTimeouts;
    private final Map<String, Map<String, List<Candidate>>> beansByModule = new HashMap<>();
    private Map<String, List<Candidate>> allBeans;
    private final Map<TypeDecl, Map<String, Built>> cache = new java.util.IdentityHashMap<>();

    Construction(Index index, Predicate<String> builderTimeouts) {
        this.index = index;
        this.builderTimeouts = builderTimeouts;
    }

    /**
     * The construction of the client or executor held in field {@code field} of {@code owner},
     * when it lacks timeouts or bounds; null when it is bounded or cannot be told.
     */
    Built problem(TypeDecl owner, String field, String fieldType) {
        String family = FAMILY.get(JavaSourceParser.simpleTypeName(fieldType));
        if (family == null) {
            return null;
        }
        Built b = cache.computeIfAbsent(owner, k -> new HashMap<>()).computeIfAbsent(field, f -> resolve(owner, f, family));
        return b == null || b.problem() == null ? null : b;
    }

    private Built resolve(TypeDecl owner, String field, String family) {
        TypeDecl.FieldInit init = owner.fieldInits.get(field);
        if (init != null) {
            String problem = initProblem(family, init.text());
            if (problem != null || constructsInText(family, init.text())) {
                return new Built(owner.file.relPath(), init.line(), owner.displayName + "." + field, problem);
            }
        }
        for (MethodDecl m : owner.methods) {
            if (m.isConstructor() && m.body().assignments().stream().anyMatch(a -> a.name().equals(field)) && constructs(family, m)) {
                return built(owner, m, family);
            }
        }
        String qualifier = qualifier(owner, field);
        Map<String, List<Candidate>> scoped = beans(owner.file.module());
        List<Candidate> candidates = scoped.getOrDefault(family, List.of());
        if (candidates.isEmpty()) {
            candidates = allBeans().getOrDefault(family, List.of());
        }
        for (Candidate c : candidates) {
            if (c.names().contains(qualifier != null ? qualifier : field)) {
                return c.built();
            }
        }
        if (candidates.size() == 1 || !candidates.isEmpty() && candidates.stream().allMatch(c -> c.built().problem() != null)) {
            return candidates.get(0).built();
        }
        return null;
    }

    private Map<String, List<Candidate>> beans(String module) {
        return beansByModule.computeIfAbsent(module, m -> collect(t -> t.file.module().equals(m)));
    }

    private Map<String, List<Candidate>> allBeans() {
        if (allBeans == null) {
            allBeans = collect(t -> true);
        }
        return allBeans;
    }

    private Map<String, List<Candidate>> collect(Predicate<TypeDecl> in) {
        Map<String, List<Candidate>> out = new HashMap<>();
        for (TypeDecl t : index.types()) {
            if (!in.test(t)) {
                continue;
            }
            for (MethodDecl m : t.methods) {
                Anno bean = m.annos().stream().filter(a -> a.simpleName().equals("Bean")).findFirst().orElse(null);
                String family = bean == null || m.returnType() == null ? null : FAMILY.get(JavaSourceParser.simpleTypeName(m.returnType()));
                if (family == null || !m.hasBody()) {
                    continue;
                }
                Set<String> names = new java.util.LinkedHashSet<>();
                names.add(m.name());
                Matcher n = BEAN_NAME.matcher(bean.text());
                while (n.find()) {
                    names.add(n.group(1));
                }
                out.computeIfAbsent(family, k -> new ArrayList<>()).add(new Candidate(built(t, m, family), names));
            }
        }
        return out;
    }

    /** A {@code @Qualifier} on the constructor parameter that has the field's name. */
    private static String qualifier(TypeDecl owner, String field) {
        for (MethodDecl m : owner.methods) {
            if (!m.isConstructor()) {
                continue;
            }
            for (MethodDecl.Param p : m.params()) {
                if (!p.name().equals(field)) {
                    continue;
                }
                for (Anno a : p.annos()) {
                    Matcher n = BEAN_NAME.matcher(a.text());
                    if (a.simpleName().equals("Qualifier") && n.find()) {
                        return n.group(1);
                    }
                }
            }
        }
        return null;
    }

    /** The constructor builds the client or pool itself, rather than storing one it was given. */
    private boolean constructs(String family, MethodDecl m) {
        boolean builderParam = m.params().stream().anyMatch(p -> JavaSourceParser.simpleTypeName(p.type()).equals("RestTemplateBuilder")
                || p.type().endsWith(family + ".Builder") || p.type().equals("Builder"));
        return switch (family) {
            case "RestTemplate" -> m.newTypes().contains("RestTemplate") || m.newTypes().contains("OAuth2RestTemplate")
                    || builderParam && m.calls().containsKey("build");
            case "WebClient", "RestClient" -> m.body().sites().stream().anyMatch(s -> family.equals(s.receiver())
                    && (s.name().equals("builder") || s.name().equals("create"))) || builderParam && m.calls().containsKey("build");
            default -> poolProblem(m) != null || m.newTypes().contains("ThreadPoolTaskExecutor") || m.newTypes().contains("ThreadPoolExecutor")
                    || m.calls().keySet().stream().anyMatch(c -> c.startsWith("new") && c.endsWith("Pool"));
        };
    }

    private Built built(TypeDecl t, MethodDecl m, String family) {
        String problem = family.equals("Executor") ? poolProblem(m) : httpProblem(t, m, family);
        return new Built(t.file.relPath(), siteLine(m, family), t.displayName + "." + m.name(), problem);
    }

    private String httpProblem(TypeDecl t, MethodDecl m, String family) {
        for (String c : m.calls().keySet()) {
            if (TIMEOUTS.matcher(c).matches()) {
                return null;
            }
        }
        for (String n : m.newTypes()) {
            if (TIMEOUTS.matcher(n).find()) {
                return null;
            }
        }
        if (family.equals("RestTemplate") && m.newTypes().stream().anyMatch(n -> n.endsWith("RestTemplate"))) {
            boolean bare = m.body().sites().stream().anyMatch(s -> Site.NEW.equals(s.receiver()) && s.name().endsWith("RestTemplate") && s.args() == 0);
            return bare ? "new RestTemplate() without a request factory: no connect or read timeout" : null;
        }
        boolean ownBuilder = m.body().sites().stream().anyMatch(s -> family.equals(s.receiver())
                && (s.name().equals("builder") || s.name().equals("create")));
        // An injected builder is the one Spring Boot 3.4+ configures from spring.http.client.*.
        if (!ownBuilder && builderTimeouts.test(t.file.module())) {
            return null;
        }
        return switch (family) {
            case "RestTemplate" -> "RestTemplate from a RestTemplateBuilder without connect or read timeouts";
            case "WebClient" -> "WebClient without a response timeout or a configured connector";
            default -> "RestClient without a request factory or timeouts";
        };
    }

    private static String poolProblem(MethodDecl m) {
        Map<String, Integer> calls = m.calls();
        List<String> problems = new ArrayList<>();
        if (calls.containsKey("newFixedThreadPool") || calls.containsKey("newSingleThreadExecutor")) {
            problems.add("Executors." + (calls.containsKey("newFixedThreadPool") ? "newFixedThreadPool" : "newSingleThreadExecutor")
                    + ": unbounded queue, queued tasks are not awaited on shutdown");
        }
        if (calls.containsKey("newCachedThreadPool") || calls.containsKey("newVirtualThreadPerTaskExecutor")
                || calls.containsKey("newThreadPerTaskExecutor") || m.newTypes().contains("SimpleAsyncTaskExecutor") && !calls.containsKey("setConcurrencyLimit")) {
            problems.add("one thread per task with no limit, running tasks are not awaited on shutdown");
        }
        if (m.newTypes().contains("ThreadPoolTaskExecutor")) {
            List<String> lacks = new ArrayList<>();
            if (!calls.containsKey("setQueueCapacity")) {
                lacks.add("unbounded queue (no setQueueCapacity)");
            }
            if (!calls.containsKey("setWaitForTasksToCompleteOnShutdown") && !calls.containsKey("setAwaitTerminationSeconds")
                    && !calls.containsKey("setAwaitTerminationMillis")) {
                lacks.add("queued tasks dropped on shutdown");
            }
            if (!lacks.isEmpty()) {
                problems.add("ThreadPoolTaskExecutor: " + String.join(", ", lacks));
            }
        }
        if (m.newTypes().contains("ThreadPoolExecutor") && m.body().sites().stream()
                .anyMatch(s -> Site.NEW.equals(s.receiver()) && UNBOUNDED_QUEUES.contains(s.name()) && s.args() == 0)) {
            problems.add("ThreadPoolExecutor on an unbounded queue");
        }
        return problems.isEmpty() ? null : String.join("; ", problems);
    }

    private static String initProblem(String family, String text) {
        if (family.equals("Executor")) {
            if (UNBOUNDED_QUEUE_INIT.matcher(text).find()) {
                return "unbounded queue, queued tasks are not awaited on shutdown: " + shorten(text);
            }
            if (UNBOUNDED_THREADS_INIT.matcher(text).find()) {
                return "one thread per task with no limit: " + shorten(text);
            }
            return null;
        }
        return HTTP_INIT.matcher(text).find() && !TIMEOUTS.matcher(text).find() ? "no connect or read timeout: " + shorten(text) : null;
    }

    private static boolean constructsInText(String family, String text) {
        return family.equals("Executor") ? text.contains("Executors") || text.contains("new ") : HTTP_INIT.matcher(text).find() || text.contains("build(");
    }

    private static String shorten(String text) {
        String t = text.replaceAll("\\s+", " ");
        return t.length() > 80 ? t.substring(0, 77) + "..." : t;
    }

    /** The line that builds the client or pool inside the method, else the method's own line. */
    private static int siteLine(MethodDecl m, String family) {
        for (Site s : m.body().sites()) {
            boolean match = family.equals("Executor")
                    ? Site.NEW.equals(s.receiver()) && (s.name().equals("ThreadPoolTaskExecutor") || s.name().equals("ThreadPoolExecutor")
                    || s.name().equals("SimpleAsyncTaskExecutor")) || "Executors".equals(s.receiver()) && s.name().startsWith("new")
                    : Site.NEW.equals(s.receiver()) && s.name().endsWith("RestTemplate")
                    || ("WebClient".equals(s.receiver()) || "RestClient".equals(s.receiver())) && (s.name().equals("builder") || s.name().equals("create"))
                    || s.name().equals("build");
            if (match) {
                return s.line();
            }
        }
        return m.line();
    }
}
