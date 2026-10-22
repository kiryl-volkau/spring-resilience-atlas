package studio.volkoff.atlas.model;

import java.util.Map;

/**
 * An annotation use as written in the source.
 *
 * @param name annotation type name as written, simple or qualified
 * @param args attribute name to value; a single unnamed argument is stored under {@code value}
 * @param text the annotation's source text, for reporting
 */
public record Anno(String name, Map<String, Expr> args, int line, String text) {

    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
