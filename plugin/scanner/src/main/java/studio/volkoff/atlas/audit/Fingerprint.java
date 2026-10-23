package studio.volkoff.atlas.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * What a place verdict depends on. {@code core} covers the methods around the rule's evidence,
 * the effects the rule cited and the mitigation signals on the path; it is known before the
 * checker runs. {@code opened} covers the files outside the packet that the checker read.
 * A verdict is stale when either no longer matches the tree.
 */
final class Fingerprint {

    private Fingerprint() {
    }

    static String core(AuditContext ctx, Packet.PlaceView v) {
        StringBuilder s = new StringBuilder();
        for (Slices.Slice slice : v.evidenceSlices()) {
            s.append("S|").append(slice.file()).append('|').append(Slices.normalised(slice)).append('\n');
        }
        for (String f : v.missing()) {
            s.append("MISSING|").append(f).append('\n');
        }
        for (Map<String, Object> e : v.place().reasonEffects()) {
            s.append("E|").append(e.get("signal")).append('|').append(e.get("detail")).append('|').append(e.get("symbol")).append('\n');
        }
        TreeSet<String> mitigations = new TreeSet<>();
        for (String op : v.place().ops()) {
            mitigations.addAll(ctx.inputById.get(op).assessment().mitigations());
        }
        s.append("M|").append(String.join(",", mitigations));
        return sha256(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Hash of the files, sorted, each as path and content hash; a missing file hashes as missing. */
    static String opened(Path repo, Collection<String> files) {
        List<String> sorted = new ArrayList<>(new TreeSet<>(files));
        StringBuilder s = new StringBuilder();
        for (String f : sorted) {
            Path p = repo.resolve(f).normalize();
            String content;
            try {
                content = p.startsWith(repo) && Files.isRegularFile(p) ? sha256(Files.readAllBytes(p)) : "missing";
            } catch (IOException e) {
                content = "unreadable";
            }
            s.append(f).append('|').append(content).append('\n');
        }
        return sha256(s.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
