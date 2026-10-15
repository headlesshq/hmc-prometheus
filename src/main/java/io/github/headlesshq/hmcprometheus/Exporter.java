package io.github.headlesshq.hmcprometheus;

import io.github.headlesshq.hmcprometheus.console.ConsoleHandler;
import io.github.headlesshq.hmcprometheus.logs.LatestLog;
import io.github.headlesshq.hmcprometheus.logs.LogCapture;
import io.github.headlesshq.hmcprometheus.logs.LokiPusher;
import io.github.headlesshq.hmcprometheus.screenshot.ScreenshotCache;
import io.github.headlesshq.hmcprometheus.screenshot.ScreenshotHandler;
import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Info;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.Labels;
import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.MetricsService;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A running Prometheus HTTP server with its own registry, created from a {@link PrometheusConfig}.
 * Every metric gets the {@code client} label, so that metrics of multiple clients can be told apart,
 * independent of how Prometheus discovers the clients.
 */
final class Exporter {
    private static final Logger LOGGER = Logger.getLogger(Exporter.class.getName());
    /**
     * Live views keep a thread busy, so there are enough threads left for scrapes.
     */
    private static final int HTTP_THREADS = 16;
    /**
     * The lines of latest.log from before HMC-Prometheus started are only pushed once per game.
     */
    private static final AtomicBoolean BACKFILLED = new AtomicBoolean();

    private final HTTPServer server;
    private final ExecutorService executor;
    private final String client;
    private final String address;
    private final File discoveryFile;
    private final LokiPusher lokiPusher;
    private final boolean screenshots;

    private Exporter(HTTPServer server, ExecutorService executor, String client, String address, File discoveryFile,
                     LokiPusher lokiPusher, boolean screenshots) {
        this.server = server;
        this.executor = executor;
        this.client = client;
        this.address = address;
        this.discoveryFile = discoveryFile;
        this.lokiPusher = lokiPusher;
        this.screenshots = screenshots;
    }

    /**
     * Starts an exporter. Has to be called on a daemon thread, because the dispatcher thread of the JDK HttpServer
     * inherits the daemon status of the thread that starts it, and a non-daemon dispatcher would keep the JVM alive.
     */
    static Exporter start(PrometheusConfig config, Minecraft mc, String loader, String version) throws IOException {
        String client = getClientName(config, mc);
        Labels constLabels = Labels.of("client", client);
        PrometheusRegistry registry = new PrometheusRegistry();
        registry.register(new HmcMetricsCollector(
            MetricsService.getInstance(), mc, constLabels,
            config.getList(PrometheusConfig.INCLUDE), config.getList(PrometheusConfig.EXCLUDE),
            config.getInt(PrometheusConfig.COLLECT_TIMEOUT)));
        if (config.getBoolean(PrometheusConfig.JVM_METRICS)) {
            JvmMetrics.builder().constLabels(constLabels).register(registry);
        }

        Info info = Info.builder()
            .name("hmc_prometheus")
            .help("Information about HMC-Prometheus.")
            .labelNames("loader", "version")
            .constLabels(constLabels)
            .register(registry);
        info.setLabelValues(loader, version);

        boolean screenshots = config.getBoolean(PrometheusConfig.SCREENSHOTS);
        ScreenshotCache screenshotCache = screenshots ? new ScreenshotCache(mc, config.getInt(PrometheusConfig.SCREENSHOT_INTERVAL)) : null;
        GaugeWithCallback.builder()
            .name("hmc_screenshots_available")
            .help("Whether /screenshot.png and /live serve screenshots, the game has to render and screenshots have to be enabled.")
            .constLabels(constLabels)
            .callback(callback -> callback.call(screenshotCache != null && screenshotCache.isAvailable() ? 1.0 : 0.0))
            .register(registry);

        LokiPusher lokiPusher = startLoki(config, client, registry, constLabels);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(HTTP_THREADS, HTTP_THREADS, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread thread = new Thread(r, "hmc-prometheus-http");
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);

        String host = config.get(PrometheusConfig.HOST);
        ConsoleHandler consoleHandler = new ConsoleHandler(config.getBoolean(PrometheusConfig.CONSOLE), client,
                                                           Arrays.asList(host, config.get(PrometheusConfig.DISCOVERY_ADDRESS)),
                                                           () -> lokiPusher);
        ScreenshotHandler screenshotHandler = new ScreenshotHandler(screenshotCache, client, consoleHandler);
        CounterWithCallback.builder().name("hmc_screenshot_requests").help("Requests of /screenshot.png and /live.")
            .labelNames("endpoint").constLabels(constLabels)
            .callback(c -> {
                c.call(screenshotHandler.getScreenshotRequests(), "screenshot.png");
                c.call(screenshotHandler.getLiveRequests(), "live");
            })
            .register(registry);
        GaugeWithCallback.builder().name("hmc_screenshot_live_streams").help("Open /live streams.")
            .constLabels(constLabels).callback(c -> c.call(screenshotHandler.getLiveStreams())).register(registry);

        HTTPServer server;
        try {
            server = bind(registry, executor, screenshotHandler,
                          host, config.getInt(PrometheusConfig.PORT), config.getInt(PrometheusConfig.PORT_RANGE));
        } catch (IOException e) {
            stopLoki(lokiPusher);
            executor.shutdownNow();
            throw e;
        }

        String address = config.get(PrometheusConfig.DISCOVERY_ADDRESS);
        if (address.isEmpty()) {
            address = "0.0.0.0".equals(host) || "::".equals(host) ? "127.0.0.1" : host;
        }

        File discoveryFile = null;
        String discoveryDir = config.get(PrometheusConfig.DISCOVERY_DIR);
        if (!discoveryDir.isEmpty()) {
            try {
                discoveryFile = new TargetDiscovery(new File(discoveryDir)).write(address, server.getPort(), client);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to write Prometheus discovery file to " + discoveryDir, e);
            }
        }

        return new Exporter(server, executor, client, address, discoveryFile, lokiPusher, screenshots);
    }

