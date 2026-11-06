package dev.nexusmc.coop;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

/** Вся работа делается миксинами, этот класс нужен только чтобы NeoForge загрузил мод (и только на клиенте). */
@Mod(value = "nexus_coop", dist = Dist.CLIENT)
public class NexusCoopMod {
    public NexusCoopMod() {}
}
