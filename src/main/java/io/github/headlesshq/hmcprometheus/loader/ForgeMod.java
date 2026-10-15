package io.github.headlesshq.hmcprometheus.loader;

import io.github.headlesshq.hmcprometheus.HmcPrometheus;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge 1.13+ reads {@code value} and the dependencies from the mods.toml,
 * Forge 1.8.9 - 1.12.2 reads {@code modid}, the version comes from the mcmod.info.
 * Before 1.13 HMC-Specifics is loaded as a tweaker, not as a mod, so it can not be declared as a dependency.
 */
@Mod(value = HmcPrometheus.ID, modid = HmcPrometheus.ID, name = HmcPrometheus.NAME)
public class ForgeMod {
    public ForgeMod() {
        HmcPrometheus.onModConstructed("Forge");
    }

}
