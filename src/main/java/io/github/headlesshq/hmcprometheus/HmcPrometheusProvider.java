package io.github.headlesshq.hmcprometheus;

import me.earth.headlessmc.mc.Minecraft;
import me.earth.headlessmc.mc.metrics.MetricsProvider;
import me.earth.headlessmc.mc.metrics.MetricsService;

/**
 * Called by HMC-Specifics when it initializes, via {@link java.util.ServiceLoader}.
 * HMC-Prometheus does not register metrics of its own, it uses this hook to get the {@link Minecraft} instance.
 */
public class HmcPrometheusProvider implements MetricsProvider {
    @Override
    public void register(MetricsService service, Minecraft mc) {
        HmcPrometheus.getInstance().init(mc);
    }

}
