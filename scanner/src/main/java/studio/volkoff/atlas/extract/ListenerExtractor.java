package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Expr;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.Status;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations.Arg;
import studio.volkoff.atlas.resolve.Annotations.Effective;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Evaluator.Value;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** MESSAGING and EVENT records: annotated listeners, listener interfaces, programmatic consumers, routes and beans. */
final class ListenerExtractor extends ExtractorBase {

    private static final Set<String> LISTENER_INTERFACES = Set.of("MessageListener", "AcknowledgingMessageListener",
            "BatchMessageListener", "BatchAcknowledgingMessageListener", "ConsumerAwareMessageListener",
            "AcknowledgingConsumerAwareMessageListener", "ChannelAwareMessageListener", "SessionAwareMessageListener",
            "AsyncMessageListener");
    private static final Set<String> SMART_LISTENERS = Set.of("SmartApplicationListener", "GenericApplicationListener");

    /** A programmatic consumer signal: a call name plus the import package that makes it a consumer. */
    private record Poller(String call, List<String> packages, String broker) {
    }

    private static final List<Poller> POLLERS = List.of(
            new Poller("poll", List.of("org.apache.kafka.clients.consumer"), "KAFKA"),
            new Poller("receive", List.of("reactor.kafka.receiver"), "KAFKA"),
            new Poller("receiveMessage", List.of("software.amazon.awssdk.services.sqs", "com.amazonaws.services.sqs"), "SQS"),
            new Poller("receive", List.of("io.awspring.cloud.sqs.operations"), "SQS"),
            new Poller("receiveMany", List.of("io.awspring.cloud.sqs.operations"), "SQS"),
            new Poller("subscribe", List.of("com.google.cloud.spring.pubsub", "com.google.cloud.pubsub"), "PUBSUB"));

    ListenerExtractor(Context c) {
        super(c);
    }

    void extract(TypeDecl t) {
        messaging(t);
        events(t);
        beans(t);
    }

    private void messaging(TypeDecl t) {
        Ctx ctx = Ctx.of(t);
        boolean hasKafkaListener = an.has(t.annos, ctx, "KafkaListener");
        for (MethodDecl m : t.methods) {
            boolean retryable = an.has(m.annos(), ctx, "RetryableTopic");
            for (Effective e : an.expandAll(m.annos(), ctx)) {
                EntryPoint ep = listener(t, new Decl(t, m), e);
                if (ep != null && retryable && e.base().equals("KafkaListener")) {
                    ep.attr("retryableTopic", true);
                }
                hasKafkaListener |= e.base().equals("KafkaListener");
            }
        }
        if (hasKafkaListener) {
            for (MethodDecl m : t.methods) {
                Effective dlt = an.first(m.annos(), ctx, "DltHandler");
                if (dlt != null) {
                    record("MESSAGING", "KAFKA", "<dlt>", t, new Decl(t, m), "@" + dlt.useSite().name(), Status.INFERRED)
                            .via("dlt-handler");
                }
            }
        }
        for (Effective e : an.expandAll(t.annos, ctx)) {
            String handlerAnno = switch (e.base()) {
                case "KafkaListener" -> "KafkaHandler";
                case "RabbitListener" -> "RabbitHandler";
                default -> null;
            };
            if (handlerAnno == null) {
                continue;
            }
            EntryPoint ep = listener(t, null, e);
            List<String> handlers = new ArrayList<>();
            for (MethodDecl m : t.methods) {
                if (hasAnno(m.annos(), handlerAnno)) {
                    handlers.add(m.name());
                }
            }
            if (ep != null && !handlers.isEmpty()) {
                ep.attr("handlers", handlers);
            }
        }
        if (!isConcrete(t)) {
            return;
        }
        SuperMatch listener = findSupertype(t, LISTENER_INTERFACES);
        if (listener != null) {
            SourceFile f = listener.declaringType().file;
            String broker = f.importsFrom("org.springframework.kafka") ? "KAFKA"
                    : f.importsFrom("org.springframework.amqp") ? "RABBIT"
                    : f.importsFrom("javax.jms") || f.importsFrom("jakarta.jms") ? "JMS"
                    : f.importsFrom("io.awspring.cloud.sqs") ? "SQS"
                    : f.importsFrom("org.springframework.data.redis") ? "REDIS" : "UNKNOWN";
            inheritedFrom(record("MESSAGING", broker, "<programmatic>", t, find(t, "onMessage"), listener.simple(),
                    Status.INFERRED, "onMessage").via("programmatic:" + listener.simple()), listener);
        }
        SuperMatch kinesis = findSupertype(t, Set.of("ShardRecordProcessor"));
        if (kinesis != null) {
            inheritedFrom(record("MESSAGING", "KINESIS", "<programmatic>", t, find(t, "processRecords"),
                    "ShardRecordProcessor", Status.INFERRED, "processRecords").via("programmatic:ShardRecordProcessor"), kinesis);
        }
        for (MethodDecl m : t.methods) {
            for (Poller p : POLLERS) {
                if (m.calls().containsKey(p.call()) && p.packages().stream().anyMatch(t.file::importsFrom)) {
                    record("MESSAGING", p.broker(), "<programmatic>", t, new Decl(t, m), p.call(), Status.INFERRED)
                            .via("programmatic:" + p.call());
                }
            }
        }
        if (findSupertype(t, Set.of("RouteBuilder", "EndpointRouteBuilder")) != null) {
            for (MethodDecl m : t.methods) {
                for (Expr from : m.fromCalls()) {
                    Value uri = ev.eval(from, ctx);
                    record("MESSAGING", "CAMEL", uri.text(), t, new Decl(t, m), "from", uri.status().worst(Status.INFERRED))
                            .via("camel-route");
                }
            }
        }
    }

