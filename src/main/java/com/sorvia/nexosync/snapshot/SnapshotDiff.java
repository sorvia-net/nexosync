package com.sorvia.nexosync.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Difference between two snapshots, used for reporting rather than for installing.
 *
 * <p>NexoSync never merges snapshots - a managed directory is replaced wholesale - but an operator
 * still wants to know what actually changed, so the same information is computed for the console
 * and for Discord.</p>
 */
public record SnapshotDiff(List<String> added, List<String> modified, List<String> removed) {

    public static SnapshotDiff between(SnapshotManifest previous, SnapshotManifest current) {
        List<String> added = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        Map<String, ManifestEntry> before = previous == null ? Map.of() : previous.byPath();
        Map<String, ManifestEntry> after = current == null ? Map.of() : current.byPath();

        for (Map.Entry<String, ManifestEntry> entry : after.entrySet()) {
            ManifestEntry old = before.get(entry.getKey());
            if (old == null) {
                added.add(entry.getKey());
            } else if (!old.sha256.equals(entry.getValue().sha256)) {
                modified.add(entry.getKey());
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                removed.add(path);
            }
        }

        added.sort(String::compareTo);
        modified.sort(String::compareTo);
        removed.sort(String::compareTo);
        return new SnapshotDiff(added, modified, removed);
    }

    public boolean isEmpty() {
        return added.isEmpty() && modified.isEmpty() && removed.isEmpty();
    }

    public int totalChanges() {
        return added.size() + modified.size() + removed.size();
    }

    public String summary() {
        return added.size() + " added, " + modified.size() + " modified, " + removed.size() + " removed";
    }

    /**
     * A short, bounded listing suitable for a chat or webhook message.
     */
    public List<String> preview(int limit) {
        List<String> lines = new ArrayList<>();
        appendAll(lines, "+", added, limit);
        appendAll(lines, "~", modified, limit);
        appendAll(lines, "-", removed, limit);
        return lines;
    }

    private static void appendAll(List<String> target, String marker, List<String> source, int limit) {
        int shown = 0;
        for (String path : source) {
            if (target.size() >= limit) {
                return;
            }
            target.add(marker + " " + path);
            shown++;
        }
        if (shown < source.size()) {
            target.add("  ... and " + (source.size() - shown) + " more");
        }
    }
}
