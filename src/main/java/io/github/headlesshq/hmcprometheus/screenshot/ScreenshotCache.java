package io.github.headlesshq.hmcprometheus.screenshot;

import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.screenshot.Screenshots;

import java.util.concurrent.CompletableFuture;

/**
 * Takes at most one screenshot per interval, all viewers share it.
 * Screenshots are only taken while someone requests them.
 */
public final class ScreenshotCache {
    private final Minecraft mc;
    private final long intervalMs;

    private CompletableFuture<byte[]> current;
    private long takenAt;

    public ScreenshotCache(Minecraft mc, long intervalMs) {
        this.mc = mc;
        this.intervalMs = Math.max(0L, intervalMs);
    }

    /**
     * @return the latest screenshot, or a new one if the latest is older than the interval.
     */
    public synchronized CompletableFuture<byte[]> get() {
        long now = System.currentTimeMillis();
        if (current != null && (!current.isDone() || now - takenAt < intervalMs)) {
            return current;
        }

        takenAt = now;
        current = Screenshots.take(mc);
        return current;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public boolean isAvailable() {
        return Screenshots.isAvailable();
    }

}
