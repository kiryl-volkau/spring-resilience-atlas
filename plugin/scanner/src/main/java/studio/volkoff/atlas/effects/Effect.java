package studio.volkoff.atlas.effects;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One side effect observed on an entry point's call path.
 *
 * @param signal       one of {@link Signals}
 * @param detail       what was matched, such as {@code OrderRepository.save} or {@code @Async}
 * @param symbol       the {@code Type.method} whose body or declaration holds the evidence
 * @param hop          bean boundaries crossed from the entry method: 0 is the entry bean itself
 * @param inTx         the effect happens inside a read-write transaction opened on this path
 * @param inReadOnlyTx the effect happens inside a read-only transaction, which still holds a connection
 */
public record Effect(String signal, String detail, String file, int line, String symbol, int hop, boolean inTx,
                     boolean inReadOnlyTx) {

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signal", signal);
        m.put("detail", detail);
        m.put("file", file);
        m.put("line", line);
        m.put("symbol", symbol);
        m.put("hop", hop);
        m.put("inTx", inTx);
        if (inReadOnlyTx) {
            m.put("inReadOnlyTx", true);
        }
        return m;
    }
}
