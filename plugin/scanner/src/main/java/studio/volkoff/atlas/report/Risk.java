package studio.volkoff.atlas.report;

import studio.volkoff.atlas.effects.Signals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Static attention level of one operation, from its kind, tags and the effects found on its path.
 * A level is a reason to look, never a verdict: the report keeps it apart from the audit colours.
 */
public final class Risk {

    public enum Level { HIGH, MEDIUM, LOW, NONE }

    /** @param evidence indexes into the operation's effect list */
    public record Reason(String code, Level level, List<Integer> evidence) {
        public String title() {
            return RULES.get(code)[0];
        }
    }

    public record Assessment(Level level, int score, List<Reason> reasons, List<String> mitigations, boolean mutating) {
    }

    /**
     * What the rest of the atlas says about an operation's effects.
     *
     * @param listenerSubkinds for each event type, the subkinds of the listeners that handle it
     */
    public record Context(Map<String, Set<String>> listenerSubkinds) {
        public static final Context EMPTY = new Context(Map.of());

        /** Built from {@code entrypoints.jsonl} rows: the event listeners and their subkinds. */
        public static Context from(List<Map<String, Object>> entryPoints) {
            Map<String, Set<String>> out = new java.util.HashMap<>();
            for (Map<String, Object> ep : entryPoints) {
                if (!"EVENT".equals(ep.get("kind"))) {
                    continue;
                }
                for (String type : String.valueOf(ep.get("target")).split(",")) {
                    out.computeIfAbsent(type.strip(), k -> new LinkedHashSet<>()).add(String.valueOf(ep.get("subkind")));
                }
            }
            return new Context(out);
        }
    }

    /** Rule code to {title, explanation}; the page prints the explanation once per code. */
    public static final Map<String, String[]> RULES = new LinkedHashMap<>();

    private static void rule(String code, String title, String text) {
        RULES.put(code, new String[] {title, text});
    }

    static {
        rule("handoff-after-ack", "Work handed to an in-memory thread",
                "The request hands work to an in-process thread and the caller gets its answer before the work is done. A restart or deploy loses the queued work, and nobody retries it.");
        rule("handoff-after-consume", "Message acknowledged before the work is done",
                "The consumer hands work to an in-process thread and returns, so the message is acknowledged first. A restart loses the queued work and the broker never redelivers it.");
        rule("async-in-tx", "Async work started inside a transaction",
                "The task is submitted before the transaction commits. It can run before the data is visible, or after a rollback for data that never existed.");
        rule("dual-write-in-tx", "Message sent before the database commits",
                "The message is sent inside the database transaction, before the commit. If the commit fails, consumers already received a message about data that does not exist.");
        rule("dual-write", "Database write and message send without an outbox",
                "The database write and the message send are separate steps. A crash between them keeps one and loses the other.");
        rule("remote-in-tx", "Remote call inside a database transaction",
                "The transaction stays open while the remote service answers. A rollback afterwards cannot undo what the remote side already did, and a slow provider exhausts the connection pool.");
        rule("in-memory-state", "State kept in instance memory",
                "Changes a collection or counter held by a singleton bean. It is lost on restart and not shared between replicas, so any state that matters must also be in a durable store.");
        rule("shared-field-write", "Request writes a field of a shared bean",
                "Beans are singletons, so every concurrent request or message sees and overwrites the same field. The value also disappears on restart and differs between replicas.");
        rule("http-session", "Data kept in the HTTP session",
                "The session lives in this server's memory (no Spring Session). Users lose it on restart, and a load balancer that sends them to another replica loses it too.");
        rule("local-file", "Writes to the instance's local disk",
                "Files on the local disk disappear when the instance is replaced and are invisible to other replicas. Anything that must survive belongs in shared storage.");
        rule("consumer-not-idempotent", "No idempotency check in the consumer",
                "Brokers deliver at least once. Nothing on this path looks up whether the message was already processed, so a redelivery repeats the write, send or call.");
        rule("webhook-not-idempotent", "Webhook without an event-id check",
                "Providers retry webhooks on timeouts and errors. Nothing on this path looks up the provider's event id, so a retry repeats the side effects.");
        rule("webhook-sync-remote", "Webhook calls another service before answering",
                "The provider waits while this service calls out. A slow dependency makes the provider time out and retry while the first attempt is still running.");
        rule("job-without-lock", "Runs on every instance",
                "No ShedLock, Quartz lock or row lock was found. With more than one replica the job runs on each of them at the same time and repeats its writes and sends.");
        rule("proxy-bypassed", "@Async or @Transactional silently ignored",
                "A method calls its own annotated method through this, or Spring calls an init callback on the raw bean. The proxy never sees the call, so it runs synchronously or without the expected transaction.");
        rule("async-listener", "Event handled on another thread",
                "The listener runs asynchronously. The event exists only in memory until it runs, so a restart drops it and the publisher is not told.");
        rule("handoff-in-listener", "Listener hands work to another thread",
                "The listener submits work to an in-process thread and returns. If the instance stops before the work runs, it is lost and nothing retries it.");
        rule("handoff-in-job", "Scheduled work forks onto other threads",
                "The job hands work to an in-process thread. A shutdown during the run drops what is still queued, and the next run may not pick it up.");
        rule("notification-in-tx", "Notification sent before the transaction commits",
                "A notification goes out from another thread while the transaction is still open. Users can be told about a change that then rolls back.");
        rule("notification-handoff", "Notification sent on another thread",
                "An e-mail or message to users is sent in the background. A restart loses the ones still queued; fine if notifications are best effort.");
        rule("timer-handoff", "Delayed work on an in-memory timer",
                "Work is scheduled to run later on an in-process timer. If the instance restarts before it fires, the work never happens.");
        rule("async-response", "Asynchronous response",
                "The handler returns a Mono, future or emitter and the client waits for it. A restart fails the request instead of losing it silently; check that retrying it is safe.");
        rule("background-thread", "Starts background work",
                "Starts a thread or a recurring task at startup or shutdown. Check that it stops cleanly and that its work survives a restart.");
        rule("in-memory-cache", "Cache, counter or local lock in instance memory",
                "Writes a cache, a counter, a lock map or a field of a singleton bean. Fine for derived data and single-instance guards; wrong if this is the only copy, because each replica has its own and a restart clears it.");
        rule("event-in-tx", "In-process event published inside a transaction",
                "Plain @EventListener methods run before the commit. If the transaction rolls back, they have already acted on data that does not exist.");
        rule("read-verb-writes", "Read-only HTTP verb changes data",
                "GET and HEAD are retried and prefetched freely by clients and proxies. A write here repeats on every retry.");
        rule(Places.NO_HINT, "No pattern matched",
                "The operation writes, sends, calls out or opens a transaction, but no rule fired. Not a hint: the quick check can sweep it for a failure the rules missed.");
    }

