package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import io.github.headlesshq.hmcprometheus.PrometheusConfig;
import io.github.headlesshq.hmcprometheus.logs.LokiPusher;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.screenshot.Screenshots;
import picocli.CommandLine;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Shows the status of the exporter, the subcommands manage it.
 */
@CommandLine.Command(
    name = "prometheus",
    description = "Shows the status of the HMC-Prometheus exporter.",
    subcommands = {
        PrometheusStartCommand.class,
        PrometheusStopCommand.class,
        PrometheusReloadCommand.class,
        PrometheusConfigCommand.class,
        PrometheusSetCommand.class
    })
public class PrometheusCommand extends AbstractMinecraftCommand {
    public static final String DASHBOARD_PATH = "/d/hmc-client/minecraft-client?var-client=";

    public PrometheusCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        HmcPrometheus prometheus = HmcPrometheus.getInstance();
        PrometheusConfig config = prometheus.getConfig();
        StringBuilder sb = new StringBuilder();
        sb.append(HmcPrometheus.NAME).append(' ').append(HmcPrometheus.getVersion()).append(" (").append(prometheus.getLoader()).append(")\n");
        if (config == null) {
            sb.append("HMC-Specifics has not initialized HMC-Prometheus yet.");
            ctx.log(sb.toString());
            return;
        }

        sb.append("Status:    ").append(prometheus.isRunning() ? "running" : "stopped").append('\n');
        if (prometheus.isRunning()) {
            sb.append("Client:    ").append(prometheus.getClient()).append('\n');
            sb.append("Metrics:   ").append(prometheus.getUrl()).append('\n');
            if (prometheus.getDiscoveryFile() != null) {
                sb.append("Discovery: ").append(prometheus.getDiscoveryFile().getAbsolutePath()).append('\n');
            }

            if (prometheus.isScreenshots()) {
                sb.append("Live view: ").append(prometheus.getBaseUrl()).append("/live")
                  .append(Screenshots.isAvailable() ? "" : " (unavailable, the game does not render)").append('\n');
            }

            LokiPusher loki = prometheus.getLokiPusher();
            if (loki != null) {
                sb.append("Loki:      ").append(loki.getUrl()).append(", ").append(loki.getSent()).append(" lines sent, ")
                  .append(loki.getQueued()).append(" queued, ").append(loki.getDropped()).append(" dropped");
                if (!loki.isLastPushSucceeded()) {
                    sb.append(", failing: ").append(loki.getLastError());
                }

                sb.append('\n');
            }

            String grafana = config.get(PrometheusConfig.GRAFANA_URL);
            if (!grafana.isEmpty()) {
                sb.append("Dashboard: ").append(trimSlash(grafana)).append(DASHBOARD_PATH).append(encode(prometheus.getClient())).append('\n');
            }
        }

        sb.append("Config:    ").append(config.getFile().getAbsolutePath());
        ctx.log(sb.toString());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

}
