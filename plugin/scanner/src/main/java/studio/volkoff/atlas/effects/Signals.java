package studio.volkoff.atlas.effects;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signal names and the library calls that produce them. Library types are matched by simple
 * name because their sources are not in the repository; project types are resolved instead.
 */
public final class Signals {

    public static final String ASYNC = "async-handoff";
    public static final String STATE = "in-memory-state";
    public static final String LOCAL_FILE = "local-file";
    public static final String DB_WRITE = "db-write";
    public static final String MESSAGE = "message-send";
    public static final String EVENT = "event-publish";
    public static final String EXTERNAL = "external-call";
    public static final String TX = "transactional";
    public static final String RETRY = "retry";
    public static final String LOCK = "lock";
    public static final String IDEMPOTENCY = "idempotency-check";
    public static final String SELF_CALL = "self-invocation";

    /** Suffix of an async effect whose result the same method waits for: parallel work, not a hand-off. */
    public static final String WAITED = " (waited for in the same method)";
    /** Suffix of an async effect whose future the method returns and no caller on the path waits for. */
    public static final String RETURNED = " (returned to the caller)";
    /** Detail prefix of an async effect that only delays work on a timer. */
    public static final String TIMER = "timer ";

    private Signals() {
    }

    record Call(String signal, String detail) {
    }

    private static final Map<String, Map<String, String>> LIBRARY = new HashMap<>();

    private static void lib(String signal, String detail, Set<String> types, Set<String> methods) {
        for (String t : types) {
            Map<String, String> byMethod = LIBRARY.computeIfAbsent(t, k -> new HashMap<>());
            for (String m : methods) {
                byMethod.put(m, signal + "|" + detail);
            }
        }
    }

    static final Set<String> EXECUTOR_TYPES = Set.of("Executor", "ExecutorService", "ScheduledExecutorService",
            "ThreadPoolExecutor", "ScheduledThreadPoolExecutor", "ForkJoinPool", "TaskExecutor", "AsyncTaskExecutor",
            "AsyncListenableTaskExecutor", "ThreadPoolTaskExecutor", "SimpleAsyncTaskExecutor", "TaskScheduler",
            "ThreadPoolTaskScheduler", "ConcurrentTaskExecutor", "ConcurrentTaskScheduler", "SimpleAsyncTaskScheduler",
            "ListeningExecutorService", "ListeningScheduledExecutorService", "VirtualThreadTaskExecutor",
            "ExecutorCompletionService", "CompletionService", "Timer");
    static final Set<String> EXECUTOR_METHODS = Set.of("execute", "submit", "submitListenable", "submitCompletable");
    static final Set<String> TIMER_METHODS = Set.of("schedule", "scheduleAtFixedRate", "scheduleWithFixedDelay",
            "scheduleWithFixedRate");
    static final Set<String> HTTP_VERBS = Set.of("get", "post", "put", "patch", "delete", "head", "options", "method");

