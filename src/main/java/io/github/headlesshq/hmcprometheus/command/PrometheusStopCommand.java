package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import picocli.CommandLine;

@CommandLine.Command(name = "stop", description = "Stops the exporter and removes this client from the Prometheus discovery.")
public class PrometheusStopCommand extends AbstractMinecraftCommand {
    public PrometheusStopCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        HmcPrometheus.getInstance().stop();
        ctx.log("Stopped the exporter.");
    }

}