    private static final Set<String> REQUEST_KINDS = Set.of("HTTP", "SOAP", "GRPC", "GRAPHQL", "WEBSOCKET", "ROUTER", "ACTUATOR", "PORTLET");
    private static final Set<String> TRIGGER_KINDS = Set.of("SCHEDULED", "JOB");
    private static final Set<String> MUTATING_VERBS = Set.of("POST", "PUT", "PATCH", "DELETE", "ANY", "");
    /** Events published once while the application starts: their listeners behave like startup hooks. */
    private static final Set<String> STARTUP_EVENTS = Set.of("ApplicationReadyEvent", "ApplicationStartedEvent",
            "ContextRefreshedEvent", "ContextStartedEvent", "WebServerInitializedEvent", "ServletWebServerInitializedEvent");
    /** Return types with which the client waits for work done on another thread. */
    private static final Set<String> ASYNC_RESPONSES = Set.of("Mono", "Flux", "CompletableFuture", "CompletionStage",
            "DeferredResult", "Callable", "WebAsyncTask", "ListenableFuture", "Future", "SseEmitter", "ResponseBodyEmitter",
            "StreamingResponseBody", "Flow", "Publisher");
    private static final Set<String> STREAMING_RESPONSES = Set.of("SseEmitter", "ResponseBodyEmitter", "StreamingResponseBody", "Flux", "Flow");
    /** Listener subkinds that run after the publishing transaction commits. */
    private static final Set<String> AFTER_COMMIT_LISTENERS = Set.of("TRANSACTIONAL_EVENT_LISTENER", "MODULE_LISTENER");
    private static final Set<String> CACHE_LIKE = Set.of("computeIfAbsent", "putIfAbsent");
    /** Words of a class or method name that mean "tell a person": the work is a message, not business state. */
    private static final Set<String> NOTIFICATION_WORDS = Set.of("mail", "email", "e-mail", "notification", "notifications",
            "notifier", "notify", "sms", "telegram");
    /** Fields named like caches or memos hold derived data. */
    private static final Pattern CACHE_NAME = Pattern.compile("(?i)(cache|memo|token)");
    /** A numeric field assigned in place is a counter or a timestamp-style throttle, not stored data. */
    private static final Pattern NUMERIC_ASSIGN = Pattern.compile("^assigns field \\w+ \\((int|long|double|float|short|Integer|Long|Double)\\)$");
    /** Per-instance housekeeping: every replica is meant to run it. */
    private static final Pattern PER_INSTANCE_JOB = Pattern.compile("(?i)(send)?(heartbeat|renewLeases?|keepAlive|reconnect|ping)");
    /** Counters and sequences: lost on restart, but rarely the only copy of business data. */
    private static final Pattern COUNTER = Pattern.compile("\\((Atomic(Integer|Long)|LongAdder|DoubleAdder|LongAccumulator)\\)$");
    /** A map of locks guards one instance only; worth a look, but it is not stored data. */
    private static final Pattern LOCK_FIELD = Pattern.compile("(?i)^\\w*(lock|mutex|semaphore)\\w*\\.");

