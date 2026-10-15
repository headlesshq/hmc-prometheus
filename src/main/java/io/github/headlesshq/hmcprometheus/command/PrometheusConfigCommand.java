package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import io.github.headlesshq.hmcprometheus.PrometheusConfig;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.CommandException;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import me.earth.headlessmc.mc.util.ExtendedTable;
import picocli.CommandLine;

@CommandLine.Command(name = "config", description = "Lists the options of the exporter and their values.")
public class PrometheusConfigCommand extends AbstractMinecraftCommand {
    public PrometheusConfigCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        PrometheusConfig config = HmcPrometheus.getInstance().getConfig();
        if (config == null) {
            throw new CommandException("HMC-Specifics has not initialized HMC-Prometheus yet.");
        }

        ctx.log(new ExtendedTable<PrometheusConfig.Option>()
                    .withColumn("option", PrometheusConfig.Option::getKey)
                    .withColumn("value", o -> config.get(o) + (config.isOverridden(o) ? " (system property)" : ""))
                    .withColumn("description", PrometheusConfig.Option::getDescription)
                    .addAll(PrometheusConfig.getOptions())
                    .build());
    }

}
