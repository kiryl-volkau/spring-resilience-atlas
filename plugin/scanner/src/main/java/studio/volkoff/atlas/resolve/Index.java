package studio.volkoff.atlas.resolve;

import studio.volkoff.atlas.model.SourceFile;
import studio.volkoff.atlas.model.TypeDecl;
import studio.volkoff.atlas.scan.JavaSourceParser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every type in the source set, with scoped name resolution. A name is never resolved by searching
 * the whole repository for a matching simple name.
 */
public final class Index {

    /** A type found by {@link #resolveType}; {@code viaWildcard} when only a wildcard import matched. */
    public record Resolved(TypeDecl type, boolean viaWildcard) {
    }

    public final List<SourceFile> files;
    private final Map<String, TypeDecl> byFqn = new HashMap<>();
    /** Every type per name: several apps in one repository often repeat a class name. */
    private final Map<String, List<TypeDecl>> allByFqn = new HashMap<>();
    private final Map<TypeDecl, List<TypeDecl>> supers = new HashMap<>();
    private final Map<TypeDecl, List<String>> unresolvedSupers = new HashMap<>();
    private final Map<TypeDecl, List<TypeDecl>> subtypes = new HashMap<>();

    public Index(List<SourceFile> files) {
        this.files = files;
        for (SourceFile f : files) {
            for (TypeDecl t : f.types()) {
                byFqn.putIfAbsent(t.fqn, t);
                allByFqn.computeIfAbsent(t.fqn, k -> new ArrayList<>()).add(t);
            }
        }
        for (SourceFile f : files) {
            for (TypeDecl t : f.types()) {
                List<TypeDecl> resolved = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (String s : t.superTypes) {
                    String raw = stripGenerics(s);
                    Resolved r = resolveType(raw, t.enclosing != null ? t.enclosing : null, f, t);
                    if (r != null) {
                        resolved.add(r.type());
                        subtypes.computeIfAbsent(r.type(), k -> new ArrayList<>()).add(t);
                    } else {
                        missing.add(JavaSourceParser.simpleTypeName(raw));
                    }
                }
                supers.put(t, resolved);
                unresolvedSupers.put(t, missing);
            }
        }
    }

    public Iterable<TypeDecl> types() {
        List<TypeDecl> all = new ArrayList<>();
        for (SourceFile f : files) {
            all.addAll(f.types());
        }
        return all;
    }

    public TypeDecl byFqn(String fqn) {
        return byFqn.get(fqn);
    }

    /** The type with this name declared in this file, else the first one indexed under the name. */
    public TypeDecl byFqn(String fqn, String relFile) {
        for (TypeDecl t : allByFqn.getOrDefault(fqn, List.of())) {
            if (t.file.relPath().equals(relFile)) {
                return t;
            }
        }
        return byFqn.get(fqn);
    }

    /** Among types sharing a name (several apps in one repository), the one in the referring file's module. */
    private TypeDecl pick(String fqn, SourceFile from) {
        List<TypeDecl> all = allByFqn.get(fqn);
        if (all == null || all.size() < 2) {
            return byFqn.get(fqn);
        }
        for (TypeDecl t : all) {
            if (t.file.module().equals(from.module())) {
                return t;
            }
        }
        return byFqn.get(fqn);
    }

    public List<TypeDecl> supertypes(TypeDecl t) {
        return supers.getOrDefault(t, List.of());
    }

    public List<String> unresolvedSupertypes(TypeDecl t) {
        return unresolvedSupers.getOrDefault(t, List.of());
    }

    public List<TypeDecl> subtypes(TypeDecl t) {
        return subtypes.getOrDefault(t, List.of());
    }

    /** The type, its superclasses nearest first, then all interfaces breadth first. */
    public List<TypeDecl> linearize(TypeDecl t) {
        List<TypeDecl> order = new ArrayList<>();
        Set<TypeDecl> seen = new LinkedHashSet<>();
        TypeDecl c = t;
        while (c != null && seen.add(c)) {
            order.add(c);
            c = superclass(c);
        }
        Deque<TypeDecl> queue = new ArrayDeque<>(order);
        while (!queue.isEmpty()) {
            TypeDecl x = queue.poll();
            for (TypeDecl s : supertypes(x)) {
                if (s.kind == TypeDecl.Kind.INTERFACE && seen.add(s)) {
                    order.add(s);
                    queue.add(s);
                }
            }
        }
        return order;
    }

