package com.sorvia.nexosync.nexo;

import com.sorvia.nexosync.NexoSyncException;
import com.sorvia.nexosync.config.NexoSyncConfig;
import com.sorvia.nexosync.engine.OperationStage;
import com.sorvia.nexosync.log.NexoSyncLogger;
import com.sorvia.nexosync.platform.PlatformScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * Everything NexoSync knows about Nexo.
 *
 * <p>NexoSync does not compile against Nexo. Binding at runtime through reflection keeps the plugin
 * loadable when Nexo is absent or when Nexo changes its internals between releases, and it means a
 * Nexo update never requires a NexoSync rebuild. The console command remains available as a fallback
 * precisely because it is the one interface Nexo is guaranteed to keep.</p>
 *
 * <p>A reload is considered failed when the API call throws, when the command cannot be dispatched,
 * or when Nexo logs a severe error while reloading. That last check is what catches the realistic
 * failure: a snapshot whose item definitions Nexo refuses to parse.</p>
 */
public final class NexoBridge {

    private static final String NEXO_PLUGIN_NAME = "Nexo";
    private static final String NEXO_PLUGIN_CLASS = "com.nexomc.nexo.NexoPlugin";
    private static final String NEXO_ITEMS_CLASS = "com.nexomc.nexo.api.NexoItems";

    /**
     * The class holding Nexo's reload entry points.
     *
     * <p>Deliberately <em>not</em> {@code NexoPlugin}. That class also exposes a no-arg
     * {@code reload()}, but it only reloads configuration, sounds, fonts, multipack and inventories -
     * it does not reload item definitions and does not regenerate the resource pack. Calling it
     * after installing a snapshot succeeds and changes almost nothing, which is the worst possible
     * outcome: a silent partial reload reported as success.</p>
     */
    private static final String NEXO_RELOAD_CLASS = "com.nexomc.nexo.commands.ReloadCommand";

    /**
     * Full reload: configs, items, pack, recipes and dialogs.
     *
     * <p>Only complete entry points belong here. When none is found NexoSync falls back to the
     * console command rather than to a partial API call.</p>
     */
    private static final String[] RELOAD_METHOD_NAMES = {"reloadAll"};

    /** Static accessors that report how many item definitions Nexo currently has loaded. */
    private static final String[] ITEM_COUNT_METHOD_NAMES = {"entries", "itemNames", "getItemNames"};

    private final JavaPlugin plugin;
    private final NexoSyncConfig config;
    private final PlatformScheduler scheduler;
    private final NexoSyncLogger log;

    public NexoBridge(JavaPlugin plugin, NexoSyncConfig config, PlatformScheduler scheduler, NexoSyncLogger log) {
        this.plugin = plugin;
        this.config = config;
        this.scheduler = scheduler;
        this.log = log;
    }

    /**
     * Outcome of a reload attempt.
     *
     * @param method  which mechanism actually ran, for the operation log
     * @param errors  severe messages Nexo emitted while reloading
     */
    public record ReloadOutcome(boolean success, String method, List<String> errors) {

        public String errorSummary() {
            return errors.isEmpty() ? "" : String.join(" | ", errors);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------------------------

    public boolean isPresent() {
        Plugin nexo = Bukkit.getPluginManager().getPlugin(NEXO_PLUGIN_NAME);
        return nexo != null && nexo.isEnabled();
    }

    public Optional<Plugin> nexoPlugin() {
        return Optional.ofNullable(Bukkit.getPluginManager().getPlugin(NEXO_PLUGIN_NAME));
    }

    public String version() {
        return nexoPlugin()
                .map(nexo -> nexo.getPluginMeta().getVersion())
                .orElse("not detected");
    }

    /**
     * True when Nexo exposes an API entry point NexoSync can call directly.
     */
    public boolean hasApiReload() {
        return findReloadMethod().isPresent();
    }

    // -----------------------------------------------------------------------------------------
    // Reloading
    // -----------------------------------------------------------------------------------------

    /**
     * Asks Nexo to reload and waits for the configured timeout.
     *
     * <p>The reload itself runs on a server thread, because it touches the Bukkit API. The calling
     * operation thread blocks on the result, which is safe: NexoSync never calls this from a server
     * thread that would be waiting on itself unless the operator typed the command from the console,
     * in which case the work runs inline.</p>
     */
    public ReloadOutcome reload(String operationId) throws NexoSyncException {
        if (!isPresent()) {
            throw new NexoSyncException(OperationStage.NEXO_RELOAD, "Nexo is not installed or not enabled.");
        }

        NexoSyncConfig.NexoSection.ReloadSection reloadConfig = config.nexo().reload();
        SevereLogCollector collector = attachCollector();

        try {
            String method = null;

            if (reloadConfig.mode().allowsApi()) {
                Optional<Method> apiMethod = findReloadMethod();
                if (apiMethod.isPresent()) {
                    invokeOnServerThread(operationId, apiMethod.get(), reloadConfig.timeoutSeconds());
                    method = "api";
                } else if (!reloadConfig.mode().allowsCommand()) {
                    throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                            "Nexo does not expose a reload API on this version and reload mode is set to 'api'.");
                } else {
                    log.debug(operationId, "No Nexo reload API found, falling back to the configured command.");
                }
            }

            if (method == null) {
                if (!reloadConfig.mode().allowsCommand()) {
                    throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                            "Reload mode is set to 'api' but no Nexo reload API is available.");
                }
                dispatchCommand(operationId, reloadConfig.command(), reloadConfig.timeoutSeconds());
                method = "command";
            }

            List<String> severe = collector.captured();
            boolean failed = reloadConfig.failOnSevereLog() && !severe.isEmpty();

            if (failed) {
                log.warn(operationId, "Nexo reported " + severe.size() + " severe message(s) during reload.");
            } else if (!severe.isEmpty()) {
                log.debug(operationId, "Nexo severe messages during reload: " + String.join(" | ", severe));
            }

            if (!failed && !verifyContentLoaded(operationId)) {
                return new ReloadOutcome(false, method,
                        List.of("Nexo reloaded but reports no loaded item definitions."));
            }

            return new ReloadOutcome(!failed, method, severe);

        } finally {
            collector.detach();
        }
    }

