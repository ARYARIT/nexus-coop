package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Мод только для клиента. acceptableRemoteVersions = "*": друг без этого мода (его лаунчер не кладёт мод,
 * когда игрок заходит в чужой мир) всё равно может подключиться к миру хозяина.
 */
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

    /**
     * Тики клиента идут и тогда, когда игрок в меню паузы (тики сервера в это время стоят),
     * поэтому мир откроется, даже если хозяин сразу после входа нажал Esc.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server != last) { // другой мир или выход в меню
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
