package io.github.headlesshq.hmcprometheus.loader;

import cpw.mods.fml.common.Mod;
import io.github.headlesshq.hmcprometheus.HmcPrometheus;

/**
 * For Forge 1.7.10. The version comes from the mcmod.info.
 * HMC-Specifics is loaded as a tweaker, not as a mod, so it can not be declared as a dependency.
 */
@Mod(modid = HmcPrometheus.ID, name = HmcPrometheus.NAME)
public class LegacyForgeMod {
    public LegacyForgeMod() {
        HmcPrometheus.onModConstructed("Forge 1.7.10");
    }

}