    public TypeDecl superclass(TypeDecl t) {
        if (!t.hasSuperclass) {
            return null;
        }
        String raw = stripGenerics(t.superTypes.get(0));
        Resolved r = resolveType(raw, t.enclosing, t.file, t);
        return r == null || r.type().kind == TypeDecl.Kind.INTERFACE ? null : r.type();
    }

    /** All supertypes in the source set, transitively, excluding {@code t}. */
    public Set<TypeDecl> allSupertypes(TypeDecl t) {
        Set<TypeDecl> out = new LinkedHashSet<>();
        Deque<TypeDecl> queue = new ArrayDeque<>(supertypes(t));
        while (!queue.isEmpty()) {
            TypeDecl s = queue.poll();
            if (out.add(s)) {
                queue.addAll(supertypes(s));
            }
        }
        return out;
    }

    /**
     * Resolves a simple or dotted type name as seen from {@code context} (the type whose body contains
     * the reference, or {@code null} at file level) in {@code file}. {@code self} is excluded from the
     * nested-type search so a type's own supertype clause does not resolve to itself. Returns
     * {@code null} for types outside the source set.
     */
    public Resolved resolveType(String name, TypeDecl context, SourceFile file, TypeDecl self) {
        String[] parts = name.split("\\.");
        String head = parts[0];
        TypeDecl found = null;
        boolean wildcard = false;

        for (TypeDecl c = context; c != null && found == null; c = c.enclosing) {
            found = nestedOrSelf(c, head, self);
        }
        if (found == null) {
            for (String imp : file.imports()) {
                if (imp.equals(head) || imp.endsWith("." + head)) {
                    found = pick(imp, file);
                    if (found == null) {
                        return parts.length > 1 ? fullyQualified(parts) : null;
                    }
                    break;
                }
            }
        }
        if (found == null && !file.pkg().isEmpty()) {
            found = pick(file.pkg() + "." + head, file);
        } else if (found == null) {
            found = pick(head, file);
        }
        if (found == null) {
            TypeDecl match = null;
            int matches = 0;
            for (String w : file.wildcardImports()) {
                TypeDecl t = byFqn.get(w + "." + head);
                if (t != null) {
                    match = t;
                    matches++;
                }
            }
            if (matches == 1) {
                found = match;
                wildcard = true;
            }
        }
        if (found == null) {
            return parts.length > 1 ? fullyQualified(parts) : null;
        }
        for (int i = 1; i < parts.length && found != null; i++) {
            found = nested(found, parts[i]);
        }
        return found == null ? null : new Resolved(found, wildcard);
    }

    private Resolved fullyQualified(String[] parts) {
        for (int end = parts.length; end >= 1; end--) {
            TypeDecl t = byFqn.get(String.join(".", java.util.Arrays.copyOfRange(parts, 0, end)));
            if (t != null) {
                for (int i = end; i < parts.length && t != null; i++) {
                    t = nested(t, parts[i]);
                }
                return t == null ? null : new Resolved(t, false);
            }
        }
        return null;
    }

    private TypeDecl nestedOrSelf(TypeDecl c, String name, TypeDecl self) {
        if (c.simpleName.equals(name) && c != self) {
            return c;
        }
        TypeDecl n = nested(c, name);
        return n == self ? null : n;
    }

    private TypeDecl nested(TypeDecl t, String name) {
        return nested(t, name, new java.util.HashSet<>());
    }

    private TypeDecl nested(TypeDecl t, String name, Set<TypeDecl> visited) {
        if (!visited.add(t)) {
            return null;
        }
        for (TypeDecl n : t.nested) {
            if (n.simpleName.equals(name)) {
                return n;
            }
        }
        for (TypeDecl s : supertypes(t)) {
            TypeDecl inherited = nested(s, name, visited);
            if (inherited != null) {
                return inherited;
            }
        }
        return null;
    }

    public static String stripGenerics(String typeText) {
        int lt = typeText.indexOf('<');
        String s = lt >= 0 ? typeText.substring(0, lt) : typeText;
        int space = s.lastIndexOf(' ');
        return (space >= 0 ? s.substring(space + 1) : s).trim();
    }
}
