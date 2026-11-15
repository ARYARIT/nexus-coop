package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

@Mod(
    modid = "nexus_coop",
    name = "NexusMC Coop Helper",
    version = "1.0.0",
    clientSideOnly = true,
    acceptableRemoteVersions = "*",
    acceptedMinecraftVersions = "[1.12,1.12.2]"
)
public class NexusCoopMod {
    private IntegratedServer last;
    private volatile boolean stopping;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (NexusCoop.ENABLED) MinecraftForge.EVENT_BUS.register(this);
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
