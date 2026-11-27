package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Метрики производительности для старых версий Forge: кадры, тики встроенного сервера, GC и память.
 * Подключается из NexusCoopMod через рефлексию, поэтому при ошибке компиляции CI просто удаляет этот файл.
 * Класс NexusPerf кладётся в модуль на этапе сборки (общий код лежит в src/main/java).
 */
public final class NexusPerfHooks {

    private boolean hadServer;
    private Runnable pose;

    private NexusPerfHooks() {}

    public static void register() {
        if (!NexusPerf.ENABLED) return;
        NexusPerfHooks h = new NexusPerfHooks();
        try {
            // игровые данные есть не для всех версий: класс необязательный
            Object o = Class.forName("dev.nexusmc.coop.NexusPerfPose").newInstance();
            if (o instanceof Runnable) h.pose = (Runnable) o;
        } catch (Throwable ignored) {
            // нет игровых данных для этой версии
        }
        MinecraftForge.EVENT_BUS.register(h);
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        NexusPerf.frame();
        Runnable p = pose;
        if (p != null) {
            try {
                p.run();
            } catch (Throwable t) {
                pose = null;
                NexusPerf.setGame(null);
            }
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) NexusPerf.serverTickStart();
        else NexusPerf.serverTickEnd();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        boolean has = Minecraft.getInstance().getSingleplayerServer() != null;
        if (hadServer && !has) NexusPerf.serverStopped();
        hadServer = has;
    }
}