    private EntryPoint listener(TypeDecl t, Decl method, Effective e) {
        String subkind;
        Value target;
        Status floor = Status.STATIC;
        switch (e.base()) {
            case "KafkaListener" -> {
                subkind = "KAFKA";
                Arg topics = e.attr("topics");
                Arg pattern = e.attr("topicPattern");
                if (topics != null) {
                    target = eval(topics);
                } else if (pattern != null) {
                    Value p = eval(pattern);
                    target = new Value(p.v(), "pattern:" + p.text(), p.status(), p.via());
                } else if (e.attr("topicPartitions") != null) {
                    target = new Value("<topicPartitions>", "<topicPartitions>", Status.INFERRED, Set.of());
                } else {
                    target = Value.unknown("unspecified");
                }
            }
            case "SqsListener" -> {
                subkind = "SQS";
                Arg q = e.attr("value", "queueNames");
                target = q == null ? Value.unknown("unspecified") : eval(q);
            }
            case "RabbitListener" -> {
                subkind = "RABBIT";
                Arg q = e.attr("queues");
                if (q != null) {
                    target = eval(q);
                } else if (e.attr("bindings") != null) {
                    target = new Value("<bindings>", "<bindings>", Status.INFERRED, Set.of());
                } else if (e.attr("queuesToDeclare") != null) {
                    target = new Value("<queuesToDeclare>", "<queuesToDeclare>", Status.INFERRED, Set.of());
                } else {
                    target = Value.unknown("unspecified");
                }
            }
            case "JmsListener" -> {
                subkind = "JMS";
                Arg d = e.attr("destination");
                target = d == null ? Value.unknown("unspecified") : eval(d);
            }
            case "StreamListener" -> {
                subkind = "STREAM";
                Arg b = e.attr("value", "target");
                target = b == null ? Value.unknown("unspecified") : eval(b);
            }
            case "InboundChannelAdapter" -> {
                subkind = "INTEGRATION";
                Arg c = e.attr("value", "channel");
                target = c == null ? Value.unknown("unspecified") : eval(c);
                floor = Status.INFERRED;
            }
            default -> {
                return null;
            }
        }
        EntryPoint ep = record("MESSAGING", subkind, joined(target), t, method, "@" + e.useSite().name(),
                target.status().worst(floor));
        target.via().forEach(ep::via);
        applyEffective(ep, e);
        for (String a : List.of("id", "groupId", "containerFactory", "factory", "concurrency")) {
            Arg arg = e.attr(a);
            if (arg != null) {
                ep.attr(a, eval(arg).text());
            }
        }
        return ep;
    }