    static {
        lib(ASYNC, "executor", EXECUTOR_TYPES, EXECUTOR_METHODS);
        lib(ASYNC, "timer", EXECUTOR_TYPES, TIMER_METHODS);
        lib(ASYNC, "CompletableFuture", Set.of("CompletableFuture"), Set.of("runAsync", "supplyAsync"));
        lib(ASYNC, "virtual thread", Set.of("Thread"), Set.of("startVirtualThread"));
        lib(ASYNC, "Akka mailbox", Set.of("ActorRef", "ActorSelection", "TypedActorRef"), Set.of("tell", "forward"));

        lib(MESSAGE, "Kafka", Set.of("KafkaTemplate", "KafkaOperations"), Set.of("send", "sendDefault", "executeInTransaction"));
        lib(MESSAGE, "Kafka", Set.of("ReactiveKafkaProducerTemplate"), Set.of("send", "sendTransactionally"));
        lib(MESSAGE, "Kafka", Set.of("KafkaProducer", "Producer"), Set.of("send"));
        lib(MESSAGE, "SQS", Set.of("SqsTemplate", "SqsOperations", "SqsAsyncOperations"),
                Set.of("send", "sendAsync", "sendMany", "sendManyAsync"));
        lib(MESSAGE, "SQS", Set.of("SqsAsyncClient", "SqsClient", "AmazonSQS", "AmazonSQSAsync", "AmazonSQSClient"),
                Set.of("sendMessage", "sendMessageBatch", "sendMessageAsync", "sendMessageBatchAsync"));
        lib(MESSAGE, "SQS", Set.of("QueueMessagingTemplate"), Set.of("send", "convertAndSend"));
        lib(MESSAGE, "SNS", Set.of("SnsTemplate", "SnsOperations", "NotificationMessagingTemplate"),
                Set.of("send", "convertAndSend", "sendNotification"));
        lib(MESSAGE, "SNS", Set.of("SnsClient", "SnsAsyncClient", "AmazonSNS", "AmazonSNSAsync"), Set.of("publish", "publishBatch"));
        lib(MESSAGE, "RabbitMQ", Set.of("RabbitTemplate", "AmqpTemplate", "RabbitOperations", "RabbitMessagingTemplate"),
                Set.of("send", "convertAndSend", "convertSendAndReceive", "sendAndReceive", "invoke"));
        lib(MESSAGE, "JMS", Set.of("JmsTemplate", "JmsOperations", "JmsMessagingTemplate"),
                Set.of("send", "convertAndSend", "sendAndReceive", "convertSendAndReceive"));
        lib(MESSAGE, "Spring Cloud Stream", Set.of("StreamBridge"), Set.of("send"));
        lib(MESSAGE, "Pub/Sub", Set.of("PubSubTemplate", "PubSubPublisherTemplate", "Publisher"), Set.of("publish"));
        lib(MESSAGE, "Kinesis", Set.of("KinesisClient", "KinesisAsyncClient", "AmazonKinesis", "AmazonKinesisAsync", "KinesisProducer"),
                Set.of("putRecord", "putRecords", "addUserRecord"));
        lib(MESSAGE, "EventBridge", Set.of("EventBridgeClient", "EventBridgeAsyncClient"), Set.of("putEvents"));
        lib(MESSAGE, "Spring Integration", Set.of("MessageChannel", "MessagingTemplate"), Set.of("send", "convertAndSend"));
        lib(MESSAGE, "Redis pub/sub", Set.of("RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate"), Set.of("convertAndSend"));

        lib(EVENT, "in-process event", Set.of("ApplicationEventPublisher", "ApplicationContext", "ConfigurableApplicationContext",
                "ApplicationEventMulticaster"), Set.of("publishEvent", "multicastEvent"));

        lib(DB_WRITE, "JDBC", Set.of("JdbcTemplate", "NamedParameterJdbcTemplate", "JdbcOperations", "NamedParameterJdbcOperations",
                        "JdbcClient", "SimpleJdbcInsert", "SimpleJdbcCall"),
                Set.of("update", "batchUpdate", "execute", "executeAndReturnKey", "executeAndReturnKeyHolder", "executeBatch"));
        lib(DB_WRITE, "JDBC", Set.of("Statement", "PreparedStatement", "CallableStatement"),
                Set.of("executeUpdate", "executeLargeUpdate", "executeBatch", "execute"));
        lib(DB_WRITE, "JPA", Set.of("EntityManager", "Session", "StatelessSession"),
                Set.of("persist", "merge", "remove", "save", "saveOrUpdate", "update", "delete", "insert", "upsert", "executeUpdate"));
        lib(DB_WRITE, "MyBatis", Set.of("SqlSession", "SqlSessionTemplate"), Set.of("insert", "update", "delete", "flushStatements", "commit"));
        lib(DB_WRITE, "MongoDB", Set.of("MongoTemplate", "MongoOperations", "ReactiveMongoTemplate", "ReactiveMongoOperations"),
                Set.of("save", "insert", "insertAll", "updateFirst", "updateMulti", "upsert", "remove", "findAndModify",
                        "findAndRemove", "findAllAndRemove", "bulkOps", "replace", "findAndReplace"));
        lib(DB_WRITE, "Redis", Set.of("RedisTemplate", "StringRedisTemplate", "ReactiveRedisTemplate", "ReactiveStringRedisTemplate"),
                Set.of("set", "setIfAbsent", "setIfPresent", "multiSet", "put", "putAll", "putIfAbsent", "increment", "decrement",
                        "leftPush", "rightPush", "leftPushAll", "rightPushAll", "add", "delete", "expire", "remove"));
        lib(DB_WRITE, "DynamoDB", Set.of("DynamoDbClient", "DynamoDbAsyncClient", "DynamoDbTable", "DynamoDbEnhancedClient",
                        "AmazonDynamoDB", "DynamoDBMapper", "DynamoDbTemplate"),
                Set.of("putItem", "updateItem", "deleteItem", "transactWriteItems", "batchWriteItem", "save", "delete", "update"));

        lib(EXTERNAL, "object storage", Set.of("S3Client", "S3AsyncClient", "AmazonS3", "S3Template", "MinioClient"),
                Set.of("putObject", "deleteObject", "deleteObjects", "copyObject", "upload", "store", "completeMultipartUpload",
                        "removeObject", "uploadObject"));
        lib(EXTERNAL, "HTTP", Set.of("RestTemplate", "RestOperations", "OAuth2RestTemplate"),
                Set.of("exchange", "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation", "put",
                        "patchForObject", "delete", "execute", "headForHeaders", "optionsForAllow"));
        lib(EXTERNAL, "HTTP", Set.of("WebClient", "RestClient"), HTTP_VERBS);
        lib(EXTERNAL, "HTTP", Set.of("HttpClient"), Set.of("send", "sendAsync", "execute"));
        lib(EXTERNAL, "HTTP", Set.of("CloseableHttpClient", "HttpAsyncClient", "CloseableHttpAsyncClient"), Set.of("execute"));
        lib(EXTERNAL, "HTTP", Set.of("OkHttpClient"), Set.of("newCall"));
        lib(EXTERNAL, "SOAP", Set.of("WebServiceTemplate", "WebServiceOperations"),
                Set.of("marshalSendAndReceive", "sendAndReceive", "sendSourceAndReceive", "sendSourceAndReceiveToResult"));
        lib(EXTERNAL, "e-mail", Set.of("JavaMailSender", "MailSender", "JavaMailSenderImpl"), Set.of("send"));
        lib(EXTERNAL, "e-mail", Set.of("Transport"), Set.of("send", "sendMessage"));
        lib(EXTERNAL, "e-mail", Set.of("Email", "SimpleEmail", "HtmlEmail", "MultiPartEmail", "ImageHtmlEmail"), Set.of("send"));
        lib(EXTERNAL, "LLM", Set.of("ChatClient", "ChatModel", "EmbeddingModel"), Set.of("prompt", "call", "stream", "embed"));

        lib(TX, "TransactionTemplate", Set.of("TransactionTemplate", "TransactionOperations"),
                Set.of("execute", "executeWithoutResult"));
        lib(RETRY, "RetryTemplate", Set.of("RetryTemplate", "RetryOperations"), Set.of("execute"));
        lib(RETRY, "Resilience4j", Set.of("Retry", "CircuitBreaker"),
                Set.of("decorateSupplier", "decorateRunnable", "decorateCallable", "decorateCheckedSupplier", "executeSupplier",
                        "executeRunnable", "executeCallable"));
        lib(LOCK, "ShedLock", Set.of("LockingTaskExecutor", "DefaultLockingTaskExecutor"), Set.of("executeWithLock"));
        lib(LOCK, "ShedLock", Set.of("LockProvider"), Set.of("lock"));
        lib(LOCK, "Spring Integration lock", Set.of("LockRegistry", "JdbcLockRegistry", "RedisLockRegistry"), Set.of("obtain"));
        lib(LOCK, "Redisson", Set.of("RedissonClient"), Set.of("getLock", "getFairLock", "getSpinLock", "getFencedLock"));
        lib(LOCAL_FILE, "local disk", Set.of("Files"), Set.of("write", "writeString", "copy", "move", "createFile",
                "newOutputStream", "newBufferedWriter"));
        lib(LOCAL_FILE, "local disk", Set.of("FileUtils"), Set.of("copyInputStreamToFile", "copyToFile", "copyFile",
                "copyURLToFile", "writeStringToFile", "writeByteArrayToFile", "writeLines", "write", "moveFile", "touch"));
    }