    private Risk() {
    }

    /** Effects are maps as written to {@code effects.jsonl}. */
    public static Assessment assess(Map<String, Object> ep, List<Map<String, Object>> effects, String entryReturns, Context context) {
        String kind = String.valueOf(ep.get("kind"));
        boolean mutating = mutating(ep);
        boolean webhook = isWebhook(ep);
        boolean request = REQUEST_KINDS.contains(kind);
        boolean consumer = kind.equals("MESSAGING");
        boolean startup = kind.equals("LIFECYCLE") || kind.equals("EVENT") && STARTUP_EVENTS.contains(String.valueOf(ep.get("target")));
        List<Reason> reasons = new ArrayList<>();

        List<Integer> business = new ArrayList<>();
        List<Integer> timers = new ArrayList<>();
        List<Integer> notifications = new ArrayList<>();
        for (int i : matching(effects, Signals.ASYNC)) {
            String d = detail(effects, i);
            if (d.endsWith(Signals.WAITED)) {
                continue;
            }
            if (d.startsWith(Signals.TIMER)) {
                // A programmatic schedule registers itself with a timer call: that is not a fork inside the job.
                if (!(kind.equals("SCHEDULED") && "PROGRAMMATIC".equals(ep.get("subkind")) && hop(effects, i) == 0)) {
                    timers.add(i);
                }
            } else if (isNotification(d + " " + effects.get(i).get("symbol"))) {
                notifications.add(i);
            } else {
                business.add(i);
            }
        }
        List<Integer> writes = matching(effects, Signals.DB_WRITE);
        List<Integer> outbox = new ArrayList<>();
        for (int i : writes) {
            if (detail(effects, i).startsWith("outbox ")) {
                outbox.add(i);
            }
        }
        List<Integer> plainWrites = new ArrayList<>(writes);
        plainWrites.removeAll(outbox);
        List<Integer> sends = matching(effects, Signals.MESSAGE);
        List<Integer> external = matching(effects, Signals.EXTERNAL);
        List<Integer> state = matching(effects, Signals.STATE);
        List<Integer> files = matching(effects, Signals.LOCAL_FILE);
        List<Integer> selfCalls = matching(effects, Signals.SELF_CALL);
        List<Integer> events = matching(effects, Signals.EVENT);
        boolean idempotent = !matching(effects, Signals.IDEMPOTENCY).isEmpty();
        boolean locked = !matching(effects, Signals.LOCK).isEmpty();
        List<Integer> changes = concat(plainWrites, sends, external);
        // A stream keeps the client connected while other threads write to it, so a restart fails the request visibly.
        boolean streaming = entryReturns != null && STREAMING_RESPONSES.contains(entryReturns);
        boolean asyncResponse = request && entryReturns != null && ASYNC_RESPONSES.contains(entryReturns)
                && (streaming || business.stream().allMatch(i -> detail(effects, i).endsWith(Signals.RETURNED)));

        if (!business.isEmpty()) {
            if (asyncResponse) {
                reasons.add(new Reason("async-response", Level.LOW, business));
            } else if (request) {
                reasons.add(new Reason("handoff-after-ack", Level.HIGH, business));
            } else if (consumer) {
                reasons.add(new Reason("handoff-after-consume", Level.HIGH, business));
            } else if (startup) {
                reasons.add(new Reason("background-thread", Level.LOW, business));
            } else if (kind.equals("EVENT")) {
                boolean asyncListener = business.stream().anyMatch(i -> detail(effects, i).startsWith("entry method runs on an executor"));
                reasons.add(new Reason(asyncListener ? "async-listener" : "handoff-in-listener", Level.MEDIUM, business));
            } else if (TRIGGER_KINDS.contains(kind)) {
                reasons.add(new Reason("handoff-in-job", Level.MEDIUM, business));
            } else {
                reasons.add(new Reason("background-thread", Level.LOW, business));
            }
            List<Integer> inTx = inTx(effects, business);
            if (!inTx.isEmpty()) {
                reasons.add(new Reason("async-in-tx", Level.HIGH, inTx));
            }
        }
        if (!notifications.isEmpty()) {
            List<Integer> inTx = inTx(effects, notifications);
            reasons.add(inTx.isEmpty()
                    ? new Reason("notification-handoff", Level.LOW, notifications)
                    : new Reason("notification-in-tx", Level.MEDIUM, inTx));
        }
        if (!timers.isEmpty() && !startup) {
            reasons.add(new Reason("timer-handoff", Level.LOW, timers));
        }
        if (!plainWrites.isEmpty() && !sends.isEmpty()) {
            boolean shared = !inTx(effects, plainWrites).isEmpty() && !inTx(effects, sends).isEmpty();
            reasons.add(new Reason(shared ? "dual-write-in-tx" : "dual-write", shared ? Level.HIGH : Level.MEDIUM, concat(plainWrites, sends)));
        }
        List<Integer> remoteInTx = anyTx(effects, external);
        if (!remoteInTx.isEmpty()) {
            boolean onlyReads = remoteInTx.stream().allMatch(i -> !Boolean.TRUE.equals(effects.get(i).get("inTx"))
                    || READ_CALL.matcher(detail(effects, i)).find());
            reasons.add(new Reason("remote-in-tx", onlyReads ? Level.LOW : Level.MEDIUM, remoteInTx));
        }
        if (!state.isEmpty() && !startup && !kind.equals("ACTUATOR")) {
            List<Integer> stores = new ArrayList<>();
            List<Integer> assigned = new ArrayList<>();
            List<Integer> caches = new ArrayList<>();
            List<Integer> sessions = new ArrayList<>();
            Set<String> putAndRemoved = transientFields(effects, state);
            for (int i : state) {
                String d = detail(effects, i);
                String call = d.contains(".") && d.contains(" (") ? d.substring(d.indexOf('.') + 1, d.indexOf(" (")) : "";
                String field = d.startsWith("assigns field ") ? d.substring("assigns field ".length()).split(" ")[0]
                        : d.contains(".") ? d.substring(0, d.indexOf('.')) : "";
                String owner = String.valueOf(effects.get(i).get("symbol"));
                if (putAndRemoved.contains(field) || CACHE_NAME.matcher(field).find() || CACHE_NAME.matcher(owner.split("\\.")[0]).find()
                        || NUMERIC_ASSIGN.matcher(d).find() || d.contains("declared in another module")) {
                    caches.add(i);
                    continue;
                }
                if (d.startsWith("HTTP session")) {
                    sessions.add(i);
                } else if (d.startsWith("assigns field")) {
                    assigned.add(i);
                } else if (d.endsWith(", cache)") || CACHE_LIKE.contains(call) || COUNTER.matcher(d).find() || LOCK_FIELD.matcher(d).find()) {
                    caches.add(i);
                } else {
                    stores.add(i);
                }
            }
            boolean concurrent = request || consumer;
            if (!stores.isEmpty()) {
                reasons.add(new Reason("in-memory-state", Level.MEDIUM, stores));
            }
            if (!sessions.isEmpty()) {
                reasons.add(new Reason("http-session", Level.MEDIUM, sessions));
            }
            if (!assigned.isEmpty() && concurrent) {
                reasons.add(new Reason("shared-field-write", Level.MEDIUM, assigned));
            }
            List<Integer> minor = new ArrayList<>(caches);
            if (!concurrent) {
                minor.addAll(assigned);
            }
            if (stores.isEmpty() && !minor.isEmpty()) {
                reasons.add(new Reason("in-memory-cache", Level.LOW, minor));
            }
        }
        if (!files.isEmpty()) {
            reasons.add(new Reason("local-file", request || consumer || TRIGGER_KINDS.contains(kind) ? Level.MEDIUM : Level.LOW, files));
        }
        if (consumer && !changes.isEmpty() && !idempotent) {
            reasons.add(new Reason("consumer-not-idempotent", Level.MEDIUM, changes));
        }
        if (webhook && !concat(changes, business).isEmpty() && !idempotent) {
            reasons.add(new Reason("webhook-not-idempotent", Level.MEDIUM, concat(changes, business)));
        }
        if (webhook && !external.isEmpty()) {
            reasons.add(new Reason("webhook-sync-remote", Level.MEDIUM, external));
        }
        boolean housekeeping = "PROGRAMMATIC".equals(ep.get("subkind")) || PER_INSTANCE_JOB.matcher(String.valueOf(ep.get("method"))).matches()
                || String.valueOf(ep.get("cls")).contains("Lease") || String.valueOf(ep.get("cls")).contains("Heartbeat");
        // Housekeeping that writes or sends is a business job after all.
        boolean perInstance = housekeeping && plainWrites.isEmpty() && sends.isEmpty();
        if (TRIGGER_KINDS.contains(kind) && !changes.isEmpty() && !locked && !perInstance && !Boolean.TRUE.equals(attrs(ep).get("disabled"))) {
            reasons.add(new Reason("job-without-lock", Level.MEDIUM, changes));
        }
        if (!selfCalls.isEmpty()) {
            reasons.add(new Reason("proxy-bypassed", bypassMatters(effects, selfCalls) ? Level.MEDIUM : Level.LOW, selfCalls));
        }
        List<Integer> eventsInTx = new ArrayList<>();
        for (int i : inTx(effects, events)) {
            if (!handledAfterCommit(detail(effects, i), context)) {
                eventsInTx.add(i);
            }
        }
        if (!eventsInTx.isEmpty()) {
            reasons.add(new Reason("event-in-tx", Level.LOW, eventsInTx));
        }
        if (kind.equals("HTTP") && !mutating && !concat(plainWrites, sends).isEmpty()) {
            reasons.add(new Reason("read-verb-writes", Level.LOW, concat(plainWrites, sends)));
        }

        reasons.sort(Comparator.comparingInt(r -> r.level().ordinal()));
        Level level = reasons.isEmpty() ? Level.NONE : reasons.get(0).level();
        List<String> mitigations = new ArrayList<>();
        if (idempotent) {
            mitigations.add("idempotency check");
        }
        if (locked) {
            mitigations.add("lock");
        }
        if (!matching(effects, Signals.RETRY).isEmpty()) {
            mitigations.add("retry");
        }
        if (!outbox.isEmpty()) {
            mitigations.add("outbox");
        }
        return new Assessment(level, score(kind, mutating, webhook, level, reasons, effects), reasons, mitigations, mutating);
    }

