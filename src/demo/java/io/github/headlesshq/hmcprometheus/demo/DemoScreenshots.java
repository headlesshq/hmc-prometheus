package io.github.headlesshq.hmcprometheus.demo;

import me.earth.headlessmc.mc.screenshot.ScreenshotEncoder;
import me.earth.headlessmc.mc.screenshot.ScreenshotProvider;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.function.Consumer;

/**
 * Draws a simple scene instead of reading Minecraft's framebuffer.
 */
final class DemoScreenshots implements ScreenshotProvider {
    private final DemoMetricsSource source;
    private final String name;

    DemoScreenshots(DemoMetricsSource source, String name) {
        this.source = source;
        this.name = name;
    }

    @Override
    public void takeScreenshot(File file, Consumer<Throwable> callback) {
        BufferedImage image = new BufferedImage(854, 480, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        long seconds = System.currentTimeMillis() / 1000L;
        // the sky moves through the day, ground with a few blocks
        float day = (seconds % 120) / 120.0f;
        Color sky = Color.getHSBColor(0.58f, 0.6f, 0.35f + 0.6f * (float) Math.abs(Math.sin(Math.PI * day)));
        g.setPaint(new GradientPaint(0, 0, sky.brighter(), 0, 300, sky));
        g.fillRect(0, 0, 854, 300);
        g.setColor(new Color(0x5d8c33));
        g.fillRect(0, 300, 854, 40);
        g.setColor(new Color(0x7a5230));
        g.fillRect(0, 340, 854, 140);
        for (int i = 0; i < 12; i++) {
            int x = (int) ((i * 97 + seconds * 13) % 900) - 40;
            g.setColor(new Color(0x6f6f6f));
            g.fillRect(x, 260, 40, 40);
        }

        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        g.drawString("Demo client " + name + " (" + source.getMinecraftVersion() + ")", 10, 22);
        g.drawString("FPS: " + source.getFps(), 10, 42);
        g.drawString(new SimpleDateFormat("HH:mm:ss").format(new Date()), 10, 62);
        g.dispose();
        ScreenshotEncoder.writeAsync(image, file, callback);
    }

}
