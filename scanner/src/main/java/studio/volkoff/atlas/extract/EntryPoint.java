package studio.volkoff.atlas.extract;

import studio.volkoff.atlas.model.Origin;
import studio.volkoff.atlas.model.Status;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One record of {@code entrypoints.jsonl}; field semantics are defined in {@code schemas/entrypoint-rules.md}. */
public final class EntryPoint {

    public String id;
    public final String kind;
    public final String subkind;
    public final String target;
    public final String module;
    public final String cls;
    public final String method;
    public final String file;
    public final int line;
    public final String anno;
    public Status status;
    public final Origin origin;
    public final List<String> via = new ArrayList<>();
    public final Map<String, Object> attrs = new LinkedHashMap<>();

    public EntryPoint(String kind, String subkind, String target, String module, String cls, String method,
                      String file, int line, String anno, Status status, Origin origin) {
        this.kind = kind;
        this.subkind = subkind;
        this.target = target;
        this.module = module;
        this.cls = cls;
        this.method = method;
        this.file = file;
        this.line = line;
        this.anno = anno;
        this.status = status;
        this.origin = origin;
    }

    public EntryPoint via(String tag) {
        if (!via.contains(tag)) {
            via.add(tag);
        }
        return this;
    }

    public EntryPoint atLeast(Status s) {
        status = status.worst(s);
        return this;
    }

    public EntryPoint attr(String key, Object value) {
        if (value != null && !(value instanceof String str && str.isEmpty())) {
            attrs.put(key, value);
        }
        return this;
    }

    public String key() {
        return kind + "|" + module + "|" + cls + "#" + method + "|" + subkind + "|" + target;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("subkind", subkind);
        m.put("target", target);
        m.put("module", module);
        m.put("cls", cls);
        m.put("method", method);
        m.put("file", file);
        m.put("line", line);
        m.put("anno", anno);
        m.put("status", status.name());
        m.put("origin", origin.name());
        m.put("via", via);
        m.put("placeholder", target.contains("${") || target.contains("#{"));
        m.put("attrs", attrs);
        return m;
    }
}