    /** Whether every listener of the event published at this effect runs after the commit. */
    private static boolean handledAfterCommit(String detail, Context context) {
        int open = detail.lastIndexOf('(');
        if (open < 0 || !detail.endsWith(")")) {
            return false;
        }
        Set<String> subkinds = context.listenerSubkinds().get(detail.substring(open + 1, detail.length() - 1));
        // No listener in the sources: nothing acts on the event before the commit.
        return subkinds == null || subkinds.isEmpty() || AFTER_COMMIT_LISTENERS.containsAll(subkinds);
    }

    /** Ordering within the attention list. The bands are disjoint, so a score never ranks a lower level above a higher one. */
    private static int score(String kind, boolean mutating, boolean webhook, Level level, List<Reason> reasons,
                             List<Map<String, Object>> effects) {
        int band = switch (level) {
            case HIGH -> 300;
            case MEDIUM -> 200;
            case LOW -> 100;
            case NONE -> 0;
        };
        int s = 4 * Math.min(reasons.size(), 5);
        s += switch (kind) {
            case "MESSAGING" -> 10;
            case "SCHEDULED", "JOB" -> 8;
            case "HTTP", "SOAP", "GRPC" -> mutating ? 6 : 0;
            case "EVENT" -> 5;
            default -> 0;
        };
        if (webhook) {
            s += 10;
        }
        Set<String> signals = new LinkedHashSet<>();
        for (Map<String, Object> e : effects) {
            signals.add(String.valueOf(e.get("signal")));
        }
        for (String sig : List.of(Signals.DB_WRITE, Signals.MESSAGE, Signals.EXTERNAL)) {
            if (signals.contains(sig)) {
                s += 3;
            }
        }
        return band + Math.min(s, 99);
    }

