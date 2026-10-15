package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.CommandException;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import picocli.CommandLine;

@CommandLine.Command(name = "reload", description = "Reads the config file again and restarts the exporter.")
public class PrometheusReloadCommand extends AbstractMinecraftCommand {
    public PrometheusReloadCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        HmcPrometheus prometheus = HmcPrometheus.getInstance();
        try {
            prometheus.reload();
        } catch (Exception e) {
            throw new CommandException("Failed to reload: " + e.getMessage(), e);
        }

        ctx.log(prometheus.isRunning() ? "Reloaded, serving metrics on " + prometheus.getUrl() : "Reloaded, the exporter is disabled.");
    }

}
