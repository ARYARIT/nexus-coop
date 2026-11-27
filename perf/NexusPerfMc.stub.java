package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;

/**
 * Заглушка для старых версий Minecraft (до 1.18): API мира там другое, поэтому чанки, сущности и радар не собираются.
 * Кадры, GC, память, TPS и MSPT работают через NexusPerf без обращений к миру.
 */
public final class NexusPerfMc {

    private NexusPerfMc() {}

    public static void sample(Minecraft mc) {
        // нет данных мира для этой версии
    }

    public static void pose(Minecraft mc) {
    }

    /** Живой аватар для этой версии не собирается. */
    public static void avatar(Minecraft mc) {
    }
}
