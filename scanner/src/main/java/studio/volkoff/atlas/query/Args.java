package studio.volkoff.atlas.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code command [positional...] [--flag value]...}; a flag without a value is {@code "true"}. */
public final class Args {

    public final String command;
    public final List<String> positional = new ArrayList<>();
    private final Map<String, String> flags = new LinkedHashMap<>();

    public Args(String[] argv) {
        String cmd = null;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                String name = a.substring(2);
                String value = "true";
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    value = argv[++i];
                }
                flags.put(name, value);
            } else if (cmd == null) {
                cmd = a;
            } else {
                positional.add(a);
            }
        }
        this.command = cmd == null ? "help" : cmd;
    }

    public String get(String name) {
        return flags.get(name);
    }

    public String get(String name, String fallback) {
        return flags.getOrDefault(name, fallback);
    }

    /** A boolean switch: present without a value, or with any value but {@code false}. */
    public boolean flag(String name) {
        String v = flags.get(name);
        return v != null && !v.equalsIgnoreCase("false");
    }

    public int getInt(String name, int fallback, int min, int max) {
        String v = flags.get(name);
        if (v == null) {
            return fallback;
        }
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(v)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + name + " expects a number, got '" + v + "'");
        }
    }

    public Map<String, String> flags() {
        return flags;
    }
}
