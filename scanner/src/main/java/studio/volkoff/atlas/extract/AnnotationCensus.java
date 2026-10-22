package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Anno;
import studio.volkoff.atlas.model.MethodDecl;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations;
import studio.volkoff.atlas.resolve.Evaluator.Ctx;
import studio.volkoff.atlas.resolve.Index;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * What the extraction rules could not see: annotations from outside the source set that look
 * like entry points, controllers with library superclasses, and missing enabling annotations.
 * Feeds {@code manifest.warnings}.
 */
public final class AnnotationCensus {

    private static final Pattern ENTRY_LIKE = Pattern.compile(
            ".*(Mapping|Listener|Handler|Scheduled|Controller|Endpoint|Job|Exchange|Operation|Subscribe|Consumer)");

    /** Names the rules handle, plus well-known framework annotations with an entry-like name that are not entry points. */
    private static final Set<String> KNOWN = Set.of(
            "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "RenderMapping", "ActionMapping", "ResourceMapping", "EventMapping",
            "MessageMapping", "SubscribeMapping", "ConnectMapping", "QueryMapping", "MutationMapping",
            "SubscriptionMapping", "SchemaMapping", "BatchMapping", "KafkaListener", "SqsListener", "RabbitListener",
            "JmsListener", "EventListener", "TransactionalEventListener", "ApplicationModuleListener", "StreamListener",
            "KafkaHandler", "RabbitHandler", "DltHandler", "Scheduled", "Controller", "RestController", "Endpoint",
            "WebEndpoint", "JmxEndpoint", "RestControllerEndpoint", "ControllerEndpoint", "ReadOperation",
            "WriteOperation", "DeleteOperation", "HttpExchange", "GetExchange", "PostExchange", "PutExchange",
            "DeleteExchange", "PatchExchange",
            "ExceptionHandler", "MessageExceptionHandler", "GraphQlExceptionHandler",
            "Operation", "ApiOperation",
            "Mapping", "Mappings", "BeanMapping", "IterableMapping", "MapMapping", "ValueMapping", "ValueMappings",
            "SubclassMapping", "SubclassMappings", "AfterMapping", "BeforeMapping", "SqlResultSetMapping",
            "SqlResultSetMappings");

    public record Unresolved(String name, int count, List<String> samples) {
    }

    private final Map<String, Unresolved> unresolved = new TreeMap<>();
    private final List<String> libraryControllerParents = new ArrayList<>();
    private boolean enableScheduling;
    private boolean enableAsync;
    private boolean usesAsync;

    public AnnotationCensus(Index index) {
        Annotations an = new Annotations(index);
        for (TypeDecl t : index.types()) {
            Ctx ctx = Ctx.of(t);
            count(index, t, t.annos, t.line);
            for (MethodDecl m : t.methods) {
                count(index, t, m.annos(), m.line());
                usesAsync |= an.has(m.annos(), ctx, "Async");
            }
            enableScheduling |= an.has(t.annos, ctx, "EnableScheduling");
            enableAsync |= an.has(t.annos, ctx, "EnableAsync");
            usesAsync |= an.has(t.annos, ctx, "Async");
            if (t.hasSuperclass && index.superclass(t) == null && t.kind == TypeDecl.Kind.CLASS
                    && an.has(t.annos, ctx, "Controller") && libraryControllerParents.size() < 1000) {
                libraryControllerParents.add(t.simpleName + " extends " + Index.stripGenerics(t.superTypes.get(0))
                        + " (" + t.file.relPath() + ":" + t.line + ")");
            }
        }
    }

    private void count(Index index, TypeDecl t, List<Anno> annos, int line) {
        for (Anno a : annos) {
            String simple = a.simpleName();
            if (KNOWN.contains(simple) || !ENTRY_LIKE.matcher(simple).matches()) {
                continue;
            }
            Index.Resolved r = index.resolveType(a.name(), t, t.file, null);
            if (r != null && r.type().kind == TypeDecl.Kind.ANNOTATION) {
                continue;
            }
            Unresolved previous = unresolved.get(simple);
            List<String> samples = previous == null ? new ArrayList<>() : new ArrayList<>(previous.samples());
            if (samples.size() < 3) {
                samples.add(t.file.relPath() + ":" + line);
            }
            unresolved.put(simple, new Unresolved(simple, previous == null ? 1 : previous.count() + 1, samples));
        }
    }

    /** Unresolved entry-like annotations, most frequent first. */
    public List<Unresolved> unresolved() {
        List<Unresolved> list = new ArrayList<>(unresolved.values());
        list.sort(Comparator.comparingInt(Unresolved::count).reversed().thenComparing(Unresolved::name));
        return list;
    }

    public List<String> libraryControllerParents() {
        return libraryControllerParents;
    }

    public boolean enableScheduling() {
        return enableScheduling;
    }

    public boolean asyncWithoutEnable() {
        return usesAsync && !enableAsync;
    }
}
