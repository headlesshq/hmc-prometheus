package io.github.headlesshq.hmcprometheus;

import io.github.headlesshq.hmcprometheus.logs.LokiPusher;
import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.MetricsService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Prometheus exporter for the Minecraft client, built on the metrics of HMC-Specifics.
 * HMC-Specifics calls {@link HmcPrometheusProvider} with the {@link Minecraft} instance when it initializes,
 * which starts the exporter. The modloader entrypoints only record which modloader loaded the mod,
 * see {@link #onModConstructed(String)}.
 */
public final class HmcPrometheus {
    public static final String ID = "hmc_prometheus";
    public static final String NAME = "HMC-Prometheus";

    private static final Logger LOGGER = Logger.getLogger(HmcPrometheus.class.getName());
    private static final HmcPrometheus INSTANCE = new HmcPrometheus();

    private volatile String loader = "unknown";
    private volatile Minecraft mc;
    private volatile Exporter exporter;
    private volatile PrometheusConfig config;
    private boolean shutdownHookAdded;

    public static HmcPrometheus getInstance() {
        return INSTANCE;
    }

    /**
     * Called by the modloader entrypoints.
     * Forge before 1.13 adds mods to the classpath after HMC-Specifics has been initialized,
     * so HMC-Specifics could not find our {@link HmcPrometheusProvider}.
     * In that case we look for the Minecraft instance ourselves and let HMC-Specifics load our provider.
     *
     * @param loader the name of the modloader.
     */
    public static void onModConstructed(String loader) {
        INSTANCE.loader = loader;
        if (INSTANCE.mc == null) {
            Minecraft mc = findMinecraft();
            if (mc != null) {
                MetricsService.getInstance().loadProviders(mc, HmcPrometheus.class.getClassLoader());
            }
        }
    }

    /**
     * Called once HMC-Specifics has initialized, starts the exporter if it is enabled.
     *
     * @param mc the Minecraft instance.
     */
    synchronized void init(Minecraft mc) {
        if (this.mc != null) {
            return;
        }

        this.mc = mc;
        try {
            config = PrometheusConfig.load();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Failed to load the HMC-Prometheus config", e);
            return;
        }

        if (config.getBoolean(PrometheusConfig.ENABLED)) {
            try {
                start();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to start " + NAME, e);
            }
        }
    }

    /**
     * Starts the exporter, stopping the running one first.
     *
     * @throws Exception if the exporter could not be started.
     */
    public synchronized void start() throws Exception {
        if (mc == null) {
            throw new IllegalStateException("HMC-Specifics has not been initialized yet");
        }

        stop();
        PrometheusConfig config = this.config;
        exporter = runOnDaemonThread(() -> Exporter.start(config, mc, loader, getVersion()));
        if (!shutdownHookAdded) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "hmc-prometheus-shutdown"));
            shutdownHookAdded = true;
        }

        LOGGER.info(NAME + " serving metrics of client '" + exporter.getClient() + "' on " + exporter.getUrl());
    }

    public synchronized void stop() {
        Exporter exporter = this.exporter;
        if (exporter != null) {
            this.exporter = null;
            exporter.stop();
        }
    }

    /**
     * Reads the config file again and restarts the exporter, if it is enabled.
     *
     * @throws Exception if the config could not be read or the exporter could not be started.
     */
    public synchronized void reload() throws Exception {
        config = PrometheusConfig.load();
        stop();
        if (config.getBoolean(PrometheusConfig.ENABLED)) {
            start();
        }
    }

    /**
     * @return the config, or {@code null} if HMC-Specifics has not been initialized yet.
     */
    public PrometheusConfig getConfig() {
        return config;
    }

    public String getLoader() {
        return loader;
    }

    public boolean isRunning() {
        return exporter != null;
    }

    /**
     * @return the value of the client label, or {@code null} if the exporter is not running.
     */
    public String getClient() {
        Exporter exporter = this.exporter;
        return exporter == null ? null : exporter.getClient();
    }

    /**
     * @return the URL Prometheus scrapes, or {@code null} if the exporter is not running.
     */
    public String getUrl() {
        Exporter exporter = this.exporter;
        return exporter == null ? null : exporter.getUrl();
    }

    /**
     * @return the base URL of the HTTP server, e.g. http://127.0.0.1:9400, or {@code null} if the exporter is not running.
     */
    public String getBaseUrl() {
        Exporter exporter = this.exporter;
        return exporter == null ? null : exporter.getBaseUrl();
    }

    /**
     * @return whether the exporter serves screenshots, they might still be unavailable because the game does not render.
     */
    public boolean isScreenshots() {
        Exporter exporter = this.exporter;
        return exporter != null && exporter.isScreenshots();
    }

    /**
     * @return the pusher that sends the log to Loki, or {@code null} if no Loki URL is configured or the exporter is not running.
     */
    public LokiPusher getLokiPusher() {
        Exporter exporter = this.exporter;
        return exporter == null ? null : exporter.getLokiPusher();
    }

    /**
     * @return the file for Prometheus service discovery, or {@code null} if none has been written.
     */
    public File getDiscoveryFile() {
        Exporter exporter = this.exporter;
        return exporter == null ? null : exporter.getDiscoveryFile();
    }

    /**
     * Read from a resource, because not every modloader exposes the manifest of the jar through the Package.
     */
    public static String getVersion() {
        try (InputStream is = HmcPrometheus.class.getResourceAsStream("/hmc_prometheus.version")) {
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String version = reader.readLine();
                if (version != null && !version.trim().isEmpty()) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // fall back to the manifest
        }

        String version = HmcPrometheus.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    private static <T> T runOnDaemonThread(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable);
        Thread thread = new Thread(task, "hmc-prometheus-start");
        thread.setDaemon(true);
        thread.start();
        try {
            return task.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
    }

    /**
     * HMC-Specifics makes net.minecraft.client.Minecraft implement its {@link Minecraft} interface.
     * The static field holding the instance has a different name in every version and mapping, so we check the type.
     */
    private static Minecraft findMinecraft() {
        try {
            Class<?> type = Class.forName("net.minecraft.client.Minecraft", false, HmcPrometheus.class.getClassLoader());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == type) {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof Minecraft) {
                        return (Minecraft) value;
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "Failed to find the Minecraft instance", t);
        }

        return null;
    }

}
