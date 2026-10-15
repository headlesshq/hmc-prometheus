package io.github.headlesshq.hmcprometheus.screenshot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import io.github.headlesshq.hmcprometheus.console.ConsoleHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves everything except /metrics and /-/healthy:
 * <ul>
 *     <li>{@code /screenshot.png}: the latest screenshot.</li>
 *     <li>{@code /live}: a stream of screenshots ({@code multipart/x-mixed-replace}), which browsers show in an img tag.</li>
 *     <li>{@code /console} and {@code /command}: the web console, see {@link ConsoleHandler}.</li>
 *     <li>{@code /}: an index page.</li>
 * </ul>
 * If the game does not render, the images are replaced with an SVG that explains why.
 */
public final class ScreenshotHandler implements HttpHandler {
    private static final String BOUNDARY = "hmc-prometheus-frame";
    private static final long TIMEOUT_SECONDS = 10L;
    /**
     * The JDK HttpServer closes responses that take longer than sun.net.httpserver.maxRspTime (Prometheus sets 600s),
     * so streams end earlier. Grafana opens a new one when the dashboard refreshes.
     */
    private static final long MAX_STREAM_MS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_STREAMS = 4;

    /**
     * Browsers do not always close a stream when an img tag reloads, so when there are too many streams,
     * the oldest one is ended instead of refusing the new one.
     */
    private final Deque<AtomicBoolean> streams = new ArrayDeque<>();
    private final AtomicLong screenshotRequests = new AtomicLong();
    private final AtomicLong liveRequests = new AtomicLong();
    private final ConsoleHandler console;
    private final ScreenshotCache cache;
    private final String client;

    /**
     * @param cache the screenshots to serve, or {@code null} if screenshots are disabled.
     * @param client the name of the client, shown on the index page.
     * @param console the web console.
     */
    public ScreenshotHandler(ScreenshotCache cache, String client, ConsoleHandler console) {
        this.console = console;
        this.cache = cache;
        this.client = client;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if ("/screenshot.png".equals(path)) {
                screenshot(exchange);
            } else if ("/live".equals(path)) {
                live(exchange);
            } else if ("/console".equals(path)) {
                console.page(exchange);
            } else if ("/command".equals(path)) {
                console.command(exchange);
            } else if ("/".equals(path)) {
                send(exchange, 200, "text/html; charset=utf-8", index().getBytes(StandardCharsets.UTF_8));
            } else {
                send(exchange, 404, "text/plain; charset=utf-8", "Not found\n".getBytes(StandardCharsets.UTF_8));
            }
        } finally {
            exchange.close();
        }
    }

    private void screenshot(HttpExchange exchange) throws IOException {
        screenshotRequests.incrementAndGet();
        if (cache == null) {
            send(exchange, 404, "image/svg+xml", placeholder("Screenshots are disabled in the HMC-Prometheus config"));
            return;
        }

        try {
            send(exchange, 200, "image/png", take());
        } catch (ScreenshotException e) {
            send(exchange, 503, "image/svg+xml", placeholder(e.getMessage()));
        }
    }

    private void live(HttpExchange exchange) throws IOException {
        if (cache == null) {
            send(exchange, 404, "image/svg+xml", placeholder("Screenshots are disabled in the HMC-Prometheus config"));
            return;
        }

        liveRequests.incrementAndGet();
        AtomicBoolean ended = new AtomicBoolean();
        synchronized (streams) {
            while (streams.size() >= MAX_STREAMS) {
                streams.removeFirst().set(true);
            }

            streams.addLast(ended);
        }

        try {
            exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            OutputStream os = exchange.getResponseBody();
            long end = System.currentTimeMillis() + MAX_STREAM_MS;
            while (System.currentTimeMillis() < end && !ended.get()) {
                long start = System.currentTimeMillis();
                try {
                    writeFrame(os, "image/png", take());
                } catch (ScreenshotException e) {
                    // nothing will change, e.g. the game is headless, show why and end the stream
                    writeFrame(os, "image/svg+xml", placeholder(e.getMessage()));
                    return;
                }

                Thread.sleep(Math.max(100L, cache.getIntervalMs() - (System.currentTimeMillis() - start)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // the viewer closed the stream
        } finally {
            synchronized (streams) {
                streams.remove(ended);
            }
        }
    }

    public long getScreenshotRequests() {
        return screenshotRequests.get();
    }

    public long getLiveRequests() {
        return liveRequests.get();
    }

    public int getLiveStreams() {
        synchronized (streams) {
            return streams.size();
        }
    }

    private byte[] take() throws ScreenshotException {
        if (!cache.isAvailable()) {
            throw new ScreenshotException("The game does not render (HeadlessMc's headless mode), so there are no screenshots");
        }

        try {
            return cache.get().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ScreenshotException("Failed to take a screenshot: " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new ScreenshotException("Timed out waiting for a screenshot, the game might be frozen");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ScreenshotException("Interrupted");
        }
    }

    private static void writeFrame(OutputStream os, String contentType, byte[] bytes) throws IOException {
        String header = "--" + BOUNDARY + "\r\nContent-Type: " + contentType + "\r\nContent-Length: " + bytes.length + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.US_ASCII));
        os.write(bytes);
        os.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        os.flush();
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    static byte[] placeholder(String message) {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"854\" height=\"480\" viewBox=\"0 0 854 480\">"
            + "<rect width=\"854\" height=\"480\" fill=\"#22252b\"/>"
            + "<text x=\"427\" y=\"240\" fill=\"#ccccdc\" font-family=\"sans-serif\" font-size=\"18\" text-anchor=\"middle\">"
            + escape(message) + "</text></svg>";
        return svg.getBytes(StandardCharsets.UTF_8);
    }

    private String index() {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><title>HMC-Prometheus</title></head>"
            + "<body style=\"font-family:sans-serif\"><h1>HMC-Prometheus: " + escape(client) + "</h1><ul>"
            + "<li><a href=\"/metrics\">/metrics</a></li>"
            + "<li><a href=\"/screenshot.png\">/screenshot.png</a></li>"
            + "<li><a href=\"/live\">/live</a></li>"
            + "<li><a href=\"/console\">/console</a></li>"
            + "<li><a href=\"/-/healthy\">/-/healthy</a></li>"
            + "</ul></body></html>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final class ScreenshotException extends Exception {
        private ScreenshotException(String message) {
            super(message);
        }
    }

}
