package io.github.headlesshq.hmcprometheus;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prometheus file based service discovery, so that Prometheus finds every running client, no matter which port it got.
 * Every client writes {@code hmc-prometheus-<port>.json} into the discovery directory and deletes it when it stops.
 * Files of clients that crashed are deleted by the next client that starts, if nothing listens on their port anymore.
 * Until then Prometheus reports them with {@code up == 0}, so a crashed client shows up in Grafana.
 */
final class TargetDiscovery {
    private static final Logger LOGGER = Logger.getLogger(TargetDiscovery.class.getName());
    private static final Pattern TARGET = Pattern.compile("\"targets\"\\s*:\\s*\\[\\s*\"([^\"]+):(\\d+)\"");
    private static final String FILE_PREFIX = "hmc-prometheus-";

    private final File dir;

    TargetDiscovery(File dir) {
        this.dir = dir;
    }

    /**
     * Writes the discovery file for this client.
     *
     * @param address the address Prometheus should scrape.
     * @param port the port of the HTTP server.
     * @param client the value of the client label.
     * @return the written file.
     * @throws IOException if the file could not be written.
     */
    File write(String address, int port, String client) throws IOException {
        Files.createDirectories(dir.toPath());
        cleanUp(port);
        File file = new File(dir, FILE_PREFIX + port + ".json");
        String json = "[{\"targets\":[\"" + escape(address) + ":" + port + "\"],\"labels\":{\"client\":\"" + escape(client) + "\"}}]\n";
        // Prometheus watches the directory, it must not see a partially written file
        File tmp = new File(dir, "." + file.getName() + ".tmp");
        Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    /**
     * Deletes the files of clients that are not running anymore.
     */
    private void cleanUp(int ownPort) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(FILE_PREFIX) && name.endsWith(".json"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            try {
                Matcher matcher = TARGET.matcher(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                if (!matcher.find()) {
                    continue;
                }

                int port = Integer.parseInt(matcher.group(2));
                if (port != ownPort && !isListening(matcher.group(1), port)) {
                    LOGGER.info("Deleting discovery file of a client that is not running anymore: " + file);
                    Files.deleteIfExists(file.toPath());
                }
            } catch (IOException | NumberFormatException e) {
                LOGGER.log(Level.FINE, "Failed to check " + file, e);
            }
        }
    }

    private static boolean isListening(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 250);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                result.append('\\').append(c);
            } else if (c < 0x20) {
                result.append(String.format("\\u%04x", (int) c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

}
