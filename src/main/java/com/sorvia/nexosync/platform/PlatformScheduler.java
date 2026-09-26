package com.sorvia.nexosync.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Platform-safe scheduling.
 *
 * <p>NexoSync is almost entirely I/O: HTTP requests, hashing, archive handling and file copying.
 * None of that belongs on a server thread, so it runs on this class's own executors, which behave
 * identically on Paper and on Folia. Only the few calls that genuinely touch the Bukkit API - the
 * Nexo reload in particular - are pushed back onto a server thread through
 * {@link #onServerThread(Supplier)}.</p>
 *
 * <p>On Folia there is no single "main thread", so the global region scheduler is used. Paper
 * exposes the same API, but the classic scheduler is used there to keep behaviour predictable on
 * older builds.</p>
 */
public final class PlatformScheduler {

    private final Plugin plugin;
    private final boolean folia;
    private final ScheduledExecutorService workers;

    public PlatformScheduler(Plugin plugin) {
        this.plugin = plugin;
        this.folia = detectFolia();
        this.workers = Executors.newScheduledThreadPool(2, namedThreadFactory());
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException notFolia) {
            return false;
        }
    }

    private ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "NexoSync-Worker-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    public boolean isFolia() {
        return folia;
    }

    public String platformName() {
        return folia ? "Folia" : Bukkit.getName();
    }

    /**
     * Runs work off any server thread.
     */
    public CompletableFuture<Void> async(Runnable task) {
        return CompletableFuture.runAsync(task, workers);
    }

    public <T> CompletableFuture<T> asyncSupply(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, workers);
    }

    public ScheduledFuture<?> repeating(Runnable task, long initialDelaySeconds, long periodSeconds) {
        return workers.scheduleWithFixedDelay(
                guarded(task), initialDelaySeconds, periodSeconds, TimeUnit.SECONDS);
    }

    public ScheduledFuture<?> delayed(Runnable task, long delaySeconds) {
        return workers.schedule(guarded(task), delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Executes a supplier on a server thread and completes the returned future with its result.
     *
     * <p>When the caller already is a server thread the supplier runs inline, which keeps the Nexo
     * reload path simple when an operator triggers it from the console.</p>
     */
    public <T> CompletableFuture<T> onServerThread(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();

        if (Bukkit.isPrimaryThread()) {
            completeWith(future, task);
            return future;
        }

        Runnable runnable = () -> completeWith(future, task);
        try {
            if (folia) {
                Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
            } else {
                Bukkit.getScheduler().runTask(plugin, runnable);
            }
        } catch (RuntimeException schedulingRejected) {
            // Bukkit refuses to schedule while the plugin is disabling; surface that to the caller
            // rather than leaving it blocked on a future nothing will ever complete.
            future.completeExceptionally(schedulingRejected);
        }
        return future;
    }

    private static <T> void completeWith(CompletableFuture<T> future, Supplier<T> task) {
        try {
            future.complete(task.get());
        } catch (Throwable failure) {
            future.completeExceptionally(failure);
        }
    }

    /**
     * Wraps a repeating task so that one failure never silently cancels the schedule.
     */
    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable failure) {
                plugin.getLogger().warning("Scheduled task failed: " + failure);
            }
        };
    }

    public void shutdown() {
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
