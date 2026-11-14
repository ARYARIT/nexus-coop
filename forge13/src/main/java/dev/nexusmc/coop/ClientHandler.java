package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.server.FMLServerStoppingEvent;

public class ClientHandler {
    private IntegratedServer last;
    private volatile boolean stopping;

    /**
     * Тики клиента идут и тогда, когда игрок в меню паузы (тики сервера в это время стоят),
     * поэтому мир откроется, даже если хозяин сразу после входа нажал Esc.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        IntegratedServer server = Minecraft.getInstance().getIntegratedServer();
        if (server != last) { // другой мир или выход в меню
            if (last != null) NexusCoop.stop();
            last = server;
            stopping = false;
        }
        if (server == null || stopping) return;
        NexusCoop.tick(server);
    }

    @SubscribeEvent
    public void onServerStopping(FMLServerStoppingEvent event) {
        stopping = true;
        NexusCoop.stop();
    }
}
