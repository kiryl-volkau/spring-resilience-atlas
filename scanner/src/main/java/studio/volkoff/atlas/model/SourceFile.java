package studio.volkoff.atlas.model;

import java.util.List;

/**
 * A parsed source file.
 *
 * @param imports               single-type imports, fully qualified
 * @param wildcardImports       packages or types imported with {@code .*}
 * @param staticImports         {@code type.member} of single static imports
 * @param staticWildcardImports types imported with {@code import static type.*}
 * @param types                 every type declared in the file, nested ones included
 */
public record SourceFile(
        String relPath,
        String module,
        String pkg,
        Origin origin,
        List<String> imports,
        List<String> wildcardImports,
        List<String> staticImports,
        List<String> staticWildcardImports,
        List<TypeDecl> types) {

    public boolean importsFrom(String packagePrefix) {
        for (String i : imports) {
            if (i.startsWith(packagePrefix)) {
                return true;
            }
        }
        for (String i : wildcardImports) {
            if (i.startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }
}
