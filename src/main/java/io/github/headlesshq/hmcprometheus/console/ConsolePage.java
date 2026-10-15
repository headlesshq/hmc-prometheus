package io.github.headlesshq.hmcprometheus.console;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The HTML of the web console, from hmc_prometheus/console.html.
 */
final class ConsolePage {
    private static volatile String template;

    private ConsolePage() {
        throw new AssertionError();
    }

    static String render(String client, boolean enabled) throws IOException {
        return getTemplate()
            .replace("{{client}}", escapeJs(client))
            .replace("{{enabled}}", String.valueOf(enabled));
    }

    private static String getTemplate() throws IOException {
        String result = template;
        if (result == null) {
            try (InputStream is = ConsolePage.class.getResourceAsStream("/hmc_prometheus/console.html")) {
                if (is == null) {
                    throw new IOException("Missing resource hmc_prometheus/console.html");
                }

                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                }

                result = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                template = result;
            }
        }

        return result;
    }

    /**
     * Escapes a value for a double quoted JavaScript string inside a script tag.
     */
    static String escapeJs(String value) {
        StringBuilder result = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\' || c == '<' || c == '>' || c == '&' || c < 0x20 || c == '\u2028' || c == '\u2029') {
                result.append(String.format("\\u%04x", (int) c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

}
