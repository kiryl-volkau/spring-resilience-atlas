import com.sun.source.tree.*;
import com.sun.source.util.*;

import javax.tools.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Prototype C (parse-only): measure javac Tree API parse time / failure rate and count mapping annotations. */
public class JavacParse {
    static final Set<String> SKIP = Set.of(".git", "target", "build", "node_modules", "test", "it", "integration-test", "testFixtures");
    static final Set<String> INTERESTING = Set.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping",
            "Scheduled", "EventListener", "TransactionalEventListener", "Async", "KafkaListener", "SqsListener", "RabbitListener", "JmsListener");

    public static void main(String[] a) throws Exception {
        long t0 = System.nanoTime();
        Path root = Paths.get(a[0]).toAbsolutePath();
        List<File> files = new ArrayList<>();
        try (Stream<Path> w = Files.walk(root)) {
            w.filter(p -> { for (Path s : root.relativize(p)) if (SKIP.contains(s.toString())) return false; return p.toString().endsWith(".java"); })
             .forEach(p -> files.add(p.toFile()));
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, null);
        JavacTask task = (JavacTask) jc.getTask(null, fm, diags, List.of("--release", "26", "-proc:none"), null, fm.getJavaFileObjectsFromFiles(files));
        long t1 = System.nanoTime();
        Iterable<? extends CompilationUnitTree> units = task.parse();
        long t2 = System.nanoTime();
        AtomicInteger annos = new AtomicInteger(), cus = new AtomicInteger();
        for (CompilationUnitTree cu : units) {
            cus.incrementAndGet();
            new TreeScanner<Void, Void>() {
                @Override public Void visitAnnotation(AnnotationTree n, Void v) {
                    String s = n.getAnnotationType().toString(); s = s.substring(s.lastIndexOf('.') + 1);
                    if (INTERESTING.contains(s)) annos.incrementAndGet();
                    return super.visitAnnotation(n, v);
                }
            }.scan(cu, null);
        }
        long errors = diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).count();
        System.out.printf(Locale.ROOT, "{\"files\":%d,\"units\":%d,\"parse_errors\":%d,\"setup_s\":%.2f,\"parse_s\":%.2f,\"walk_s\":%.2f,\"wall_s\":%.2f,\"interesting_annotations\":%d}%n",
                files.size(), cus.get(), errors, (t1 - t0) / 1e9, (t2 - t1) / 1e9, (System.nanoTime() - t2) / 1e9, (System.nanoTime() - t0) / 1e9, annos.get());
        diags.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).limit(5).forEach(d -> System.err.println(d));
    }
}
