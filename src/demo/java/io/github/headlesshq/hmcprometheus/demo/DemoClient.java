package io.github.headlesshq.hmcprometheus.demo;

import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.auth.McAccount;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.console.StdConsole;
import me.earth.headlessmc.mc.metrics.DefaultMetrics;
import me.earth.headlessmc.mc.metrics.MetricsService;
import me.earth.headlessmc.mc.metrics.MetricsTracker;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.FutureTask;
import java.util.function.Supplier;

/**
 * Simulates a Minecraft client with HMC-Specifics and HMC-Prometheus installed, without Minecraft.
 * The metrics come from HMC-Specifics' DefaultMetrics with a simulated {@link DemoMetricsSource},
 * HMC-Prometheus is started the same way HMC-Specifics starts it, through its MetricsProvider.
 * Commands like 'prometheus' can be typed into stdin.
 * Usage: {@code DemoClient <name> [server|singleplayer|menu|laggy]}
 */
public final class DemoClient {
    private static final Logger CHAT = LogManager.getLogger("net.minecraft.client.gui.components.ChatComponent");
    private static final Logger LOGGER = LogManager.getLogger("net.minecraft.client.Minecraft");
    private static final String RESET = "\u001B[0m";
    private static final String[] CHAT_COLORS = {"\u001B[0;33m", "\u001B[0;36m", "\u001B[0;35m", "\u001B[0;32m"};
    private static final String[] MESSAGES = {"hi", "anyone got diamonds?", "gg", "brb", "where is the nether portal?", "lag!"};

    private final Queue<FutureTask<?>> tasks = new ConcurrentLinkedQueue<>();
    private final MetricsTracker tracker = MetricsTracker.getInstance();
    private final Scenario scenario;
    private long gameTime;

    private DemoClient(Scenario scenario) {
        this.scenario = scenario;
    }

    public static void main(String[] args) throws Exception {
        String name = args.length > 0 ? args[0] : "DemoClient";
        Scenario scenario = args.length > 1 ? Scenario.valueOf(args[1].toUpperCase(Locale.ROOT)) : Scenario.SERVER;
        DemoClient client = new DemoClient(scenario);
        DemoMetricsSource source = new DemoMetricsSource(scenario, name);
        LOGGER.info("Setting user: {}", name);
        LOGGER.info("Backend library: LWJGL version 3.3.3 (demo)");
        Minecraft mc = client.createMinecraft(source, new DemoScreenshots(source, name), new McAccount(name, UUID.nameUUIDFromBytes(name.getBytes()), "token"));

        // what SpecificsInitializer does when Minecraft has been constructed
        MetricsService metrics = MetricsService.getInstance();
        DefaultMetrics.registerAll(metrics, mc, client.tracker);
        metrics.loadProviders(mc, DemoClient.class.getClassLoader());
        client.tracker.onJoin();

        Thread commands = new Thread(() -> client.readCommands(mc), "HeadlessMc-CommandLine");
        commands.setDaemon(true);
        commands.start();
        client.runMainThread();
    }

    /**
     * Runs scheduled tasks every frame and ticks 20 times per second, like Minecraft's main thread.
     */
    private void runMainThread() throws InterruptedException {
        long lastTick = System.nanoTime();
        long lastTimeUpdate = System.nanoTime();
        long lastLog = System.nanoTime();
        int logs = 0;
        while (true) {
            FutureTask<?> task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }

            long now = System.nanoTime();
            if (now - lastTick >= 50_000_000L) {
                lastTick = now;
                tracker.onTickStart();
                Thread.sleep(scenario == Scenario.LAGGY ? 30 : 3);
                for (int i = 0; i < 5; i++) {
                    tracker.onPacketReceived();
                }

                tracker.onPacketSent();
                tracker.onTickEnd();
            }

            if (now - lastLog >= 4_000_000_000L) {
                lastLog = now;
                log(logs++);
            }

            if (now - lastTimeUpdate >= 1_000_000_000L) {
                lastTimeUpdate = now;
                // a lagging server sends less game time per second
                gameTime += scenario == Scenario.LAGGY ? 14 + (long) (Math.random() * 4) : 20;
                tracker.onTimeUpdate(gameTime);
            }

            // the laggy client freezes for 5 seconds every 2 minutes
            if (scenario == Scenario.LAGGY && System.currentTimeMillis() / 1000 % 120 == 0) {
                Thread.sleep(5000);
            }

            Thread.sleep(1000 / 60);
        }
    }

    /**
     * Logs chat with ANSI colors like HMC-Specifics does, and now and then a warning or an error.
     */
    private void log(int count) {
        String player = new String[]{"Steve", "Alex", "Notch", "Jeb_"}[count % 4];
        String color = CHAT_COLORS[count % CHAT_COLORS.length];
        CHAT.info("[CHAT] <{}{}{}> {}", color, player, RESET, MESSAGES[count % MESSAGES.length]);
        if (count % 5 == 0) {
            CHAT.info("[CHAT] \u001B[0;93m{} joined the game{}", player, RESET);
        }

        if (scenario == Scenario.LAGGY && count % 3 == 0) {
            LOGGER.warn("Can't keep up! Is the server overloaded? Running 2500ms or 50 ticks behind");
        }

        if (count % 15 == 7) {
            LOGGER.error("Failed to load texture minecraft:textures/entity/demo.png", new java.io.FileNotFoundException("minecraft:textures/entity/demo.png"));
        }
    }

    private void readCommands(Minecraft mc) {
        MinecraftContext ctx = new MinecraftContext(mc, new StdConsole(), line -> line.trim().split("\\s+"), null, null, null);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ctx.execute(line);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private Minecraft createMinecraft(DemoMetricsSource source, DemoScreenshots screenshots, McAccount account) {
        return (Minecraft) Proxy.newProxyInstance(DemoClient.class.getClassLoader(), new Class<?>[]{Minecraft.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "schedule":
                case "scheduleEx":
                    FutureTask<Object> task = args[0] instanceof Supplier
                        ? new FutureTask<>(((Supplier<?>) args[0])::get)
                        : new FutureTask<>((Runnable) args[0], null);
                    tasks.add(task);
                    return task;
                case "getMetricsSource":
                    return source;
                case "getScreenshotProvider":
                    return screenshots;
                case "getMcAccount":
                    return account;
                case "getHandle":
                    return proxy;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "DemoMinecraft";
                default:
                    return null;
            }
        });
    }

    enum Scenario {
        SERVER("1.21.10"),
        SINGLEPLAYER("1.20.1"),
        MENU("1.12.2"),
        LAGGY("1.8.9");

        final String version;

        Scenario(String version) {
            this.version = version;
        }
    }

}