    private void events(TypeDecl t) {
        Ctx ctx = Ctx.of(t);
        boolean classAsync = an.has(t.annos, ctx, "Async");
        for (MethodDecl m : t.methods) {
            for (Effective e : an.expandAll(m.annos(), ctx)) {
                String subkind = switch (e.base()) {
                    case "EventListener" -> "EVENT_LISTENER";
                    case "TransactionalEventListener" -> "TRANSACTIONAL_EVENT_LISTENER";
                    case "ApplicationModuleListener" -> "MODULE_LISTENER";
                    default -> null;
                };
                if (subkind == null) {
                    continue;
                }
                Value target;
                Arg classes = e.attr("classes", "value");
                if (classes != null) {
                    target = eval(classes);
                } else if (!m.params().isEmpty()) {
                    String p = JavaSourceParser.simpleTypeName(m.params().get(0).type());
                    target = Value.of(p, p);
                } else {
                    target = Value.of("<none>", "<none>");
                }
                EntryPoint ep = record("EVENT", subkind, joined(target), t, new Decl(t, m), "@" + e.useSite().name(),
                        target.status());
                target.via().forEach(ep::via);
                applyEffective(ep, e);
                Arg condition = e.attr("condition");
                if (condition != null) {
                    ep.attr("condition", eval(condition).text());
                }
                if (subkind.equals("TRANSACTIONAL_EVENT_LISTENER")) {
                    Arg phase = e.attr("phase");
                    ep.attr("phase", phase == null ? "AFTER_COMMIT" : Evaluator.enumName(phase.expr()));
                    Arg fallback = e.attr("fallbackExecution");
                    if (fallback != null) {
                        ep.attr("fallbackExecution", eval(fallback).text());
                    }
                }
                if (subkind.equals("MODULE_LISTENER")) {
                    ep.attr("phase", "AFTER_COMMIT").attr("async", true).via("modulith");
                }
                if (classAsync || an.has(m.annos(), ctx, "Async")) {
                    ep.attr("async", true);
                }
            }
        }
        if (!isConcrete(t)) {
            return;
        }
        SuperMatch smart = findSupertype(t, SMART_LISTENERS);
        if (smart != null) {
            EntryPoint ep = inheritedFrom(record("EVENT", "APPLICATION_LISTENER", "<supportsEventType>", t,
                    find(t, "onApplicationEvent"), smart.simple(), Status.INFERRED, "onApplicationEvent"), smart);
            if (classAsync) {
                ep.attr("async", true);
            }
            return;
        }
        SuperMatch listener = findSupertype(t, Set.of("ApplicationListener"));
        if (listener == null) {
            return;
        }
        String raw = listener.raw();
        String event;
        Status status = Status.STATIC;
        if (raw.contains("<")) {
            String arg = JavaSourceParser.simpleTypeName(raw.substring(raw.indexOf('<') + 1, raw.lastIndexOf('>')).trim());
            if (arg.matches("[A-Z][0-9]*")) {
                event = "<" + arg + ">";
                status = Status.UNKNOWN;
            } else {
                event = arg;
            }
        } else {
            event = "<raw>";
            status = Status.UNKNOWN;
        }
        EntryPoint ep = inheritedFrom(record("EVENT", "APPLICATION_LISTENER", event, t, find(t, "onApplicationEvent"),
                "ApplicationListener", status, "onApplicationEvent"), listener);
        if (classAsync) {
            ep.attr("async", true);
        }
    }

    private void beans(TypeDecl t) {
        Ctx ctx = Ctx.of(t);
        boolean stream = ws.declares("spring-cloud-stream") || t.file.importsFrom("org.springframework.cloud.stream");
        for (MethodDecl m : t.methods) {
            if (m.returnType() == null || !an.has(m.annos(), ctx, "Bean")) {
                continue;
            }
            String type = JavaSourceParser.simpleTypeName(m.returnType());
            Decl d = new Decl(t, m);
            if (stream && (type.equals("Consumer") || type.equals("Function"))) {
                record("MESSAGING", "STREAM", m.name(), t, d, "@Bean", Status.INFERRED).via("stream-function");
            } else if (type.endsWith("MessageListenerContainer")) {
                String broker = switch (type) {
                    case "ConcurrentMessageListenerContainer", "KafkaMessageListenerContainer" -> "KAFKA";
                    case "SimpleMessageListenerContainer", "DirectMessageListenerContainer" -> "RABBIT";
                    case "DefaultMessageListenerContainer" -> "JMS";
                    case "SqsMessageListenerContainer" -> "SQS";
                    default -> "UNKNOWN";
                };
                record("MESSAGING", broker, "<programmatic>", t, d, "@Bean", Status.INFERRED).via("container-bean");
            }
        }
    }
}
