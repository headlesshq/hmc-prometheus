package io.github.headlesshq.hmcprometheus.command;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import io.github.headlesshq.hmcprometheus.PrometheusConfig;
import me.earth.headlessmc.mc.commands.AbstractMinecraftCommand;
import me.earth.headlessmc.mc.commands.CommandException;
import me.earth.headlessmc.mc.commands.MinecraftContext;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@CommandLine.Command(name = "set", description = "Sets an option in the config file and reloads the exporter.")
public class PrometheusSetCommand extends AbstractMinecraftCommand {
    @CommandLine.Parameters(index = "0", paramLabel = "<option>", completionCandidates = OptionNames.class, description = "The option to set.")
    private String option;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "<value>", description = "The new value, empty if not specified.")
    private String value = "";

    public PrometheusSetCommand(MinecraftContext ctx) {
        super(ctx);
    }

    @Override
    protected void execute() {
        HmcPrometheus prometheus = HmcPrometheus.getInstance();
        PrometheusConfig config = prometheus.getConfig();
        if (config == null) {
            throw new CommandException("HMC-Specifics has not initialized HMC-Prometheus yet.");
        }

        try {
            PrometheusConfig.Option option = PrometheusConfig.getOption(this.option);
            config.set(option.getKey(), value);
            config.save();
            if (config.isOverridden(option)) {
                ctx.log("Warning: " + option.getKey() + " is overridden by a system property.");
            }

            prometheus.reload();
            ctx.log("Set " + option.getKey() + " to '" + value + "'"
                        + (prometheus.isRunning() ? ", serving metrics on " + prometheus.getUrl() : ", the exporter is disabled."));
        } catch (IllegalArgumentException e) {
            throw new CommandException(e.getMessage());
        } catch (Exception e) {
            throw new CommandException("Failed to set " + this.option + ": " + e.getMessage(), e);
        }
    }

    public static final class OptionNames implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            List<String> names = new ArrayList<>();
            for (PrometheusConfig.Option option : PrometheusConfig.getOptions()) {
                names.add(option.getKey());
            }

            return names.iterator();
        }
    }

}
