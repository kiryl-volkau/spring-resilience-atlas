package studio.volkoff.atlas.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TypeDecl {

    public enum Kind { CLASS, INTERFACE, ENUM, RECORD, ANNOTATION }

    public final String fqn;
    public final String simpleName;
    /** Simple name prefixed by enclosing type names: {@code Outer.Inner}. */
    public final String displayName;
    public final Kind kind;
    public final boolean isAbstract;
    public final int line;
    public final List<Anno> annos;
    /** Supertype texts as written, generics included; the superclass first if present. */
    public final List<String> superTypes;
    public final boolean hasSuperclass;
    public final List<FieldDecl> fields;
    /** Every field, static or not, by name: its declared type text. */
    public final Map<String, String> fieldTypes;
    public final List<String> enumConstants;
    public final List<MethodDecl> methods;
    /** Simple names of types this type references in field types and {@code new} expressions. */
    public final Set<String> referencedTypes;
    public final TypeDecl enclosing;
    public final List<TypeDecl> nested = new ArrayList<>();
    public SourceFile file;

    public TypeDecl(String fqn, String simpleName, String displayName, Kind kind, boolean isAbstract, int line,
                    List<Anno> annos, List<String> superTypes, boolean hasSuperclass, List<FieldDecl> fields,
                    Map<String, String> fieldTypes, List<String> enumConstants, List<MethodDecl> methods,
                    Set<String> referencedTypes, TypeDecl enclosing) {
        this.fqn = fqn;
        this.simpleName = simpleName;
        this.displayName = displayName;
        this.kind = kind;
        this.isAbstract = isAbstract;
        this.line = line;
        this.annos = annos;
        this.superTypes = superTypes;
        this.hasSuperclass = hasSuperclass;
        this.fields = fields;
        this.fieldTypes = fieldTypes;
        this.enumConstants = enumConstants;
        this.methods = methods;
        this.referencedTypes = referencedTypes;
        this.enclosing = enclosing;
    }

    public boolean isConcreteClass() {
        return (kind == Kind.CLASS || kind == Kind.RECORD || kind == Kind.ENUM) && !isAbstract;
    }

    public String packageName() {
        return file.pkg();
    }

    @Override
    public String toString() {
        return fqn;
    }
}
