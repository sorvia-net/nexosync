package com.sorvia.nexosync.engine;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guarantees that only one snapshot operation touches the Nexo directory at a time.
 *
 * <p>A scheduled check, a manual {@code /nexosync update} and a rollback can all arrive at once. The
 * lock makes the second one fail fast with a clear message instead of interleaving file deletions
 * with file copies.</p>
 */
public final class OperationLock {

    private final AtomicReference<Holder> holder = new AtomicReference<>(null);

    private record Holder(String description, String operationId, long startedAt) {
    }

    /**
     * Attempts to take the lock.
     *
     * @return a closeable handle when the lock was taken, empty when another operation holds it
     */
    public Optional<Handle> tryAcquire(String description, String operationId) {
        Holder candidate = new Holder(description, operationId, System.currentTimeMillis());
        if (holder.compareAndSet(null, candidate)) {
            return Optional.of(new Handle(candidate));
        }
        return Optional.empty();
    }

    public boolean isBusy() {
        return holder.get() != null;
    }

    public Optional<String> currentOperation() {
        Holder current = holder.get();
        return current == null ? Optional.empty() : Optional.of(current.description());
    }

    public Optional<String> currentOperationId() {
        Holder current = holder.get();
        return current == null ? Optional.empty() : Optional.of(current.operationId());
    }

    /**
     * Releases the lock when closed. Safe to close more than once.
     */
    public final class Handle implements AutoCloseable {

        private final Holder owned;
        private boolean released;

        private Handle(Holder owned) {
            this.owned = owned;
        }

        public long elapsedMillis() {
            return System.currentTimeMillis() - owned.startedAt();
        }

        @Override
        public void close() {
            if (!released) {
                released = true;
                holder.compareAndSet(owned, null);
            }
        }
    }
}
