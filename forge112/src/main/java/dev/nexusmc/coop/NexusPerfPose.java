package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.biome.Biome;

/**
 * Игровые данные для раздела «Игровые данные» (Minecraft 1.12.2): координаты, измерение, биом, время, свет, предмет и цель.
 * Необязательный класс: NexusPerfHooks создаёт его через рефлексию и вызывает каждый кадр; если файл не собрался, CI его удаляет.
 */
public final class NexusPerfPose implements Runnable {

    private long last;
    private Object nameWorld;
    private String nameCache = "";

    @Override
    public void run() {
        if (!NexusPerf.ENABLED) return;
        long now = System.nanoTime();
        if (now - last < 200000000L) return;
        last = now;
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.world;
        EntityPlayerSP player = mc.player;
        if (world == null || player == null) {
            NexusPerf.setGame(null);
            nameWorld = null;
            return;
        }
        NexusPerf.setGame(json(mc, world, player));
    }

    private String json(Minecraft mc, WorldClient w, EntityPlayerSP p) {
        BlockPos pos = new BlockPos(p);
        StringBuilder sb = new StringBuilder(448);
        sb.append("{\"name\":\"").append(esc(worldName(mc, w))).append('"');
        sb.append(",\"x\":").append(r2(p.posX));
        sb.append(",\"y\":").append(r2(p.posY));
        sb.append(",\"z\":").append(r2(p.posZ));
        sb.append(",\"yaw\":").append(r2(p.rotationYaw));
        sb.append(",\"pitch\":").append(r2(p.rotationPitch));
        sb.append(",\"dim\":\"").append(esc(w.provider.getDimensionType().getName())).append('"');
        Biome biome = w.getBiome(pos);
        String bid = "";
        if (biome != null) {
            ResourceLocation id = Biome.REGISTRY.getNameForObject(biome);
            bid = id != null ? id.toString() : biome.getBiomeName();
        }
        if (bid != null && !bid.isEmpty()) sb.append(",\"biome\":\"").append(esc(bid)).append('"');
        long dayTime = w.getWorldTime();
        sb.append(",\"dayTime\":").append(dayTime);
        sb.append(",\"days\":").append(dayTime / 24000L);
        sb.append(",\"light\":{\"block\":").append(w.getLightFor(EnumSkyBlock.BLOCK, pos));
        sb.append(",\"sky\":").append(w.getLightFor(EnumSkyBlock.SKY, pos)).append('}');
        ItemStack stack = p.getHeldItemMainhand();
        if (!stack.isEmpty() && stack.isItemStackDamageable()) {
            int max = stack.getMaxDamage();
            sb.append(",\"item\":{\"name\":\"").append(esc(stack.getDisplayName()));
            sb.append("\",\"left\":").append(Math.max(0, max - stack.getItemDamage()));
            sb.append(",\"max\":").append(max).append('}');
        }
        RayTraceResult hit = mc.objectMouseOver;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            BlockPos b = hit.getBlockPos();
            sb.append(",\"target\":{\"x\":").append(b.getX());
            sb.append(",\"y\":").append(b.getY());
            sb.append(",\"z\":").append(b.getZ()).append('}');
        }
        return sb.append('}').toString();
    }

    private String worldName(Minecraft mc, WorldClient w) {
        if (nameWorld == w) return nameCache;
        nameWorld = w;
        String n = "";
        if (mc.isSingleplayer() && mc.getIntegratedServer() != null) n = mc.getIntegratedServer().getFolderName();
        else if (mc.getCurrentServerData() != null) n = mc.getCurrentServerData().serverIP;
        nameCache = n == null ? "" : n.replace(':', '_');
        return nameCache;
    }

    private static double r2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }
}
