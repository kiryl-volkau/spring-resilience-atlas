package studio.volkoff.atlas.store;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read-only git queries. Every method returns {@code null} when git or the repository is unavailable. */
public final class Git {

    private final Path repo;

    public Git(Path repo) {
        this.repo = repo;
    }

    public String head() {
        return run("rev-parse", "HEAD");
    }

    /** Whether scanned sources have uncommitted changes; other files do not make the atlas dirty. */
    public Boolean dirty() {
        String head = head();
        Integer changed = head == null ? null : changedSourceFiles(head);
        return changed == null ? null : changed > 0;
    }

    /**
     * Number of Java/Kotlin/Groovy main-source files that differ between {@code commit} and the working
     * tree, untracked new files included, ignoring hidden directories and test trees, which the
     * scanner never reads.
     */
    public Integer changedSourceFiles(String commit) {
        String changed = run("diff", "--name-only", "--relative", commit, "--", "*.java", "*.kt", "*.groovy");
        String untracked = run("ls-files", "--others", "--exclude-standard", "--", "*.java", "*.kt", "*.groovy");
        if (changed == null || untracked == null) {
            return null;
        }
        return (int) (changed + "\n" + untracked).lines().filter(l -> !l.isBlank() && scannable(l)).count();
    }

    /** The scanner's source-set rules, path by path: hidden directories, test-like sets and resources are not read. */
    private static boolean scannable(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length; i++) {
            if (segments[i].startsWith(".")) {
                return false;
            }
            if (i > 0 && segments[i - 1].equals("src")) {
                String set = segments[i].toLowerCase(java.util.Locale.ROOT);
                if (set.contains("test") || set.equals("it") || set.equals("jmh") || set.startsWith("integration")
                        || set.startsWith("e2e")) {
                    return false;
                }
            }
            if (i > 1 && segments[i - 2].equals("src") && segments[i].equals("resources")) {
                return false;
            }
        }
        return true;
    }

    private String run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.longpaths=true");
        cmd.add("-C");
        cmd.add(repo.toString());
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                in.transferTo(buf);
            }
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
                p.destroyForcibly();
                return null;
            }
            return buf.toString(StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
