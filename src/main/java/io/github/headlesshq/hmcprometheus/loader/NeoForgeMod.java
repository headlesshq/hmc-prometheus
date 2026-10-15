package io.github.headlesshq.hmcprometheus.loader;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import net.neoforged.fml.common.Mod;

@Mod(HmcPrometheus.ID)
public class NeoForgeMod {
    public NeoForgeMod() {
        HmcPrometheus.onModConstructed("NeoForge");
    }

}
