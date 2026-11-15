package dev.nexusmc.coop;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod("nexus_coop")
public class NexusCoopMod {
    public NexusCoopMod() {
        if (FMLEnvironment.dist == Dist.CLIENT && NexusCoop.ENABLED) {
            MinecraftForge.EVENT_BUS.register(new ClientHandler());
        }
    }
}
