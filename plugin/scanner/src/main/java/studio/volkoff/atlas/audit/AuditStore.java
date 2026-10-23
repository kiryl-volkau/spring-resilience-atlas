package studio.volkoff.atlas.audit;

import studio.volkoff.atlas.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code .reliability/audit/}: the plan, the recorded rows per batch and the derived files. The
 * scanner is the only writer, and every file is written whole to a temporary file and moved into
 * place, so an interrupted run never leaves a half-written file.
 */
final class AuditStore {

    static final List<String> TIERS = List.of("TRIAGE", "DEEP", "VERIFIED");

    /**
     * The verdict that counts for one place.
     *
     * @param status    CURRENT, STALE or UNJUDGED
     * @param row       the winning row: a verdict row, or the newest UNJUDGED row
     * @param unjudged  how many UNJUDGED rows the place has
     * @param hasVerdict whether any row of the place carries a verdict, current or stale
     */
    record Resolved(String placeId, String status, Map<String, Object> row, int unjudged, boolean hasVerdict) {
        String verdict() {
            return status.equals("UNJUDGED") ? null : String.valueOf(row.get("verdict"));
        }
    }

    final Path dir;

    AuditStore(Path dir) {
        this.dir = dir;
    }

    Path planFile() {
        return dir.resolve("plan.json");
    }

    Map<String, Object> plan() throws IOException {
        Path f = planFile();
        return Files.exists(f) ? Json.parseObject(read(f)) : null;
    }

    void writePlan(Map<String, Object> plan) throws IOException {
        write(planFile(), Json.write(plan) + "\n");
    }

    Path packet(String batch) {
        return dir.resolve("packets").resolve(batch + ".txt");
    }

    Path draft(String batch) {
        return dir.resolve("drafts").resolve(batch + ".jsonl");
    }

    Path batchRows(String batch) {
        return dir.resolve("places").resolve(batch + ".jsonl");
    }

    boolean hasRows() {
        return Files.isDirectory(dir.resolve("places"));
    }

    /** Every recorded row, from every batch file, in file-name order. */
    List<Map<String, Object>> rows() throws IOException {
        Path places = dir.resolve("places");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(places)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(places)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".jsonl")).sorted().toList();
        }
        for (Path f : files) {
            for (String line : read(f).split("\\R")) {
                if (!line.isBlank()) {
                    out.add(Json.parseObject(line));
                }
            }
        }
        return out;
    }

    void writeRows(String batch, List<Map<String, Object>> rows) throws IOException {
        StringBuilder s = new StringBuilder();
        for (Map<String, Object> r : rows) {
            s.append(Json.write(r)).append('\n');
        }
        write(batchRows(batch), s.toString());
    }

    void writeJsonl(String name, List<Map<String, Object>> rows) throws IOException {
        StringBuilder s = new StringBuilder();
        for (Map<String, Object> r : rows) {
            s.append(Json.write(r)).append('\n');
        }
        write(dir.resolve(name), s.toString());
    }

    /**
     * Per place: a verdict row whose {@code core} and {@code opened} still match is CURRENT, the
     * highest tier first, then the newest. Without one, the best verdict row is STALE. Without
     * any verdict row, the newest UNJUDGED row stands. {@code currentCore} has the core of every
     * place that still derives from the atlas; rows of other places are left out.
     */
    static Map<String, Resolved> resolve(List<Map<String, Object>> rows, Map<String, String> currentCore, Path repo) {
        Map<String, List<Map<String, Object>>> byPlace = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String id = String.valueOf(r.get("placeId"));
            if (currentCore.containsKey(id)) {
                byPlace.computeIfAbsent(id, k -> new ArrayList<>()).add(r);
            }
        }
        Comparator<Map<String, Object>> best = Comparator
                .comparingInt((Map<String, Object> r) -> TIERS.indexOf(String.valueOf(r.get("tier"))))
                .thenComparing(r -> String.valueOf(r.get("recordedAt")));
        Map<String, Resolved> out = new LinkedHashMap<>();
        byPlace.forEach((id, list) -> {
            List<Map<String, Object>> fresh = new ArrayList<>();
            List<Map<String, Object>> stale = new ArrayList<>();
            List<Map<String, Object>> unjudged = new ArrayList<>();
            for (Map<String, Object> r : list) {
                if ("UNJUDGED".equals(r.get("status"))) {
                    unjudged.add(r);
                } else if (currentCore.get(id).equals(r.get("core")) && openedMatches(r, repo)) {
                    fresh.add(r);
                } else {
                    stale.add(r);
                }
            }
            boolean hasVerdict = !fresh.isEmpty() || !stale.isEmpty();
            if (!fresh.isEmpty()) {
                out.put(id, new Resolved(id, "CURRENT", fresh.stream().max(best).orElseThrow(), unjudged.size(), true));
            } else if (!stale.isEmpty()) {
                out.put(id, new Resolved(id, "STALE", stale.stream().max(best).orElseThrow(), unjudged.size(), true));
            } else {
                Map<String, Object> newest = unjudged.stream().max(Comparator.comparing(r -> String.valueOf(r.get("recordedAt")))).orElseThrow();
                out.put(id, new Resolved(id, "UNJUDGED", newest, unjudged.size(), false));
            }
        });
        return out;
    }

    private static boolean openedMatches(Map<String, Object> row, Path repo) {
        List<String> files = new ArrayList<>();
        if (row.get("openedFiles") instanceof List<?> l) {
            l.forEach(f -> files.add(String.valueOf(f)));
        }
        if (files.isEmpty()) {
            return true;
        }
        return Fingerprint.opened(repo, files).equals(row.get("opened"));
    }

    static String read(Path f) throws IOException {
        String s = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    static void write(Path f, String content) throws IOException {
        Files.createDirectories(f.toAbsolutePath().getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
