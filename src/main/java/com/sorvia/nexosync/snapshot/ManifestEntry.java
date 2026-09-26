package com.sorvia.nexosync.snapshot;

/**
 * One managed file inside a snapshot.
 *
 * <p>The path is always relative to the Nexo directory and uses forward slashes, so a snapshot
 * produced on Windows installs identically on Linux.</p>
 */
public final class ManifestEntry {

    public String path;
    public long size;
    public String sha256;

    public ManifestEntry() {
        // Required by the JSON codec.
    }

    public ManifestEntry(String path, long size, String sha256) {
        this.path = path;
        this.size = size;
        this.sha256 = sha256;
    }

    public boolean isComplete() {
        return path != null && !path.isBlank() && sha256 != null && sha256.length() == 64 && size >= 0;
    }

    @Override
    public String toString() {
        return path + " (" + size + " bytes)";
    }
}