    /** The signal for a call on a library type, or {@code null}. */
    static Call library(String simpleType, String method) {
        Map<String, String> byMethod = LIBRARY.get(simpleType);
        String v = byMethod == null ? null : byMethod.get(method);
        if (v == null) {
            return null;
        }
        int bar = v.indexOf('|');
        String detail = v.substring(bar + 1);
        return new Call(v.substring(0, bar), (detail.equals("timer") ? TIMER : detail + " ") + simpleType + "." + method);
    }

    static boolean knownLibraryType(String simpleType) {
        return LIBRARY.containsKey(simpleType);
    }

    /** Creating one of these opens a stream to the local disk. */
    static final Set<String> FILE_WRITERS = Set.of("FileOutputStream", "FileWriter", "RandomAccessFile");

    /**
     * Packages of SDKs whose calls go over the network. A call on a type imported from one of
     * them is a remote call unless its name reads like a builder or an accessor.
     */
    static final List<String> SDK_PACKAGES = List.of("com.stripe.", "software.amazon.awssdk.", "com.amazonaws.",
            "com.google.cloud.", "com.twilio.", "com.sendgrid.", "org.keycloak.admin.", "com.slack.api.", "org.telegram.",
            "io.minio.", "com.azure.", "com.microsoft.azure.", "com.paypal.", "com.braintreegateway.", "com.yookassa.",
            "org.springframework.ai.", "com.openai.", "dev.langchain4j.", "com.github.dockerjava.", "io.kubernetes.client.");
    /** Calls on SDK types count as remote only when named like a remote operation; verifying or parsing stays local. */
    static final Pattern SDK_REMOTE_VERB = Pattern.compile("(create|retrieve|update|delete|del|list|search|capture|cancel|confirm"
            + "|refund|send|publish|put|upload|download|invoke|execute|call|post|fetch|charge|pay|transfer|subscribe|attach"
            + "|detach|complete|approve|void|reverse|query|scan|batch|copy|remove|start|stop|run)([A-Z].*)?");
    /** AWS SDK v2 client reads: getObject, getSecretValue, describeInstances, listObjects. */
    static final Pattern AWS_READ = Pattern.compile("(get|describe|list|head|batchGet|query|scan)[A-Z].*");
    static final Pattern NOT_REMOTE = Pattern.compile("(get|is|has|set|with|to|as|builder|newBuilder|build|of|valueOf|from|parse"
            + "|equals|hashCode|toString|close|getClass|toBuilder|copy|stream|iterator|size|isEmpty)([A-Z].*)?");

