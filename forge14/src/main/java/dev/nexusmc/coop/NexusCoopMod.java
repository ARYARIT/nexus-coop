package dev.nexusmc.coop;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Вся работа делается в ClientHandler. Здесь нет ссылок на клиентские классы игры, чтобы мод не падал на выделенном сервере. */
@Mod("nexus_coop")
public class NexusCoopMod {
    public NexusCoopMod() {
        if (FMLEnvironment.dist == Dist.CLIENT && NexusCoop.ENABLED) {
            MinecraftForge.EVENT_BUS.register(new ClientHandler());
        }
    }
}