    private void invokeOnServerThread(String operationId, Method method, int timeoutSeconds) throws NexoSyncException {
        log.debug(operationId, "Invoking Nexo reload through " + method.getDeclaringClass().getSimpleName()
                + "#" + method.getName() + "().");

        CompletableFuture<Boolean> future = scheduler.onServerThread(() -> {
            try {
                boolean isStatic = java.lang.reflect.Modifier.isStatic(method.getModifiers());
                // Only resolve the plugin singleton when the target actually needs a receiver.
                Object receiver = isStatic ? null : pluginInstance();
                method.setAccessible(true);
                method.invoke(receiver);
                return Boolean.TRUE;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError reloadFailed) {
                throw new IllegalStateException(describe(reloadFailed), reloadFailed);
            }
        });
        await(future, timeoutSeconds, "Nexo reload");
    }

    private void dispatchCommand(String operationId, String command, int timeoutSeconds) throws NexoSyncException {
        String sanitized = command == null ? "" : command.trim();
        if (sanitized.isEmpty()) {
            throw new NexoSyncException(OperationStage.NEXO_RELOAD, "nexo.reload.command is empty.");
        }
        if (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1);
        }

        String finalCommand = sanitized;
        log.debug(operationId, "Dispatching Nexo reload command: " + finalCommand);

