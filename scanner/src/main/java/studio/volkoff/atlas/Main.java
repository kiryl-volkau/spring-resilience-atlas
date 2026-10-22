package studio.volkoff.atlas;

import studio.volkoff.atlas.query.Args;
import studio.volkoff.atlas.query.AtlasStore;
import studio.volkoff.atlas.query.EvidenceVerifier;
import studio.volkoff.atlas.query.Query;
import studio.volkoff.atlas.report.ReportBuilder;
import studio.volkoff.atlas.scan.Scanner;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class Main {

    private static final String HELP = """
            resilience-atlas %s: static entry-point atlas for Spring repositories

            java scanner/Launch.java <command> [options]    (or: java -jar resilience-atlas.jar <command> [options])

              scan                         build .reliability/atlas for --repo (default .)
              report [--scan] [--open]     write .reliability/report.html from the atlas and any audit records;
                                           --scan rebuilds the atlas first, --open opens the page in a browser
                  --out FILE  --title TEXT  --audit DIR (.reliability/audit)  --flows DIR (.reliability/flows)
              stats                        counts by kind, status and module; warnings
              entrypoints                  list entry points
                  --kind HTTP,MESSAGING,SCHEDULED,EVENT,LIFECYCLE,SOAP,JOB,WEBSOCKET,GRAPHQL,GRPC,ACTUATOR,ROUTER,PORTLET
                  --subkind S   --status S   --origin O   --tag webhook|oauth-callback|webhook-management|unauthenticated-write
                  --module PREFIX  --package PREFIX  --class TEXT  --target TEXT  --path-prefix /api  --domain D
                  --via TEXT  --file TEXT  --group-by module|package|class|path|kind|subkind|domain|status [--depth N]
              types                        list types: --implements T --annotated A --stereotype S --name TEXT --package P
              record <EP-id|type fqn>      one full record as JSON
              packages [--depth N]         package tree with type and entry-point counts
              domains                      domains from domains.jsonl with entry-point counts
              verify-evidence <file>       check {file,line,snippet} evidence in a flow or finding document; exit 1 on any problem
              doc <name>                   print a bundled reference: atlas-cli.md, entrypoint-rules.md, *.schema.json

            common: --repo DIR  --atlas DIR  --format tsv|jsonl|count  --fields a,b|+c|all  --limit N (50, max 500)  --offset N
            """;

    public static void main(String[] argv) {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        Args args = new Args(argv);
        Path repo = Path.of(args.get("repo", ".")).toAbsolutePath().normalize();
        Path atlasDir = args.get("atlas") != null ? Path.of(args.get("atlas")).toAbsolutePath().normalize()
                : repo.resolve(".reliability").resolve("atlas");
        try {
            switch (args.command) {
                case "help", "--help", "-h" -> out.print(HELP.formatted(Scanner.VERSION));
                case "version" -> out.println(Scanner.VERSION);
                case "scan" -> out.print(scanSummary(new Scanner().scan(repo, atlasDir), repo, atlasDir));
                case "report" -> {
                    if (args.flag("scan")) {
                        out.print(scanSummary(new Scanner().scan(repo, atlasDir), repo, atlasDir));
                    }
                    Path reliability = repo.resolve(".reliability");
                    Path outFile = args.get("out") != null ? Path.of(args.get("out")).toAbsolutePath().normalize()
                            : reliability.resolve("report.html");
                    ReportBuilder.Summary s = new ReportBuilder().build(new ReportBuilder.Options(repo, atlasDir,
                            args.get("audit") != null ? Path.of(args.get("audit")).toAbsolutePath() : reliability.resolve("audit"),
                            args.get("flows") != null ? Path.of(args.get("flows")).toAbsolutePath() : reliability.resolve("flows"),
                            outFile, args.get("title")));
                    out.print(reportSummary(s, repo));
                    if (args.flag("open")) {
                        out.println(open(outFile));
                    }
                }
                case "doc" -> out.print(doc(args.positional.isEmpty() ? null : args.positional.get(0)));
                case "verify-evidence" -> {
                    if (args.positional.isEmpty()) {
                        throw new IllegalArgumentException("usage: verify-evidence <flow-or-finding.json>");
                    }
                    EvidenceVerifier.Result r = new EvidenceVerifier(repo).verify(repo.resolve(args.positional.get(0)));
                    out.print(r.report());
                    if (!r.passed()) {
                        System.exit(1);
                    }
                }
                default -> out.print(new Query(AtlasStore.open(repo, atlasDir), args).run());
            }
        } catch (AtlasStore.Missing | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            err.println("error: " + e);
            System.exit(1);
        }
    }

    private static final List<String> DOCS = List.of("atlas-cli.md", "not-in-atlas.md", "entrypoint-rules.md", "entrypoint.schema.json",
            "domain.schema.json", "flow.schema.json", "finding.schema.json");

    private static String doc(String name) throws java.io.IOException {
        if (name == null || !DOCS.contains(name)) {
            throw new IllegalArgumentException("usage: doc <" + String.join("|", DOCS) + ">");
        }
        try (java.io.InputStream in = Main.class.getResourceAsStream("/docs/" + name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the scanner build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String reportSummary(ReportBuilder.Summary s, Path repo) {
        StringBuilder out = new StringBuilder();
        Path shown = s.out().startsWith(repo) ? repo.relativize(s.out()) : s.out();
        out.append("# report written to ").append(shown.toString().replace('\\', '/')).append('\n');
        out.append("# url: ").append(s.out().toUri()).append('\n');
        out.append("# operations: ").append(s.operations()).append(' ').append(s.byKind()).append('\n');
        Map<String, Integer> l = s.byLevel();
        out.append("# static attention: high ").append(l.get("HIGH")).append(", medium ").append(l.get("MEDIUM"))
                .append(", low ").append(l.get("LOW")).append(", none ").append(l.get("NONE"))
                .append(", not analysed ").append(l.get(ReportBuilder.NOT_ANALYSED))
                .append(" (Kotlin, regex fallback or synthetic: no signals, so no level)").append('\n');
        Map<String, Integer> c = s.byColour();
        if (c.get("GREY") == s.operations()) {
            out.append("# audit: not run; every operation is \"not audited\" (attention levels are static hints, not verdicts)\n");
        } else {
            out.append("# audit ").append(s.auditStatus()).append(": broken ").append(c.get("RED")).append(", smells ")
                    .append(c.get("YELLOW")).append(", ok ").append(c.get("GREEN")).append(", not audited ").append(c.get("GREY")).append('\n');
        }
        if (!s.top().isEmpty()) {
            out.append("rank\tlevel\tid\tkind\ttarget\thandler\tfirst pattern\n");
            int i = 1;
            for (Map<String, Object> op : s.top()) {
                Object reasons = op.get("reasons");
                String first = reasons instanceof List<?> r && !r.isEmpty() && r.get(0) instanceof Map<?, ?> m ? String.valueOf(m.get("title")) : "";
                out.append(i++).append('\t').append(op.get("level")).append('\t').append(op.get("id")).append('\t')
                        .append(op.get("kind")).append(' ').append(op.get("subkind")).append('\t').append(op.get("target"))
                        .append('\t').append(op.get("cls")).append('.').append(op.get("method")).append('\t').append(first).append('\n');
            }
        }
        for (String w : s.warnings()) {
            out.append("# blind spot: ").append(w).append('\n');
        }
        for (String p : s.auditProblems()) {
            out.append("# audit file problem (row skipped): ").append(p).append('\n');
        }
        return out.toString();
    }

    /** Opens the report in the default browser where a desktop exists; otherwise says how to open it. */
    private static String open(Path file) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(file.toUri());
                return "# opened in the default browser";
            }
        } catch (Exception | Error e) {
            // A headless JVM or a missing browser falls through to the hint below.
        }
        return "# could not open a browser here; open the url above";
    }

    private static String scanSummary(Scanner.Summary s, Path repo, Path atlasDir) {
        Map<String, Object> m = s.manifest();
        StringBuilder out = new StringBuilder();
        Object commit = m.get("commit");
        Path shown = atlasDir.startsWith(repo) ? repo.relativize(atlasDir) : atlasDir;
        out.append("# atlas written to ").append(shown.toString().replace('\\', '/'))
                .append(commit == null ? " (no git commit)" : " @" + commit.toString().substring(0, 7)
                        + (Boolean.TRUE.equals(m.get("dirty")) ? " with uncommitted changes" : ""))
                .append(" in ").append(m.get("durationMs")).append(" ms\n");
        if (m.get("files") instanceof Map<?, ?> f) {
            out.append("# files: java=").append(f.get("java")).append(" kotlin=").append(f.get("kotlin"))
                    .append(" generated=").append(f.get("generated")).append(" parse-failures=").append(f.get("parseFailures")).append('\n');
        }
        out.append("kind\tcount\n");
        if (m.get("entrypoints") instanceof Map<?, ?> counts) {
            counts.forEach((k, v) -> out.append(k).append('\t').append(v).append('\n'));
        }
        out.append("# statuses: ").append(m.get("statuses")).append('\n');
        if (m.get("warnings") instanceof List<?> w) {
            for (Object x : w) {
                out.append("# warning: ").append(x).append('\n');
            }
        }
        return out.toString();
    }
}
