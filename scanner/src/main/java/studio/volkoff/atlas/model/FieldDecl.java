package studio.volkoff.atlas.model;

/** A static field (or interface constant) with its initialiser; instance fields are not kept. */
public record FieldDecl(String name, String type, Expr init) {
}
