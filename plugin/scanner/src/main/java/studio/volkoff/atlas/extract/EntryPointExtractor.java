package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.resolve.Annotations;
import studio.volkoff.atlas.resolve.Evaluator;
import studio.volkoff.atlas.resolve.Index;
import studio.volkoff.atlas.scan.Workspace;

import java.util.ArrayList;
import java.util.List;

/** Produces entry-point records from parsed Java sources, per {@code schemas/entrypoint-rules.md}. */
public final class EntryPointExtractor {

    private final Index index;
    private final Workspace ws;
    private int scheduledRecords;

    public EntryPointExtractor(Index index, Workspace ws) {
        this.index = index;
        this.ws = ws;
    }

    public List<EntryPoint> extract() {
        Evaluator ev = new Evaluator(index);
        List<EntryPoint> out = new ArrayList<>();
        ExtractorBase.Context context = new ExtractorBase.Context(index, ev, new Annotations(index), ws, out);
        new HttpExtractor(context, new WebhookTagger(index, ev, ws)).extract();
        ListenerExtractor listeners = new ListenerExtractor(context);
        TriggerExtractor triggers = new TriggerExtractor(context);
        ProtocolExtractor protocols = new ProtocolExtractor(context);
        for (TypeDecl t : index.types()) {
            if (t.kind == TypeDecl.Kind.ANNOTATION) {
                continue;
            }
            listeners.extract(t);
            triggers.extract(t);
            protocols.extract(t);
        }
        scheduledRecords = triggers.scheduledRecords;
        return out;
    }

    /** {@code @Scheduled} records produced by the last {@link #extract()}. */
    public int scheduledRecords() {
        return scheduledRecords;
    }

    public static String joinPaths(String a, String b) {
        return HttpExtractor.joinPaths(a, b);
    }
}
