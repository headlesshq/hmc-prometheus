package io.github.headlesshq.hmcprometheus.logs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the lines of {@code logs/latest.log} that were logged before HMC-Prometheus started capturing the log.
 * The lines only contain the time, not the date, so they are assumed to be from the last 24 hours.
 * Lines that do not start with a time, e.g. stack traces, are appended to the previous line.
 */
public final class LatestLog {
    // [12:34:56] [Render thread/INFO]: ..., Forge adds the logger: [12:34:56] [main/INFO] [FML]: ...
    private static final Pattern LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2}):(\\d{2})(?:\\.\\d+)?] \\[[^\\]]*?/([A-Z]+)]");

    private LatestLog() {
        throw new AssertionError();
    }

    /**
     * @param file the log file, usually logs/latest.log.
     * @return the current size of the file, so that only the lines before this point are read.
     */
    public static long size(File file) {
        return file.isFile() ? file.length() : 0L;
    }

    /**
     * Pushes the first {@code size} bytes of the log file.
     *
     * @param file the log file.
     * @param size how many bytes to read, see {@link #size(File)}.
     * @param pusher the pusher to add the lines to.
     * @return the amount of pushed lines.
     * @throws IOException if the file could not be read.
     */
    public static int push(File file, long size, LokiPusher pusher) throws IOException {
        if (size <= 0) {
            return 0;
        }

        int count = 0;
        long now = System.currentTimeMillis();
        try (InputStream is = Files.newInputStream(file.toPath());
             BufferedReader reader = new BufferedReader(new InputStreamReader(new LimitedInputStream(is, size), StandardCharsets.UTF_8))) {
            StringBuilder entry = null;
            long time = now;
            String level = "INFO";
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = LINE.matcher(line);
                if (matcher.find()) {
                    if (entry != null) {
                        pusher.add(time, level, entry.toString());
                        count++;
                    }

                    time = toMillis(now, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
                    level = matcher.group(4);
                    // the time is the timestamp of the line in Loki
                    entry = new StringBuilder(line.substring(line.indexOf(']') + 2));
                } else if (entry != null) {
                    entry.append('\n').append(line);
                }
            }

            if (entry != null) {
                pusher.add(time, level, entry.toString());
                count++;
            }
        }

        return count;
    }

    static long toMillis(long now, int hours, int minutes, int seconds) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, hours);
        calendar.set(Calendar.MINUTE, minutes);
        calendar.set(Calendar.SECOND, seconds);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() > now + 60_000L) { // logged yesterday, before midnight
            calendar.add(Calendar.DAY_OF_MONTH, -1);
        }

        return calendar.getTimeInMillis();
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;

        private LimitedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int read = delegate.read();
            if (read >= 0) {
                remaining--;
            }

            return read;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }

            int read = delegate.read(b, off, (int) Math.min(len, remaining));
            if (read > 0) {
                remaining -= read;
            }

            return read;
        }
    }

}
