package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.flow.Flows;
import studio.volkoff.atlas.query.Args;

import java.nio.file.Path;
import java.util.Map;

/** {@code audit plan|next|record} and {@code audit deep plan|record-step}: the mechanical half of the audits, run by the audit skill. */
public final class Audit {

    private Audit() {
    }

    public static String run(Path repo, Path atlasDir, Path auditDir, Path flowsDir, Args args) throws Exception {
        AuditStore store = new AuditStore(auditDir);
        Flows flows = new Flows(repo, atlasDir, auditDir, flowsDir);
        String sub = args.positional.isEmpty() ? "" : args.positional.get(0);
        return switch (sub) {
            case "plan" -> Plan.plan(repo, atlasDir, store, args);
            case "next" -> {
                String id = arg(args, "next <plan-id>");
                Map<String, Object> plan = store.plan();
                if (plan != null && "deep".equals(plan.get("kind"))) {
                    if (!String.valueOf(plan.get("id")).equals(id)) {
                        throw new IllegalArgumentException("plan " + id + " is not the current plan (" + plan.get("id")
                                + "); run the audit skill again to plan and confirm");
                    }
                    yield Deep.next(repo, atlasDir, store, flows, plan, id);
                }
                yield Plan.next(repo, atlasDir, store, id, args);
            }
            case "record" -> Record.record(repo, atlasDir, store, arg(args, "record <batch-id>"));
            case "deep" -> {
                if (!"plan".equals(arg(args, "deep plan [--flow F | --places … | --ops …] [--max-flows N]"))) {
                    throw new IllegalArgumentException("usage: audit deep plan [--flow F | --places … | --ops …] [--max-flows N]; audit next runs it");
                }
                yield Deep.plan(repo, atlasDir, store, flows, args);
            }
            case "record-step" -> Deep.recordStep(repo, atlasDir, store, flows, arg(args, "record-step <FLOW-id>:<TRACE|SCENARIOS|VERIFY>"));
            default -> throw new IllegalArgumentException("usage: audit plan [filters] | audit deep plan [filters] | audit next <plan-id> [--batches N]"
                    + " | audit record <batch-id> | audit record-step <FLOW-id>:<STEP> | audit record-flow <FLOW-id>");
        };
    }

    private static String arg(Args args, String usage) {
        if (args.positional.size() < 2) {
            throw new IllegalArgumentException("usage: audit " + usage);
        }
        return args.positional.get(1);
    }
}
