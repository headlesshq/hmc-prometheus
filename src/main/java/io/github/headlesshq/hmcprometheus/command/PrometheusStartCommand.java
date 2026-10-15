package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.CommandException;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import picocli.CommandLine;

@CommandLine.Command(name = "start", description = "Starts the exporter, or restarts it if it is running.")
public class PrometheusStartCommand extends AbstractMinecraftCommand {
    public PrometheusStartCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        HmcPrometheus prometheus = HmcPrometheus.getInstance();
        try {
            prometheus.start();
        } catch (Exception e) {
            throw new CommandException("Failed to start the exporter: " + e.getMessage(), e);
        }

        ctx.log("Serving metrics of client '" + prometheus.getClient() + "' on " + prometheus.getUrl());
    }

}
