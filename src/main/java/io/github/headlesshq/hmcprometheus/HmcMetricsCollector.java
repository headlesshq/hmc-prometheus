package io.github.headlesshq.hmcprometheus;

import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.Metric;
import me.earth.headlessmc.mc.metrics.MetricsService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Collects the metrics of the HMC-Specifics {@link MetricsService} on every scrape.
 * Most metrics access Minecraft, so they are collected on Minecraft's main thread.
 * If the main thread does not run the collection within the timeout, e.g. because the game is frozen or loading,
 * the previously collected values are served and {@code hmc_collect_success} is 0.
 * The time it took for the main thread to pick up the collection is exported as {@code hmc_main_thread_latency_seconds},
 * which shows how responsive the game is.
 */
final class HmcMetricsCollector implements MultiCollector {
    private static final Logger LOGGER = Logger.getLogger(HmcMetricsCollector.class.getName());

    private final MetricsService service;
    private final Minecraft mc;
    private final Labels constLabels;
    private final List<String> include;
    private final List<String> exclude;
    private final long timeoutMs;

    private volatile Map<String, Object> lastValues = Collections.emptyMap();
    private volatile long lastCollected = -1L;
    private volatile double lastLatency;
    private volatile double lastDuration;
    private Future<Map<String, Object>> pending;

    HmcMetricsCollector(MetricsService service, Minecraft mc, Labels constLabels, List<String> include, List<String> exclude, long timeoutMs) {
        this.service = service;
        this.mc = mc;
        this.constLabels = constLabels;
        this.include = include;
        this.exclude = exclude;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public MetricSnapshots collect() {
        List<Metric<?>> metrics = select();
        boolean success = collectOnMainThread(metrics);
        Map<String, Object> values = lastValues;

        MetricsConverter converter = new MetricsConverter(constLabels);
        for (Metric<?> metric : metrics) {
            converter.add(metric.getName(), metric.getDescription(), values.get(metric.getName()));
        }

        converter.gauge("hmc_collect_success", "Whether the last scrape could collect the metrics on Minecraft's main thread in time.", success ? 1.0 : 0.0);
        long collected = lastCollected;
        if (collected != -1L) {
            converter.gauge("hmc_main_thread_latency_seconds", "Time until Minecraft's main thread started collecting the metrics.", lastLatency);
            converter.gauge("hmc_collect_duration_seconds", "Time it took to collect the metrics on Minecraft's main thread.", lastDuration);
            converter.gauge("hmc_data_age_seconds", "Age of the served metrics, greater than 0 if the main thread did not collect them in time.",
                (System.nanoTime() - collected) / 1e9);
        }

        return MetricSnapshots.of(converter.build().toArray(new MetricSnapshot[0]));
    }

    /**
     * @return {@code true} if the values have been collected within the timeout.
     */
    private synchronized boolean collectOnMainThread(List<Metric<?>> metrics) {
        // a frozen main thread would otherwise pile up a task for every scrape
        if (pending == null || pending.isDone()) {
            long scheduled = System.nanoTime();
            pending = mc.schedule(() -> {
                long start = System.nanoTime();
                Map<String, Object> values = service.collect(metrics);
                lastDuration = (System.nanoTime() - start) / 1e9;
                lastLatency = (start - scheduled) / 1e9;
                return values;
            });
        }

        try {
            Map<String, Object> values = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            lastValues = values == null ? Collections.<String, Object>emptyMap() : values;
            lastCollected = System.nanoTime();
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            LOGGER.log(Level.WARNING, "Failed to collect metrics", e);
            return false;
        }
    }

    private List<Metric<?>> select() {
        Map<String, Metric<?>> result = new LinkedHashMap<>();
        if (include.isEmpty()) {
            for (Metric<?> metric : service.getMetrics()) {
                result.put(metric.getName(), metric);
            }
        } else {
            for (String query : include) {
                for (Metric<?> metric : select(query)) {
                    result.put(metric.getName(), metric);
                }
            }
        }

        for (String query : exclude) {
            for (Metric<?> metric : select(query)) {
                result.remove(metric.getName());
            }
        }

        return new ArrayList<>(result.values());
    }

    private List<Metric<?>> select(String query) {
        try {
            return service.select(query);
        } catch (IllegalArgumentException e) { // the metric might be registered later by another mod
            return Collections.emptyList();
        }
    }

}