    static final Set<String> STATE_TYPES = Set.of("Map", "HashMap", "ConcurrentHashMap", "ConcurrentMap", "LinkedHashMap",
            "TreeMap", "ConcurrentSkipListMap", "WeakHashMap", "IdentityHashMap", "EnumMap", "NavigableMap", "SortedMap",
            "List", "ArrayList", "LinkedList", "CopyOnWriteArrayList", "Collection", "Set", "HashSet", "LinkedHashSet",
            "TreeSet", "ConcurrentSkipListSet", "CopyOnWriteArraySet", "Queue", "Deque", "ArrayDeque", "BlockingQueue",
            "BlockingDeque", "LinkedBlockingQueue", "ArrayBlockingQueue", "PriorityBlockingQueue", "DelayQueue",
            "ConcurrentLinkedQueue", "ConcurrentLinkedDeque", "LinkedBlockingDeque", "PriorityQueue", "TransferQueue",
            "LinkedTransferQueue", "AtomicInteger", "AtomicLong", "AtomicReference", "AtomicIntegerArray",
            "AtomicLongArray", "AtomicReferenceArray", "LongAdder", "DoubleAdder", "LongAccumulator", "Multimap",
            "ListMultimap", "SetMultimap", "Table", "BiMap", "Multiset", "Many");

