package io.github.headlesshq.hmcprometheus.logs;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.spi.LoggerContextFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Captures the log of the game with a Log4j appender on the root logger and forwards it to the current {@link LokiPusher}.
 * Minecraft uses Log4j in every version, from 2.0-beta9 in 1.7.10 to the latest 2.x,
 * so only methods that exist in all of these versions are used.
 * HMC-Specifics logs chat messages with ANSI escape codes, which Grafana renders as colors.
 */
public final class LogCapture {
    private static final Logger LOGGER = Logger.getLogger(LogCapture.class.getName());
    private static final String APPENDER_NAME = "HmcPrometheusLoki";

    private static volatile LokiPusher pusher;
    private static boolean installed;

    private LogCapture() {
        throw new AssertionError();
    }

    /**
     * Sets where captured lines go, installs the appender the first time.
     *
     * @param lokiPusher the pusher to forward to, or {@code null} to stop forwarding.
     * @return {@code true} if the appender is installed.
     */
    public static synchronized boolean setPusher(LokiPusher lokiPusher) {
        pusher = lokiPusher;
        if (lokiPusher != null && !installed) {
            installed = install();
        }

        return installed;
    }

    private static boolean install() {
        try {
            LoggerContextFactory factory = LogManager.getFactory();
            if (!(factory instanceof Log4jContextFactory)) {
                LOGGER.warning("Not capturing the log, Log4j uses " + factory);
                return false;
            }

            boolean any = false;
            for (LoggerContext context : ((Log4jContextFactory) factory).getSelector().getLoggerContexts()) {
                any |= install(context);
            }

            return any;
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "Failed to install the Log4j appender, the log is not pushed to Loki", t);
            return false;
        }
    }

    private static boolean install(LoggerContext context) {
        Configuration config = context.getConfiguration();
        LoggerConfig root;
        try {
            root = config.getRootLogger();
        } catch (Throwable t) { // older versions of Log4j do not have this method
            root = config.getLoggerConfig("");
        }

        LokiAppender appender = new LokiAppender();
        appender.start();
        root.addAppender(appender, null, null);
        context.updateLoggers();
        return true;
    }

    static String format(LogEvent event) {
        String logger = event.getLoggerName();
        if (logger != null && logger.lastIndexOf('.') >= 0) {
            logger = logger.substring(logger.lastIndexOf('.') + 1);
        }

        StringBuilder line = new StringBuilder()
            .append('[').append(event.getThreadName()).append('/').append(event.getLevel()).append("] ");
        if (logger != null && !logger.isEmpty()) {
            line.append('[').append(logger).append("]: ");
        }

        line.append(event.getMessage() == null ? "null" : event.getMessage().getFormattedMessage());
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            StringWriter stackTrace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(stackTrace));
            line.append('\n').append(stackTrace.toString().trim());
        }

        return line.toString();
    }

    private static final class LokiAppender extends AbstractAppender {
        @SuppressWarnings("deprecation") // the non-deprecated constructor does not exist in older versions of Log4j
        private LokiAppender() {
            super(APPENDER_NAME, null, null, true);
        }

        @Override
        public void append(LogEvent event) {
            LokiPusher current = pusher;
            if (current != null) {
                try {
                    // the time of the event has different getters in different Log4j versions
                    current.add(System.currentTimeMillis(), String.valueOf(event.getLevel()), format(event));
                } catch (Throwable ignored) {
                    // logging must never fail because of us
                }
            }
        }
    }

}
