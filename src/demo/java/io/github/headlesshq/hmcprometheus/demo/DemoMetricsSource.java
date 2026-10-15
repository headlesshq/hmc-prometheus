package io.github.headlesshq.hmcprometheus.demo;

import me.earth.headlessmc.mc.metrics.MetricsSource;
import me.earth.headlessmc.mc.metrics.PlayerListEntry;
import me.earth.headlessmc.mc.metrics.PlayerPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Simulated values of a Minecraft client, called on the simulated main thread like the real version specific sources.
 */
final class DemoMetricsSource implements MetricsSource {
    private static final String[] NAMES = {"Steve", "Alex", "Notch", "Jeb_", "Dinnerbone", "Grumm", "Herobrine", "Kai", "Sunny", "Zuri"};

    private final long start = System.currentTimeMillis();
    private final DemoClient.Scenario scenario;
    private final String playerName;
    private final Random random;
    private double x;
    private double z;

    DemoMetricsSource(DemoClient.Scenario scenario, String playerName) {
        this.scenario = scenario;
        this.playerName = playerName;
        this.random = new Random(playerName.hashCode());
    }

    private double seconds() {
        return (System.currentTimeMillis() - start) / 1000.0;
    }

    /**
     * A value that oscillates around base, phase shifted per client.
     */
    private double wave(double base, double amplitude, double period) {
        return base + amplitude * Math.sin(2 * Math.PI * seconds() / period + playerName.hashCode());
    }

    @Override
    public String getMinecraftVersion() {
        return scenario.version;
    }

    @Override
    public Integer getFps() {
        return (int) Math.max(1, wave(scenario == DemoClient.Scenario.LAGGY ? 25 : 120, 20, 90) + random.nextInt(10));
    }

    @Override
    public boolean isInWorld() {
        // the menu client joins a server for a minute every three minutes
        return scenario != DemoClient.Scenario.MENU || seconds() % 180 > 120;
    }

    @Override
    public boolean isSingleplayer() {
        return scenario == DemoClient.Scenario.SINGLEPLAYER;
    }

    @Override
    public String getServerAddress() {
        return isSingleplayer() ? null : scenario == DemoClient.Scenario.LAGGY ? "lag.example.org" : "play.example.org";
    }

    @Override
    public String getServerBrand() {
        return isSingleplayer() ? "vanilla" : "Paper";
    }

    @Override
    public Float getTickRate() {
        return 20.0f;
    }

    @Override
    public Boolean isTickingFrozen() {
        return false;
    }

    @Override
    public Double getIntegratedServerMspt() {
        return isSingleplayer() ? wave(18, 10, 60) : null;
    }

    @Override
    public List<PlayerListEntry> getPlayerList() {
        List<PlayerListEntry> players = new ArrayList<>();
        players.add(new PlayerListEntry(playerName, UUID.nameUUIDFromBytes(playerName.getBytes()), (int) wave(45, 15, 40), "survival"));
        if (!isSingleplayer()) {
            int count = (int) wave(6, 3, 300);
            for (int i = 0; i < count; i++) {
                String name = NAMES[i];
                players.add(new PlayerListEntry(name, UUID.nameUUIDFromBytes(name.getBytes()), 20 + (name.hashCode() & 0xff), i == 0 ? "creative" : "survival"));
            }
        }

        return players;
    }

    @Override
    public String getDimension() {
        return seconds() % 600 > 450 ? "minecraft:the_nether" : "minecraft:overworld";
    }

    @Override
    public Long getGameTime() {
        return (long) (seconds() * 20);
    }

    @Override
    public Long getDayTime() {
        return (long) (seconds() * 20) % 24000;
    }

    @Override
    public Boolean isRaining() {
        return seconds() % 400 > 300;
    }

    @Override
    public Boolean isThundering() {
        return seconds() % 400 > 370;
    }

    @Override
    public String getDifficulty() {
        return "normal";
    }

    @Override
    public Integer getLoadedChunks() {
        return (int) wave(441, 40, 120);
    }

    @Override
    public Integer getEntityCount() {
        return (int) wave(180, 60, 75);
    }

    @Override
    public String getPlayerName() {
        return playerName;
    }

    @Override
    public PlayerPosition getPlayerPosition() {
        x += random.nextGaussian();
        z += random.nextGaussian() + 0.3;
        return new PlayerPosition(x, wave(70, 5, 50), z, (float) (seconds() * 10 % 360) - 180, 0);
    }

    @Override
    public Float getHealth() {
        return (float) Math.max(0, Math.min(20, wave(15, 7, 70)));
    }

    @Override
    public Integer getFood() {
        return (int) Math.max(0, Math.min(20, wave(16, 4, 200)));
    }

    @Override
    public Float getSaturation() {
        return (float) Math.max(0, wave(3, 3, 100));
    }

    @Override
    public Integer getXpLevel() {
        return (int) (seconds() / 30);
    }

    @Override
    public String getGameMode() {
        return "survival";
    }

    @Override
    public Boolean isDead() {
        return getHealth() <= 0.5f;
    }

}
