package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.query.Args;

import java.nio.file.Path;

/** {@code audit plan|next|record}: the quick check's mechanical half, run by the audit skill. */
public final class Audit {

    private Audit() {
    }

    public static String run(Path repo, Path atlasDir, Path auditDir, Args args) throws Exception {
        AuditStore store = new AuditStore(auditDir);
        String sub = args.positional.isEmpty() ? "" : args.positional.get(0);
        return switch (sub) {
            case "plan" -> Plan.plan(repo, atlasDir, store, args);
            case "next" -> Plan.next(repo, atlasDir, store, arg(args, "next <plan-id>"), args);
            case "record" -> Record.record(repo, atlasDir, store, arg(args, "record <batch-id>"));
            default -> throw new IllegalArgumentException("usage: audit plan [filters] | audit next <plan-id> [--batches N] | audit record <batch-id>");
        };
    }

    private static String arg(Args args, String usage) {
        if (args.positional.size() < 2) {
            throw new IllegalArgumentException("usage: audit " + usage);
        }
        return args.positional.get(1);
    }
}
