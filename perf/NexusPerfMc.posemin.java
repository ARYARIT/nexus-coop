package dev.nexusmc.coop;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Запасной вариант: координаты, измерение, биом, предмет в руке и цель, без времени суток и света.
 * Миры, чанки и радар не считаются: sample() пустой. Названия измерения и биома берутся из ResourceKey.toString(),
 * поэтому код не зависит от переименований ResourceLocation / Identifier и location() / identifier() между версиями игры.
 */
public final class NexusPerfMc {

    private static boolean poseBroken;
    private static long lastPose;

    private NexusPerfMc() {}

    public static void sample(Minecraft mc) {
        // данные мира для этой версии не собираются
    }

    public static void pose(Minecraft mc) {
        if (!NexusPerf.ENABLED || poseBroken) return;
        long now = System.nanoTime();
        if (now - lastPose < 200_000_000L) return;
        lastPose = now;
        try {
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            if (level == null || player == null) {
                NexusPerf.setGame(null);
                return;
            }
            NexusPerf.setGame(worldJson(mc, level, player));
        } catch (Throwable t) {
            poseBroken = true;
            NexusPerf.setGame(null);
            java.util.logging.Logger.getLogger("NexusPerf").log(java.util.logging.Level.WARNING, "Game data is unavailable", t);
        }
    }

    private static String worldJson(Minecraft mc, ClientLevel level, LocalPlayer p) {
        BlockPos pos = p.blockPosition();
        StringBuilder sb = new StringBuilder(448);
        sb.append("{\"name\":\"").append(esc("")).append('"');
        sb.append(",\"x\":").append(r2(p.getX()));
        sb.append(",\"y\":").append(r2(p.getY()));
        sb.append(",\"z\":").append(r2(p.getZ()));
        sb.append(",\"yaw\":").append(r2(p.getYRot()));
        sb.append(",\"pitch\":").append(r2(p.getXRot()));
        sb.append(",\"dim\":\"").append(esc(keyName(level.dimension()))).append('"');
        String biome = level.getBiome(pos).unwrapKey().map(NexusPerfMc::keyName).orElse("");
        if (!biome.isEmpty()) sb.append(",\"biome\":\"").append(esc(biome)).append('"');
        ItemStack stack = p.getMainHandItem();
        if (!stack.isEmpty() && stack.isDamageableItem()) {
            int max = stack.getMaxDamage();
            sb.append(",\"item\":{\"name\":\"").append(esc(stack.getItem().getDescriptionId()));
            sb.append("\",\"left\":").append(Math.max(0, max - stack.getDamageValue()));
            sb.append(",\"max\":").append(max).append('}');
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos b = ((BlockHitResult) hit).getBlockPos();
            sb.append(",\"target\":{\"x\":").append(b.getX());
            sb.append(",\"y\":").append(b.getY());
            sb.append(",\"z\":").append(b.getZ()).append('}');
        }
        return sb.append('}').toString();
    }

    /** "ResourceKey[minecraft:dimension / minecraft:the_nether]" -> "minecraft:the_nether" */
    private static String keyName(Object key) {
        String s = String.valueOf(key);
        int i = s.lastIndexOf(" / ");
        int j = s.lastIndexOf(']');
        return i >= 0 && j > i ? s.substring(i + 3, j) : s;
    }

    private static double r2(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        return Math.round(v * 100.0) / 100.0;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(' ');
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Живой аватар для этой версии не собирается. */
    public static void avatar(Minecraft mc) {
    }
}
