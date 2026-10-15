package io.github.headlesshq.hmcprometheus;

import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.InfoSnapshot;
import io.prometheus.metrics.model.snapshots.Labels;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.PrometheusNaming;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts the values of HMC-Specifics metrics into Prometheus metrics.
 * Every metric gets the prefix {@code hmc_}, dots become underscores, e.g. {@code server.tps} becomes {@code hmc_server_tps}.
 * <ul>
 *     <li>Numbers and Booleans (as 0 and 1) become gauges.</li>
 *     <li>Strings become info metrics with the string as the {@code value} label,
 *     e.g. {@code hmc_server_brand_info{value="Paper"} 1}.</li>
 *     <li>Maps are flattened, e.g. {@code client.mspt = {avg, max}} becomes {@code hmc_client_mspt_avg} and {@code hmc_client_mspt_max}.</li>
 *     <li>Lists of maps become one series per element, the strings of an element become its labels,
 *     e.g. {@code players.list} becomes {@code hmc_players_list_ping{name="...", uuid="...", gamemode="..."}}.</li>
 * </ul>
 * Values that are {@code null}, e.g. because the client is not in a world, are left out.
 */
final class MetricsConverter {
    private static final Logger LOGGER = Logger.getLogger(MetricsConverter.class.getName());
    private static final String PREFIX = "hmc_";

    private final Map<String, GaugeSnapshot.Builder> gauges = new LinkedHashMap<>();
    private final Map<String, InfoSnapshot.Builder> infos = new LinkedHashMap<>();
    private final Map<String, Set<Labels>> dataPoints = new LinkedHashMap<>();
    private final Set<String> warned = new HashSet<>();
    private final Labels constLabels;

    MetricsConverter(Labels constLabels) {
        this.constLabels = constLabels;
    }

    /**
     * Adds a metric of HMC-Specifics.
     *
     * @param name the name of the metric, e.g. {@code server.tps}.
     * @param help the description of the metric.
     * @param value the value of the metric.
     */
    void add(String name, String help, Object value) {
        add(PREFIX + sanitize(name), help, value, Labels.EMPTY);
    }

    void gauge(String name, String help, double value) {
        addGauge(name, help, value, Labels.EMPTY);
    }

    void info(String name, String help, Labels labels) {
        addInfo(name, help, labels);
    }

    List<MetricSnapshot> build() {
        List<MetricSnapshot> result = new ArrayList<>();
        for (GaugeSnapshot.Builder builder : gauges.values()) {
            result.add(builder.build());
        }

        for (InfoSnapshot.Builder builder : infos.values()) {
            result.add(builder.build());
        }

        return result;
    }

    private void add(String name, String help, Object value, Labels labels) {
        if (value == null) {
            return;
        }

        if (value instanceof Number) {
            addGauge(name, help, ((Number) value).doubleValue(), labels);
        } else if (value instanceof Boolean) {
            addGauge(name, help, (Boolean) value ? 1.0 : 0.0, labels);
        } else if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                add(name + "_" + sanitize(String.valueOf(entry.getKey())), help, entry.getValue(), labels);
            }
        } else if (value instanceof List) {
            for (Object element : (List<?>) value) {
                addListElement(name, help, element, labels);
            }
        } else {
            addInfo(name, help, labels.add("value", value.toString()));
        }
    }

    /**
     * The scalar values of a Map in a List identify the element, so they become labels.
     * If the element does not contain any numbers it becomes an info metric.
     */
    private void addListElement(String name, String help, Object element, Labels labels) {
        if (element instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) element;
            Labels elementLabels = labels;
            Map<String, Object> values = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = sanitizeLabel(String.valueOf(entry.getKey()));
                Object value = entry.getValue();
                if (value instanceof Number || value instanceof Boolean || value instanceof Map || value instanceof List) {
                    values.put(key, value);
                } else {
                    elementLabels = elementLabels.add(key, value == null ? "" : value.toString());
                }
            }

            if (values.isEmpty()) {
                addInfo(name, help, elementLabels);
            } else {
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    add(name + "_" + entry.getKey(), help, entry.getValue(), elementLabels);
                }
            }
        } else if (element != null && !(element instanceof Number) && !(element instanceof Boolean)) {
            addInfo(name, help, labels.add("value", element.toString()));
        }
    }

    private void addGauge(String name, String help, double value, Labels labels) {
        if (infos.containsKey(name) || !isNewDataPoint(name, labels)) {
            return;
        }

        try {
            GaugeSnapshot.Builder builder = gauges.get(name);
            if (builder == null) {
                builder = GaugeSnapshot.builder().name(name).help(help);
                gauges.put(name, builder);
            }

            builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder().value(value).labels(constLabels.merge(labels)).build());
        } catch (IllegalArgumentException e) {
            warn(name, e);
        }
    }

    private void addInfo(String name, String help, Labels labels) {
        if (gauges.containsKey(name) || !isNewDataPoint(name, labels)) {
            return;
        }

        try {
            InfoSnapshot.Builder builder = infos.get(name);
            if (builder == null) {
                builder = InfoSnapshot.builder().name(name).help(help);
                infos.put(name, builder);
            }

            builder.dataPoint(InfoSnapshot.InfoDataPointSnapshot.builder().labels(constLabels.merge(labels)).build());
        } catch (IllegalArgumentException e) {
            warn(name, e);
        }
    }

    /**
     * Prometheus does not allow two data points with the same labels, e.g. two players with the same name.
     */
    private boolean isNewDataPoint(String name, Labels labels) {
        Set<Labels> set = dataPoints.get(name);
        if (set == null) {
            set = new HashSet<>();
            dataPoints.put(name, set);
        }

        return set.add(labels);
    }

    private void warn(String name, Exception e) {
        if (warned.add(name)) {
            LOGGER.log(Level.WARNING, "Failed to export metric " + name, e);
        }
    }

    static String sanitize(String name) {
        StringBuilder result = new StringBuilder();
        for (char c : name.toCharArray()) {
            result.append(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' ? c : '_');
        }

        return result.toString();
    }

    /**
     * The client label is added to every metric, so a label with the same name gets renamed, like Prometheus would.
     */
    private String sanitizeLabel(String name) {
        String label = PrometheusNaming.sanitizeLabelName(sanitize(name));
        return constLabels.contains(label) ? "exported_" + label : label;
    }

}
