package io.github.headlesshq.hmcprometheus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The configuration of HMC-Prometheus.
 * It is read from {@code config/hmc-prometheus.properties} in the game directory, or the file given by {@value #FILE_PROPERTY}.
 * Every option can be overridden with a system property, e.g. {@code -Dhmc.prometheus.port=9401}.
 * The file is written with all options and their descriptions if it does not exist.
 */
public final class PrometheusConfig {
    public static final String FILE_PROPERTY = "hmc.prometheus.config";
    public static final String SYSTEM_PROPERTY_PREFIX = "hmc.prometheus.";

    private static final Map<String, Option> OPTIONS = new LinkedHashMap<>();

    public static final Option ENABLED = option("enabled", "true",
        "Whether the exporter is started with the game. Use the 'prometheus start' command to start it later.");
    public static final Option CLIENT = option("client", "",
        "The value of the 'client' label on every metric, identifies this client in Grafana. Defaults to the name of the account.");
    public static final Option HOST = option("host", "127.0.0.1",
        "The address the HTTP server binds to. Use 0.0.0.0 if Prometheus runs on another machine or in a Docker bridge network.");
    public static final Option PORT = option("port", "9400",
        "The first port to try for the HTTP server.");
    public static final Option PORT_RANGE = option("portRange", "100",
        "How many ports after 'port' to try, so that multiple clients can run on the same machine.");
    public static final Option INCLUDE = option("include", "",
        "Comma separated HMC-Specifics metrics to export, by name (server.tps), group (server) or last part (tps). Empty for all.");
    public static final Option EXCLUDE = option("exclude", "jvm",
        "Comma separated HMC-Specifics metrics not to export. The jvm group is covered by the JVM metrics of Prometheus.");
    public static final Option JVM_METRICS = option("jvmMetrics", "true",
        "Whether to export the JVM metrics (memory, GC, threads, CPU) of the Prometheus Java client.");
    public static final Option COLLECT_TIMEOUT = option("collectTimeoutMs", "2000",
        "How long a scrape waits for Minecraft's main thread, after that the previously collected values are served.");
    public static final Option DISCOVERY_DIR = option("discoveryDir", "${user.home}/.hmc-prometheus/targets",
        "Directory for Prometheus file based service discovery (file_sd_configs). Empty to disable.");
    public static final Option DISCOVERY_ADDRESS = option("discoveryAddress", "",
        "The address Prometheus should scrape, written to the discovery file. Defaults to 'host', or 127.0.0.1 if host is 0.0.0.0.");
    public static final Option GRAFANA_URL = option("grafanaUrl", "http://localhost:3000",
        "Base URL of Grafana, used by the 'prometheus' command to print a link to the dashboard of this client. Empty to disable.");
    public static final Option LOKI_URL = option("lokiUrl", "",
        "Base URL of Loki to push the game log to, e.g. http://127.0.0.1:3100. Empty to disable.");
    public static final Option LOKI_BACKFILL = option("lokiBackfill", "true",
        "Whether to also push the lines of logs/latest.log that were logged before HMC-Prometheus started.");
    public static final Option SCREENSHOTS = option("screenshots", "true",
        "Whether to serve screenshots on /screenshot.png and /live. Only works if the game renders, not in HeadlessMc's headless mode.");
    public static final Option CONSOLE = option("console", "false",
        "Whether commands of HMC-Specifics can be executed from the web console on /console, e.g. in Grafana. Anyone who can reach the client can then control the game.");
    public static final Option SCREENSHOT_INTERVAL = option("screenshotIntervalMs", "1000",
        "Minimum time between two screenshots, all viewers share the same screenshot.");

    private final Map<String, String> values;
    private final File file;

    private PrometheusConfig(File file, Map<String, String> values) {
        this.file = file;
        this.values = values;
    }

    /**
     * Loads the config file, writing it first if it does not exist.
     *
     * @return the loaded config.
     * @throws IOException if the file could not be read or written.
     */
    public static PrometheusConfig load() throws IOException {
        File file = new File(System.getProperty(FILE_PROPERTY, "config" + File.separator + "hmc-prometheus.properties"));
        Map<String, String> values = new LinkedHashMap<>();
        for (Option option : OPTIONS.values()) {
            values.put(option.key, option.defaultValue);
        }

        if (file.exists()) {
            Properties properties = new Properties();
            try (InputStream is = Files.newInputStream(file.toPath())) {
                properties.load(is);
            }

            for (String key : properties.stringPropertyNames()) {
                if (OPTIONS.containsKey(key)) {
                    values.put(key, properties.getProperty(key).trim());
                }
            }
        }

        PrometheusConfig config = new PrometheusConfig(file, values);
        if (!file.exists()) {
            config.save();
        }

        return config;
    }

    /**
     * Writes all options with their descriptions to the config file.
     *
     * @throws IOException if the file could not be written.
     */
    public void save() throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }

        try (OutputStream os = Files.newOutputStream(file.toPath());
             Writer writer = new OutputStreamWriter(os, StandardCharsets.ISO_8859_1)) {
            writer.write("# HMC-Prometheus, a Prometheus exporter for the Minecraft client.\n");
            writer.write("# Every option can be overridden with a system property, e.g. -D" + SYSTEM_PROPERTY_PREFIX + PORT.key + "=9401\n");
            writer.write("# Use the 'prometheus set <option> <value>' command to change options while the game is running.\n");
            for (Option option : OPTIONS.values()) {
                writer.write("\n# " + option.description + "\n");
                writer.write("# Default: " + option.defaultValue + "\n");
                writer.write(escape(option.key) + "=" + escape(values.get(option.key)) + "\n");
            }
        }
    }

    /**
     * Sets the value of an option in the file, system properties still take precedence.
     *
     * @param key the key of the option.
     * @param value the new value.
     * @throws IllegalArgumentException if there is no option with the given key.
     */
    public void set(String key, String value) {
        Option option = getOption(key);
        values.put(option.key, value.trim());
    }

    /**
     * @param option the option to get.
     * @return the value of the option, from the system properties or the file, with ${...} system properties expanded.
     */
    public String get(Option option) {
        String value = System.getProperty(SYSTEM_PROPERTY_PREFIX + option.key);
        if (value == null) {
            value = values.get(option.key);
        }

        return expand(value == null ? option.defaultValue : value);
    }

    public boolean getBoolean(Option option) {
        return Boolean.parseBoolean(get(option));
    }

    public int getInt(Option option) {
        try {
            return Integer.parseInt(get(option));
        } catch (NumberFormatException e) {
            return Integer.parseInt(option.defaultValue);
        }
    }

    public List<String> getList(Option option) {
        List<String> result = new ArrayList<>();
        for (String entry : get(option).split(",")) {
            if (!entry.trim().isEmpty()) {
                result.add(entry.trim());
            }
        }

        return result;
    }

    /**
     * @param option the option to check.
     * @return {@code true} if the option is overridden by a system property.
     */
    public boolean isOverridden(Option option) {
        return System.getProperty(SYSTEM_PROPERTY_PREFIX + option.key) != null;
    }

    public File getFile() {
        return file;
    }

    public static List<Option> getOptions() {
        return Collections.unmodifiableList(new ArrayList<>(OPTIONS.values()));
    }

    public static Option getOption(String key) {
        for (Option option : OPTIONS.values()) {
            if (option.key.equalsIgnoreCase(key)) {
                return option;
            }
        }

        throw new IllegalArgumentException("Unknown option '" + key + "', options: " + Arrays.toString(OPTIONS.keySet().toArray()));
    }

    private static Option option(String key, String defaultValue, String description) {
        Option option = new Option(key, defaultValue, description);
        OPTIONS.put(key, option);
        return option;
    }

    /**
     * Replaces ${name} with the system property name.
     */
    static String expand(String value) {
        StringBuilder result = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            int start = value.indexOf("${", index);
            int end = start < 0 ? -1 : value.indexOf('}', start);
            if (end < 0) {
                result.append(value, index, value.length());
                break;
            }

            String property = System.getProperty(value.substring(start + 2, end));
            result.append(value, index, start).append(property == null ? "" : property);
            index = end + 1;
        }

        return result.toString();
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c > 0x7e) {
                // Properties are read as ISO-8859-1
                result.append(String.format("\\u%04x", (int) c));
                continue;
            }

            if (c == '\\' || c == '=' || c == ':' || c == '#' || c == '!') {
                result.append('\\');
            }

            result.append(c);
        }

        return result.toString();
    }

    public static final class Option {
        private final String key;
        private final String defaultValue;
        private final String description;

        private Option(String key, String defaultValue, String description) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.description = description;
        }

        public String getKey() {
            return key;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getDescription() {
            return description;
        }
    }

}