    private static LokiPusher startLoki(PrometheusConfig config, String client, PrometheusRegistry registry, Labels constLabels) {
        String url = config.get(PrometheusConfig.LOKI_URL);
        if (url.isEmpty()) {
            return null;
        }

        File latestLog = new File("logs", "latest.log");
        // everything after this point is captured by the appender, everything before is read from the file
        long size = LatestLog.size(latestLog);
        LokiPusher pusher;
        try {
            pusher = new LokiPusher(url, client);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Invalid Loki URL " + url, e);
            return null;
        }

        if (!LogCapture.setPusher(pusher)) {
            pusher.close();
            return null;
        }

        if (config.getBoolean(PrometheusConfig.LOKI_BACKFILL) && !BACKFILLED.getAndSet(true)) {
            Thread thread = new Thread(() -> {
                try {
                    LatestLog.push(latestLog, size, pusher);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to read " + latestLog, e);
                }
            }, "hmc-prometheus-backfill");
            thread.setDaemon(true);
            thread.start();
        }

        CounterWithCallback.builder().name("hmc_loki_sent_lines").help("Log lines pushed to Loki.")
            .constLabels(constLabels).callback(c -> c.call(pusher.getSent())).register(registry);
        CounterWithCallback.builder().name("hmc_loki_dropped_lines").help("Log lines dropped, because Loki was not reachable or rejected them.")
            .constLabels(constLabels).callback(c -> c.call(pusher.getDropped())).register(registry);
        CounterWithCallback.builder().name("hmc_loki_failed_pushes").help("Failed attempts to push to Loki.")
            .constLabels(constLabels).callback(c -> c.call(pusher.getFailedPushes())).register(registry);
        GaugeWithCallback.builder().name("hmc_loki_queued_lines").help("Log lines waiting to be pushed to Loki.")
            .constLabels(constLabels).callback(c -> c.call(pusher.getQueued())).register(registry);
        return pusher;
    }

    private static void stopLoki(LokiPusher pusher) {
        if (pusher != null) {
            LogCapture.setPusher(null);
            pusher.close();
        }
    }

    void stop() {
        if (discoveryFile != null) {
            try {
                Files.deleteIfExists(discoveryFile.toPath());
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to delete " + discoveryFile, e);
            }
        }

        server.close();
        executor.shutdownNow();
        stopLoki(lokiPusher);
    }

    LokiPusher getLokiPusher() {
        return lokiPusher;
    }

    String getBaseUrl() {
        return "http://" + address + ":" + server.getPort();
    }

    boolean isScreenshots() {
        return screenshots;
    }

    String getClient() {
        return client;
    }

    int getPort() {
        return server.getPort();
    }

    String getUrl() {
        return "http://" + address + ":" + server.getPort() + "/metrics";
    }

    File getDiscoveryFile() {
        return discoveryFile;
    }

    private static HTTPServer bind(PrometheusRegistry registry, ExecutorService executor, ScreenshotHandler handler,
                                   String host, int port, int range) throws IOException {
        IOException exception = null;
        for (int i = 0; i <= Math.max(0, range); i++) {
            try {
                return HTTPServer.builder()
                    .registry(registry)
                    .executorService(executor)
                    .defaultHandler(handler)
                    .hostname(host)
                    .port(port + i)
                    .buildAndStart();
            } catch (IOException e) { // most likely a BindException, because another client uses the port
                exception = e;
            }
        }

        throw new IOException("Failed to bind to any port from " + port + " to " + (port + range) + " on " + host, exception);
    }

    private static String getClientName(PrometheusConfig config, Minecraft mc) {
        String client = config.get(PrometheusConfig.CLIENT);
        if (client.isEmpty()) {
            try {
                if (mc.getMcAccount() != null && mc.getMcAccount().getName() != null) {
                    client = mc.getMcAccount().getName();
                }
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Failed to get the account name", t);
            }
        }

        return client.isEmpty() ? "minecraft" : client;
    }

}
