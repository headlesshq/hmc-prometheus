package io.github.headlesshq.hmcprometheus.loader;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import net.fabricmc.api.ClientModInitializer;

public class FabricMod implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        HmcPrometheus.onModConstructed("Fabric");
    }

}
