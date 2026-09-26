package com.sorvia.nexosync.engine;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.snapshot.ManifestEntry;
import com.sorvia.nexosync.snapshot.SnapshotManifest;
import com.sorvia.nexosync.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Replaces the managed directories with a staged snapshot.
 *
 * <p>This is the only class that deletes live Nexo content, and it is only ever reached after the
 * snapshot has been downloaded, verified and backed up. The replacement is deliberately total: a
 * managed directory is emptied before the new files are copied in, which is what removes files that
 * the publisher deleted.</p>
 */
public final class Installer {

    private final EngineContext context;

    public Installer(EngineContext context) {
        this.context = context;
    }

    /**
     * Empties every configured managed directory, leaving the directory itself in place.
     */
    public void removeManagedDirectories(String operationId) throws NexoSyncException {
        for (String managed : context.config().sync().paths()) {
            Path live = context.nexoDirectory().resolve(managed).normalize();

            if (!FileUtils.isWithin(context.nexoDirectory(), live)) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Refusing to delete outside the Nexo directory: " + managed);
            }
            try {
                FileUtils.deleteRecursively(live);
                FileUtils.ensureDirectory(live);
                context.log().debug(operationId, "Cleared managed directory " + managed + ".");
            } catch (IOException deleteFailed) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Unable to clear managed directory " + managed + ": " + deleteFailed.getMessage(), deleteFailed);
            }
        }
    }

    /**
     * Copies every file the manifest lists from the staging area into the Nexo directory.
     *
     * <p>Copying from the manifest rather than walking the staging tree means that anything the
     * archive contained but the manifest did not describe is silently left behind instead of being
     * installed.</p>
     */
    public void install(String operationId, Path stagingRoot, SnapshotManifest manifest) throws NexoSyncException {
        int copied = 0;

        for (ManifestEntry entry : manifest.files) {
            Path source = stagingRoot.resolve(entry.path).normalize();
            Path destination = context.nexoDirectory().resolve(entry.path).normalize();

            if (!FileUtils.isWithin(stagingRoot, source)) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Staged file resolves outside the staging directory: " + entry.path);
            }
            if (!FileUtils.isWithin(context.nexoDirectory(), destination)) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Snapshot file resolves outside the Nexo directory: " + entry.path);
            }
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Staged file is missing: " + entry.path);
            }

            try {
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                copied++;
            } catch (IOException copyFailed) {
                throw new NexoSyncException(OperationStage.INSTALLATION,
                        "Unable to install " + entry.path + ": " + copyFailed.getMessage(), copyFailed);
            }
        }

        context.log().progress(operationId, "Installed " + copied + " file(s) into " + context.nexoDirectory() + ".");
    }

    /**
     * Confirms the live tree now matches the manifest exactly.
     */
    public void verify(SnapshotManifest manifest) throws NexoSyncException {
        context.snapshots().verifyTree(
                context.nexoDirectory(), manifest, context.config().safety().strictManagedPaths(),
                OperationStage.INSTALLATION);
    }
}