        CompletableFuture<Boolean> future = scheduler.onServerThread(
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand));

        boolean accepted = await(future, timeoutSeconds, "Nexo reload command");
        if (!accepted) {
            throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                    "The server rejected the reload command '" + finalCommand + "'.");
        }
    }

    private boolean await(CompletableFuture<Boolean> future, int timeoutSeconds, String description)
            throws NexoSyncException {
        try {
            Boolean result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return result != null && result;
        } catch (TimeoutException timedOut) {
            throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                    description + " did not finish within " + timeoutSeconds + " seconds.", timedOut);
        } catch (ExecutionException failed) {
            throw new NexoSyncException(OperationStage.NEXO_RELOAD,
                    description + " failed: " + describe(failed.getCause()), failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NexoSyncException(OperationStage.NEXO_RELOAD, description + " was interrupted.", interrupted);
        }
    }

    /**
     * Sanity check after a reload: Nexo should report at least one item definition once the managed
     * directories contain item files.
     */
    private boolean verifyContentLoaded(String operationId) {
        try {
            Class<?> itemsClass = Class.forName(NEXO_ITEMS_CLASS, false, nexoClassLoader());
            Method itemNames = findNoArgMethod(itemsClass, ITEM_COUNT_METHOD_NAMES);

            if (itemNames == null || !java.lang.reflect.Modifier.isStatic(itemNames.getModifiers())) {
                // Nothing usable on this Nexo version; the reload result stands on its own.
                return true;
            }
            itemNames.setAccessible(true);
            Object result = itemNames.invoke(null);

            int count = sizeOf(result);
            log.debug(operationId, "Nexo reports " + count + " loaded item definitions.");
            return count > 0 || !managedPathsContainItems();

        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            log.debug(operationId, "Nexo item verification unavailable: " + unavailable);
            return true;
        }
    }

    private boolean managedPathsContainItems() {
        return config.sync().paths().stream().anyMatch(path -> path.equals("items") || path.startsWith("items/"));
    }

    private static int sizeOf(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof java.util.Collection<?> collection) {
            return collection.size();
        }
        if (value instanceof java.util.Map<?, ?> map) {
            return map.size();
        }
        if (value.getClass().isArray()) {
            return java.lang.reflect.Array.getLength(value);
        }
        return 1;
    }

    // -----------------------------------------------------------------------------------------
    // Reflection plumbing
    // -----------------------------------------------------------------------------------------

    private ClassLoader nexoClassLoader() {
        return nexoPlugin().map(nexo -> nexo.getClass().getClassLoader()).orElse(plugin.getClass().getClassLoader());
    }

    private Object pluginInstance() throws ReflectiveOperationException {
        Class<?> pluginClass = Class.forName(NEXO_PLUGIN_CLASS, false, nexoClassLoader());
        Method instance = findNoArgMethod(pluginClass, "instance", "getInstance");
        if (instance != null && java.lang.reflect.Modifier.isStatic(instance.getModifiers())) {
            instance.setAccessible(true);
            return instance.invoke(null);
        }
        return nexoPlugin().orElse(null);
    }

    /**
     * Resolves Nexo's full-reload entry point, if this Nexo version exposes one.
     */
    private Optional<Method> findReloadMethod() {
        if (!isPresent()) {
            return Optional.empty();
        }
        try {
            Class<?> reloadClass = Class.forName(NEXO_RELOAD_CLASS, false, nexoClassLoader());
            return Optional.ofNullable(findNoArgMethod(reloadClass, RELOAD_METHOD_NAMES));
        } catch (ClassNotFoundException | RuntimeException | LinkageError unavailable) {
            return Optional.empty();
        }
    }

    /**
     * Names the mechanism a reload would use, for the startup log.
     */
    public String describeReloadTarget() {
        return findReloadMethod()
                .map(method -> method.getDeclaringClass().getName() + "#" + method.getName() + "()")
                .orElseGet(() -> "console command '" + config.nexo().reload().command() + "'");
    }

    /**
     * Finds a public no-argument method by name, tolerating a class whose signatures cannot be
     * fully resolved.
     *
     * <p>{@code getMethods()} has to resolve every parameter and return type it reports, and Nexo
     * declares a long list of <em>optional</em> soft dependencies that join its classpath. On a
     * server where one of those is absent, reflecting over the class throws {@link LinkageError} -
     * an {@code Error}, which an ordinary {@code catch (Exception)} would not stop. Treating it as
     * "no usable method here" lets NexoSync fall back to the console command instead of failing the
     * whole update.</p>
     */
    private static Method findNoArgMethod(Class<?> type, String... names) {
        for (String name : names) {
            Method found = searchMethods(type, name, true);
            if (found == null) {
                found = searchMethods(type, name, false);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static Method searchMethods(Class<?> type, String name, boolean publicOnly) {
        try {
            for (Method method : publicOnly ? type.getMethods() : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) {
                    return method;
                }
            }
        } catch (LinkageError | SecurityException unresolvable) {
            return null;
        }
        return null;
    }

    private static String describe(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root == null) {
            return "unknown error";
        }
        return root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // -----------------------------------------------------------------------------------------
    // Log capture
    // -----------------------------------------------------------------------------------------

    private SevereLogCollector attachCollector() {
        SevereLogCollector collector = new SevereLogCollector();
        nexoPlugin().ifPresent(nexo -> {
            try {
                nexo.getLogger().addHandler(collector);
                collector.attachedTo(nexo);
            } catch (SecurityException notPermitted) {
                log.debug(null, "Unable to observe Nexo's logger: " + notPermitted.getMessage());
            }
        });
        return collector;
    }

    /**
     * Collects severe messages Nexo emits while a reload is running.
     */
    private static final class SevereLogCollector extends Handler {

        private static final int MAX_CAPTURED = 10;

        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private Plugin attached;

        void attachedTo(Plugin plugin) {
            this.attached = plugin;
        }

        @Override
        public void publish(LogRecord record) {
            if (record == null || record.getLevel().intValue() < Level.SEVERE.intValue()) {
                return;
            }
            synchronized (messages) {
                if (messages.size() >= MAX_CAPTURED) {
                    return;
                }
                String message = record.getMessage();
                if (message != null && !message.isBlank()) {
                    messages.add(message.strip());
                }
            }
        }

        @Override
        public void flush() {
            // Nothing is buffered.
        }

        @Override
        public void close() {
            // Nothing to release.
        }

        List<String> captured() {
            synchronized (messages) {
                return List.copyOf(messages);
            }
        }

        void detach() {
            if (attached != null) {
                try {
                    attached.getLogger().removeHandler(this);
                } catch (SecurityException ignored) {
                    // The handler stops mattering once the operation ends.
                }
            }
        }
    }
}