    static boolean mutating(Map<String, Object> ep) {
        String kind = String.valueOf(ep.get("kind"));
        if (kind.equals("HTTP") || kind.equals("ROUTER")) {
            for (String verb : String.valueOf(ep.get("subkind")).split(",")) {
                if (MUTATING_VERBS.contains(verb.trim())) {
                    return true;
                }
            }
            return false;
        }
        if (kind.equals("PORTLET")) {
            return !"RENDER".equals(ep.get("subkind"));
        }
        return !kind.equals("ACTUATOR") || "WRITE".equals(ep.get("subkind")) || "DELETE".equals(ep.get("subkind"));
    }

    static boolean isWebhook(Map<String, Object> ep) {
        return attrs(ep).get("webhook") instanceof Map<?, ?> w && "WEBHOOK".equals(w.get("verdict"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attrs(Map<String, Object> ep) {
        return ep.get("attrs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static List<Integer> matching(List<Map<String, Object>> effects, String signal) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < effects.size(); i++) {
            if (signal.equals(effects.get(i).get("signal"))) {
                out.add(i);
            }
        }
        return out;
    }

    private static List<Integer> inTx(List<Map<String, Object>> effects, List<Integer> indexes) {
        List<Integer> out = new ArrayList<>();
        for (int i : indexes) {
            if (Boolean.TRUE.equals(effects.get(i).get("inTx"))) {
                out.add(i);
            }
        }
        return out;
    }

    /** Read-only transactions count here: they also hold a connection while the remote side answers. */
    private static List<Integer> anyTx(List<Map<String, Object>> effects, List<Integer> indexes) {
        List<Integer> out = new ArrayList<>();
        for (int i : indexes) {
            if (Boolean.TRUE.equals(effects.get(i).get("inTx")) || Boolean.TRUE.equals(effects.get(i).get("inReadOnlyTx"))) {
                out.add(i);
            }
        }
        return out;
    }

    /** A remote read: an HTTP GET or a client method named like a lookup. */
    private static final Pattern READ_CALL = Pattern.compile("\\.(get|head|find|fetch|load|search|list|read|query|lookup|exists)[A-Z(]?\\w*$");

    /**
     * A bypassed {@code @Async} always changes behaviour. A bypassed {@code @Transactional} matters
     * only when the method it was meant for writes, sends or calls out.
     */
    private static boolean bypassMatters(List<Map<String, Object>> effects, List<Integer> selfCalls) {
        for (int i : selfCalls) {
            String d = detail(effects, i);
            if (d.startsWith("@Async") || d.contains("init callback")) {
                return true;
            }
            int on = d.indexOf(" on ");
            int is = d.indexOf(" is bypassed");
            String target = on >= 0 && is > on ? d.substring(on + 4, is) : "";
            for (Map<String, Object> e : effects) {
                String sig = String.valueOf(e.get("signal"));
                if (target.equals(e.get("symbol")) && (sig.equals(Signals.DB_WRITE) || sig.equals(Signals.MESSAGE) || sig.equals(Signals.EXTERNAL))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isNotification(String text) {
        for (String word : text.split("(?<=[a-z])(?=[A-Z])|[^A-Za-z-]+")) {
            if (NOTIFICATION_WORDS.contains(word.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Fields that the same operation both puts into and removes from: request correlation, not state that outlives it. */
    private static Set<String> transientFields(List<Map<String, Object>> effects, List<Integer> state) {
        Set<String> put = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        for (int i : state) {
            String d = detail(effects, i);
            int dot = d.indexOf('.');
            int paren = d.indexOf(" (");
            if (dot < 0 || paren < dot) {
                continue;
            }
            String field = d.substring(0, dot);
            String call = d.substring(dot + 1, paren);
            if (call.startsWith("put") || call.equals("add") || call.equals("offer") || call.equals("compute") || call.equals("computeIfAbsent")) {
                put.add(field);
            } else if (call.startsWith("remove") || call.equals("poll")) {
                removed.add(field);
            }
        }
        put.retainAll(removed);
        return put;
    }

    private static int hop(List<Map<String, Object>> effects, int i) {
        return effects.get(i).get("hop") instanceof Number n ? n.intValue() : 0;
    }

    private static String detail(List<Map<String, Object>> effects, int i) {
        return String.valueOf(effects.get(i).get("detail"));
    }

    @SafeVarargs
    private static List<Integer> concat(List<Integer>... lists) {
        Set<Integer> out = new LinkedHashSet<>();
        for (List<Integer> l : lists) {
            out.addAll(l);
        }
        return new ArrayList<>(out);
    }
}
