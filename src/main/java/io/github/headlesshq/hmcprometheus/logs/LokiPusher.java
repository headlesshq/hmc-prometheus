package io.github.headlesshq.hmcprometheus.logs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pushes log lines to Loki in batches, with the labels {@code job="minecraft"}, {@code client} and {@code level}.
 * Lines are queued, so logging never waits for Loki. If Loki is not reachable, the batch is retried with a backoff
 * and the oldest lines are dropped once the queue is full.
 * Uses the JSON push API, which needs no protobuf or snappy.
 */
public final class LokiPusher {
    private static final Logger LOGGER = Logger.getLogger(LokiPusher.class.getName());
    private static final int CAPACITY = 20_000;
    private static final int BATCH_SIZE = 1_000;
    private static final long MAX_BACKOFF_MS = 30_000L;

    private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final Semaphore flushRequests = new Semaphore(0);
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failedPushes = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong();
    private final Thread thread;
    private final String client;
    private final URL url;

    private volatile int inFlight;
    private volatile boolean running = true;
    private volatile boolean lastPushSucceeded = true;
    private volatile String lastError;

    public LokiPusher(String baseUrl, String client) throws IOException {
        this.url = new URL(trimSlash(baseUrl) + "/loki/api/v1/push");
        this.client = client;
        this.thread = new Thread(this::run, "hmc-prometheus-loki");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    /**
     * Queues a line, never blocks.
     *
     * @param timeMillis the time the line was logged at.
     * @param level the log level, e.g. INFO.
     * @param line the line, may contain ANSI escape codes and line breaks.
     */
    public void add(long timeMillis, String level, String line) {
        // Loki deduplicates lines with the same timestamp, the sequence keeps lines of the same millisecond apart and in order
        long nanos = TimeUnit.MILLISECONDS.toNanos(timeMillis) + sequence.getAndIncrement() % 1_000_000L;
        // HMC-Specifics resets colors with the short form ESC[m, which Grafana does not understand
        Entry entry = new Entry(nanos, level.toLowerCase(Locale.ROOT), line.replace("\u001B[m", "\u001B[0m"));
        while (!queue.offer(entry)) {
            if (queue.poll() != null) {
                dropped.incrementAndGet();
            }
        }
    }

    /**
     * Pushes the queued lines now, instead of waiting up to a second for more lines.
     * E.g. after a command from the web console has been executed, so that its output shows up quickly.
     */
    public void flush() {
        flushRequests.release();
    }

    /**
     * Stops the pusher, trying to push the queued lines once more.
     */
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long getSent() {
        return sent.get();
    }

    public long getDropped() {
        return dropped.get();
    }

    public long getFailedPushes() {
        return failedPushes.get();
    }

    /**
     * @return the lines waiting to be pushed, including the batch that is being retried.
     */
    public int getQueued() {
        return queue.size() + inFlight;
    }

    public boolean isLastPushSucceeded() {
        return lastPushSucceeded;
    }

    public String getLastError() {
        return lastError;
    }

    public String getUrl() {
        return url.toString();
    }

    private void run() {
        List<Entry> batch = new ArrayList<>();
        long backoff = 1_000L;
        while (running || !batch.isEmpty() || !queue.isEmpty()) {
            try {
                if (batch.isEmpty()) {
                    Entry first = running ? queue.poll(1, TimeUnit.SECONDS) : queue.poll();
                    if (first == null) {
                        continue;
                    }

                    batch.add(first);
                    // collect what else arrives within a second, so that we push less often, unless a flush is requested
                    if (running) {
                        flushRequests.tryAcquire(1, TimeUnit.SECONDS);
                        flushRequests.drainPermits();
                    }

                    queue.drainTo(batch, BATCH_SIZE - 1);
                    inFlight = batch.size();
                }

                if (push(batch)) {
                    batch.clear();
                    inFlight = 0;
                    backoff = 1_000L;
                } else if (!running) {
                    return; // one last attempt on close
                } else {
                    Thread.sleep(backoff);
                    backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
                }
            } catch (InterruptedException e) {
                if (running) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * @return {@code true} if the batch should not be retried.
     */
    private boolean push(List<Entry> batch) {
        HttpURLConnection connection = null;
        try {
            byte[] body = toJson(batch).getBytes(StandardCharsets.UTF_8);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int code = connection.getResponseCode();
            if (code / 100 == 2) {
                sent.addAndGet(batch.size());
                onResult(true, null);
                return true;
            }

            String error = "HTTP " + code + ": " + readError(connection);
            onResult(false, error);
            // 4xx, e.g. lines that are too old, will not succeed if we retry, 429 and 5xx might
            if (code / 100 == 4 && code != 429) {
                dropped.addAndGet(batch.size());
                return true;
            }

            return false;
        } catch (IOException e) {
            onResult(false, e.toString());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void onResult(boolean success, String error) {
        if (!success) {
            failedPushes.incrementAndGet();
            // only log when the state changes, a missing Loki should not spam the log, which we also push
            if (lastPushSucceeded) {
                LOGGER.log(Level.WARNING, "Failed to push logs to " + url + ": " + error);
            }

            lastError = error;
        } else if (!lastPushSucceeded) {
            LOGGER.info("Pushing logs to " + url + " again");
        }

        lastPushSucceeded = success;
    }

    private String toJson(List<Entry> batch) {
        Map<String, List<Entry>> byLevel = new LinkedHashMap<>();
        for (Entry entry : batch) {
            byLevel.computeIfAbsent(entry.level, k -> new ArrayList<>()).add(entry);
        }

        StringBuilder json = new StringBuilder("{\"streams\":[");
        boolean firstStream = true;
        for (Map.Entry<String, List<Entry>> stream : byLevel.entrySet()) {
            if (!firstStream) {
                json.append(',');
            }

            firstStream = false;
            json.append("{\"stream\":{\"job\":\"minecraft\",\"client\":");
            appendString(json, client);
            json.append(",\"level\":");
            appendString(json, stream.getKey());
            json.append("},\"values\":[");
            boolean firstValue = true;
            for (Entry entry : stream.getValue()) {
                if (!firstValue) {
                    json.append(',');
                }

                firstValue = false;
                json.append("[\"").append(entry.nanos).append("\",");
                appendString(json, entry.line);
                json.append(']');
            }

            json.append("]}");
        }

        return json.append("]}").toString();
    }

    static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        // e.g. the ESC of ANSI escape codes
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
            }
        }

        json.append('"');
    }

    private static String readError(HttpURLConnection connection) {
        try (InputStream is = connection.getErrorStream()) {
            if (is == null) {
                return "";
            }

            byte[] buffer = new byte[512];
            int read = is.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final class Entry {
        private final long nanos;
        private final String level;
        private final String line;

        private Entry(long nanos, String level, String line) {
            this.nanos = nanos;
            this.level = level;
            this.line = line;
        }
    }

}
