package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

@Mod(
    modid = "nexus_coop",
    name = "NexusMC Coop Helper",
    version = "1.0.0",
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.7.10]"
)
public class NexusCoopMod {
    private IntegratedServer last;
    private volatile boolean stopping;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (NexusCoop.ENABLED) FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server != last) {
            if (last != null) NexusCoop.stop();
            last = server;
            stopping = false;
        }
        if (server == null || stopping) return;
        NexusCoop.tick(server);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        stopping = true;
        NexusCoop.stop();
    }
}
