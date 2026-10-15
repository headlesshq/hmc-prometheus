package io.github.headlesshq.hmcprometheus.console;

import com.sun.net.httpserver.HttpExchange;
import io.github.headlesshq.hmcprometheus.logs.LokiPusher;
import me.earth.headlessmc.mc.commands.MinecraftContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * A web console for the commands of HMC-Specifics:
 * {@code GET /console} serves a page with an input line, {@code POST /command} executes one command and returns its output.
 * <p>
 * Executing commands from a browser is dangerous, every website you visit could try to send requests to 127.0.0.1, so:
 * <ul>
 *     <li>the console is disabled unless the config option {@code console} is true,</li>
 *     <li>commands need the header {@value #HEADER}. Browsers only send custom headers to other origins after a
 *     CORS preflight request, which this server never allows, and HTML forms can not set headers,</li>
 *     <li>the Host header has to be one of the addresses of this client, against DNS rebinding,
 *     where another domain resolves to 127.0.0.1.</li>
 * </ul>
 */
public final class ConsoleHandler {
    public static final String HEADER = "X-HMC-Console";
    /**
     * Response header, whether the output has been pushed to Loki.
     */
    public static final String LOKI_HEADER = "X-HMC-Loki";
    private static final long TIMEOUT_SECONDS = 30L;
    private static final int MAX_COMMAND_LENGTH = 4096;

    private final Set<String> allowedHosts = new HashSet<>();
    private final Supplier<LokiPusher> pusher;
    private final boolean enabled;
    private final String client;

    /**
     * @param enabled whether commands may be executed.
     * @param client the name of the client, shown on the page.
     * @param hosts the host names this client can be reached with, localhost and loopback addresses are always allowed.
     * @param pusher the pusher to send the commands and their output to Loki with, supplies {@code null} if Loki is disabled.
     */
    public ConsoleHandler(boolean enabled, String client, Collection<String> hosts, Supplier<LokiPusher> pusher) {
        this.pusher = pusher;
        this.enabled = enabled;
        this.client = client;
        allowedHosts.add("127.0.0.1");
        allowedHosts.add("localhost");
        allowedHosts.add("[::1]");
        for (String host : hosts) {
            if (host != null && !host.isEmpty() && !"0.0.0.0".equals(host) && !"::".equals(host)) {
                allowedHosts.add(host.toLowerCase(Locale.ROOT));
            }
        }
    }

    public void page(HttpExchange exchange) throws IOException {
        String html = ConsolePage.render(client, enabled);
        send(exchange, 200, "text/html; charset=utf-8", html);
    }

    public void command(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            // also answers CORS preflight requests, without allowing anything
            exchange.getResponseHeaders().set("Allow", "POST");
            send(exchange, 405, "text/plain; charset=utf-8", "Use POST\n");
            return;
        }

        if (!enabled) {
            send(exchange, 403, "text/plain; charset=utf-8",
                 "The console is disabled. Enable it in the game with: prometheus set console true\n");
            return;
        }

        if (exchange.getRequestHeaders().getFirst(HEADER) == null || !isAllowedHost(exchange.getRequestHeaders().getFirst("Host"))) {
            send(exchange, 403, "text/plain; charset=utf-8", "Forbidden\n");
            return;
        }

        String line = readBody(exchange).trim();
        if (line.isEmpty()) {
            send(exchange, 200, "text/plain; charset=utf-8", "");
            return;
        }

        MinecraftContext context = getContext();
        if (context == null) {
            send(exchange, 503, "text/plain; charset=utf-8", "HMC-Specifics has not been initialized yet.\n");
            return;
        }

        // the command and its output go to the console of the game and to Loki, so that the log shows what has been
        // executed, next to what mods log, e.g. Baritone. Output written after the response, e.g. by render, still goes there.
        LokiPusher pusher = this.pusher.get();
        LogConsole output = new LogConsole(context.getConsole(), pusher);
        output.writeCommand(line);
        String timedOut = "";
        try {
            context.execute(line, output).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            timedOut = "\n(still running after " + TIMEOUT_SECONDS + "s, later output only appears in the log)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            timedOut = "\n" + e.getCause();
        }

        if (pusher != null) {
            // the command has been executed, also on the main thread, so its output and what it logged are queued
            pusher.flush();
        }

        exchange.getResponseHeaders().set(LOKI_HEADER, String.valueOf(pusher != null));
        send(exchange, 200, "text/plain; charset=utf-8", output.getOutput() + timedOut);
    }

    private boolean isAllowedHost(String host) {
        if (host == null) {
            return false;
        }

        String name = host.toLowerCase(Locale.ROOT);
        if (name.startsWith("[")) {
            int end = name.indexOf(']');
            name = end < 0 ? name : name.substring(0, end + 1);
        } else if (name.indexOf(':') >= 0) {
            name = name.substring(0, name.indexOf(':'));
        }

        return allowedHosts.contains(name);
    }

    private static MinecraftContext getContext() {
        try {
            return MinecraftContext.getInstance();
        } catch (LinkageError e) { // an HMC-Specifics version without the method
            return null;
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (InputStream is = exchange.getRequestBody()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = is.read(buffer)) != -1 && body.size() <= MAX_COMMAND_LENGTH) {
                body.write(buffer, 0, read);
            }
        }

        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

}
