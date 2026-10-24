package studio.volkoff.atlas.report;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The page templates under {@code /report/}: shared theme and diagram code inlined, then title and data filled in. */
public final class Page {

    private Page() {
    }

    /** The template with {@code theme.css} and {@code diagram.js} put in place of their markers. */
    public static String template(String name) throws IOException {
        return resource(name).replace("/*__THEME__*/", resource("theme.css")).replace("/*__DIAGRAM__*/", resource("diagram.js"));
    }

    /** Puts the title and the data into the template in one pass, so neither can inject into the other. */
    public static String fill(String template, String title, Object data) {
        // Escaping every "<" keeps "</script>" and "<!--" in snippets from ending the data block.
        String payload = Json.write(data).replace("<", "\\u003c");
        String escapedTitle = escapeHtml(title);
        StringBuilder out = new StringBuilder(template.length() + payload.length());
        int i = 0;
        while (true) {
            int t = template.indexOf("__TITLE__", i);
            int d = template.indexOf("__DATA__", i);
            if (t < 0 && d < 0) {
                break;
            }
            boolean titleFirst = t >= 0 && (d < 0 || t < d);
            int at = titleFirst ? t : d;
            out.append(template, i, at).append(titleFirst ? escapedTitle : payload);
            i = at + (titleFirst ? "__TITLE__".length() : "__DATA__".length());
        }
        return out.append(template.substring(i)).toString();
    }

    public static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Page.class.getResourceAsStream("/report/" + name)) {
            if (in == null) {
                throw new IllegalStateException("report/" + name + " is missing from the scanner build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