    /**
     * Field types whose assignment stores data. Assigning anything else (a manager, a client, a
     * validator) is almost always lazy initialisation of a dependency. {@code AtomicBoolean} is
     * left out of the collection types: it is a re-entrancy guard far more often than state.
     */
    static final Set<String> VALUE_TYPES = Set.of("int", "long", "double", "float", "short", "byte", "char", "boolean",
            "Integer", "Long", "Double", "Float", "Short", "Byte", "Character", "Boolean", "String", "BigDecimal",
            "BigInteger", "Instant", "LocalDate", "LocalDateTime", "LocalTime", "OffsetDateTime", "ZonedDateTime", "Date",
            "Duration", "UUID", "Object");

    static final Set<String> CACHE_TYPES = Set.of("Cache", "LoadingCache", "AsyncCache", "AsyncLoadingCache");

    static final Set<String> STATE_MUTATORS = Set.of("put", "putAll", "putIfAbsent", "compute", "computeIfAbsent",
            "computeIfPresent", "merge", "replace", "replaceAll", "add", "addAll", "addFirst", "addLast", "offer",
            "offerFirst", "offerLast", "push", "remove", "removeAll", "removeIf", "retainAll", "clear", "poll", "pollFirst",
            "pollLast", "take", "drainTo", "set", "lazySet", "getAndSet", "compareAndSet", "weakCompareAndSet",
            "incrementAndGet", "decrementAndGet", "getAndIncrement", "getAndDecrement", "addAndGet", "getAndAdd",
            "updateAndGet", "getAndUpdate", "accumulateAndGet", "getAndAccumulate", "increment", "decrement", "accumulate",
            "invalidate", "invalidateAll", "tryEmitNext", "emitNext");

    /** Spring Data parents that make an interface a repository. */
    static final Set<String> REPOSITORY_PARENTS = Set.of("Repository", "CrudRepository", "ListCrudRepository",
            "PagingAndSortingRepository", "ListPagingAndSortingRepository", "JpaRepository", "JpaSpecificationExecutor",
            "QuerydslPredicateExecutor", "MongoRepository", "ReactiveCrudRepository", "ReactiveSortingRepository",
            "R2dbcRepository", "ReactiveMongoRepository", "ElasticsearchRepository", "CassandraRepository",
            "Neo4jRepository", "KeyValueRepository", "RevisionRepository", "CoroutineCrudRepository", "JdbcRepository");

    static final Pattern REPOSITORY_WRITE = Pattern.compile(
            "(save|insert|update|upsert|delete|remove|persist|merge|create|store|put|add|set|mark|increment|decrement|"
                    + "bulk|truncate|purge|archive|lock|release|assign|change|modify|replace|register|enqueue|claim)([A-Z_].*)?");

    /** A lookup or insert keyed by something a redelivery repeats: an event, message or request id. */
    static final Pattern IDEMPOTENCY_LOOKUP = Pattern.compile(
            "(exists|find|get|count|is|has|check)\\w*(EventId|MessageId|Idempotency|ExternalId|ExternalReference|"
                    + "ProviderEventId|RequestId|DeliveryId|Dedup|Fingerprint|UniqueKey|DeliveryKey|CorrelationId)\\w*"
                    + "|isProcessed|alreadyProcessed|wasProcessed|isDuplicate|\\w*(IfAbsent|OrIgnore|OnConflict\\w*|IgnoreDuplicates?)");

    /** Repository names that hold dedup records: any lookup on them is an idempotency check. */
    static final Pattern DEDUP_STORE = Pattern.compile(".*(Processed|Inbox|Dedup|Idempot|DeliveryLog|DeliveriesLog).*");

    /** Repository methods that claim rows for one worker. */
    static final Pattern LOCK_METHOD = Pattern.compile("(lock|tryLock|acquire|tryAcquire|claim|reserve)([A-Z_].*)?");
}
