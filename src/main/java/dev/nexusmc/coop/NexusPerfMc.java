package dev.nexusmc.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class NexusPerfMc {

    private static int lastChunks = -1;
    private static boolean broken;
    private static boolean poseBroken;
    private static long lastPose;
    private static Object nameLevel;
    private static String nameCache = "";

    private NexusPerfMc() {}

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
                nameLevel = null;
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
        sb.append("{\"name\":\"").append(esc(worldName(mc, level))).append('"');
        sb.append(",\"x\":").append(r2(p.getX()));
        sb.append(",\"y\":").append(r2(p.getY()));
        sb.append(",\"z\":").append(r2(p.getZ()));
        sb.append(",\"yaw\":").append(r2(p.getYRot()));
        sb.append(",\"pitch\":").append(r2(p.getXRot()));
        sb.append(",\"dim\":\"").append(esc(level.dimension().location().toString())).append('"');
        String biome = level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse("");
        if (!biome.isEmpty()) sb.append(",\"biome\":\"").append(esc(biome)).append('"');
        long dayTime = level.getDayTime();
        sb.append(",\"dayTime\":").append(dayTime);
        sb.append(",\"days\":").append(dayTime / 24000L);
        sb.append(",\"light\":{\"block\":").append(level.getBrightness(LightLayer.BLOCK, pos));
        sb.append(",\"sky\":").append(level.getBrightness(LightLayer.SKY, pos)).append('}');
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

    private static String worldName(Minecraft mc, ClientLevel level) {
        if (nameLevel == level) return nameCache;
        nameLevel = level;
        String n = "";
        if (mc.getSingleplayerServer() != null) n = mc.getSingleplayerServer().getWorldData().getLevelName();
        else if (mc.getCurrentServer() != null) n = mc.getCurrentServer().ip;
        nameCache = n == null ? "" : n.replace(':', '_');
        return nameCache;
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

    private static final class Zone {
        int dx, dz, e, b;
        final Map<String, int[]> types = new HashMap<String, int[]>();

        void add(String name) {
            int[] n = types.get(name);
            if (n == null) types.put(name, n = new int[1]);
            n[0]++;
        }

        String top() {
            String best = "";
            int max = 0;
            for (Map.Entry<String, int[]> en : types.entrySet()) {
                if (en.getValue()[0] > max) {
                    max = en.getValue()[0];
                    best = en.getKey();
                }
            }
            return max > 0 ? best.replaceAll("[^A-Za-z0-9_.:-]", "") + " x" + max : "";
        }
    }

    private static String shortName(String id) {
        int i = id.lastIndexOf('.');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    public static void sample(Minecraft mc) {
        if (broken) return;
        try {
            ClientLevel level = mc.level;
            if (level == null || mc.player == null) {
                NexusPerf.clearWorld();
                lastChunks = -1;
                return;
            }
            boolean radar = NexusPerf.radarWanted();
            int rd = mc.options.getEffectiveRenderDistance();
            int pcx = mc.player.getBlockX() >> 4;
            int pcz = mc.player.getBlockZ() >> 4;
            Map<Long, Zone> zones = radar ? new HashMap<Long, Zone>() : null;

            int entities = 0;
            for (Entity e : level.entitiesForRendering()) {
                entities++;
                if (radar) {
                    int dx = (e.getBlockX() >> 4) - pcx;
                    int dz = (e.getBlockZ() >> 4) - pcz;
                    if (Math.abs(dx) > 32 || Math.abs(dz) > 32) continue;
                    Zone z = zone(zones, dx, dz);
                    z.e++;
                    z.add(shortName(e.getType().getDescriptionId()));
                }
            }

            int loaded = 0;
            int blockEntities = 0;
            int r = Math.min(Math.max(rd, 2), 32);
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    LevelChunk ch = level.getChunkSource().getChunkNow(pcx + dx, pcz + dz);
                    if (ch == null) continue;
                    loaded++;
                    Map<?, BlockEntity> bes = ch.getBlockEntities();
                    int n = bes.size();
                    blockEntities += n;
                    if (radar && n > 0) {
                        Zone z = zone(zones, dx, dz);
                        z.b += n;
                        for (BlockEntity be : bes.values()) {
                            z.add(shortName(be.getBlockState().getBlock().getDescriptionId()));
                        }
                    }
                }
            }

            int fresh = lastChunks >= 0 && loaded > lastChunks ? loaded - lastChunks : 0;
            lastChunks = loaded;
            NexusPerf.setWorld(loaded, entities, blockEntities, rd, fresh, radar ? zonesJson(zones) : null);
        } catch (Throwable t) {
            broken = true;
            NexusPerf.clearWorld();
            java.util.logging.Logger.getLogger("NexusPerf").log(java.util.logging.Level.WARNING, "World metrics are unavailable", t);
        }
    }

    private static Zone zone(Map<Long, Zone> zones, int dx, int dz) {
        long key = ((long) dx << 32) ^ (dz & 0xffffffffL);
        Zone z = zones.get(key);
        if (z == null) {
            z = new Zone();
            z.dx = dx;
            z.dz = dz;
            zones.put(key, z);
        }
        return z;
    }

    private static String zonesJson(Map<Long, Zone> zones) {
        List<Zone> all = new ArrayList<Zone>(zones.values());
        Collections.sort(all, new Comparator<Zone>() {
            @Override
            public int compare(Zone a, Zone b) {
                return Integer.compare(b.e + b.b * 2, a.e + a.b * 2);
            }
        });
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(60, all.size());
        for (int i = 0; i < n; i++) {
            Zone z = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"dx\":").append(z.dx).append(",\"dz\":").append(z.dz).append(",\"e\":").append(z.e).append(",\"b\":").append(z.b).append(",\"top\":\"").append(z.top()).append("\"}");
        }
        return sb.append(']').toString();
    }

    // ---------- Живой аватар в профиле лаунчера ----------
    // Передаём не кости, а состояние (поза, скорость, счётчики ударов), анимацию играет лаунчер.
    // Счётчики swing и hurt растут на каждый новый взмах и удар, поэтому задержка передачи не страшна.
    private static boolean avBroken;
    private static boolean avSwingPrev;
    private static int avSwings;
    private static boolean avHurtPrev;
    private static int avHurts;
    private static long avLast;

    public static void avatar(Minecraft mc) {
        if (!NexusPerf.ENABLED || avBroken || !NexusPerf.avatarWanted()) return;
        try {
            LocalPlayer p = mc.player;
            if (mc.level == null || p == null) {
                NexusPerf.setAvatar(null);
                return;
            }
            boolean sw = p.swinging;
            if (sw && !avSwingPrev) avSwings++;
            avSwingPrev = sw;
            boolean hu = p.hurtTime > 0;
            if (hu && !avHurtPrev) avHurts++;
            avHurtPrev = hu;
            long now = System.nanoTime();
            if (now - avLast < 90_000_000L) return;
            avLast = now;
            NexusPerf.setAvatar(avatarJson(p));
        } catch (Throwable t) {
            avBroken = true;
            NexusPerf.setAvatar(null);
            java.util.logging.Logger.getLogger("NexusPerf").log(java.util.logging.Level.WARNING, "Avatar data is unavailable", t);
        }
    }

    private static String avatarJson(LocalPlayer p) {
        double dx = p.getX() - p.xo;
        double dz = p.getZ() - p.zo;
        double speed = Math.sqrt(dx * dx + dz * dz);
        double head = ((p.getYHeadRot() - p.yBodyRot) % 360.0 + 540.0) % 360.0 - 180.0;
        StringBuilder sb = new StringBuilder(448);
        sb.append("{\"pose\":\"").append(esc(String.valueOf(p.getPose()))).append('"');
        sb.append(",\"speed\":").append(Math.round(speed * 1000.0) / 1000.0);
        sb.append(",\"sprint\":").append(p.isSprinting());
        sb.append(",\"head\":").append(r2(head));
        sb.append(",\"pitch\":").append(r2(p.getXRot()));
        sb.append(",\"swing\":").append(avSwings);
        sb.append(",\"hurt\":").append(avHurts);
        sb.append(",\"lefty\":").append(String.valueOf(p.getMainArm()).toLowerCase().contains("left"));
        sb.append(",\"ride\":").append(p.getVehicle() != null);
        if (p.isUsingItem()) {
            String anim = "NONE";
            try {
                anim = String.valueOf(p.getUseItem().getUseAnimation());
            } catch (Throwable ignored) {
                // тип действия не прочитали: аватар просто поднимет руку
            }
            sb.append(",\"use\":{\"anim\":\"").append(esc(anim)).append('"');
            sb.append(",\"off\":").append(String.valueOf(p.getUsedItemHand()).contains("OFF"));
            sb.append(",\"left\":").append(p.getUseItemRemainingTicks()).append('}');
        }
        avItem(sb, "main", p.getMainHandItem());
        avItem(sb, "off", p.getOffhandItem());
        sb.append(",\"armor\":{");
        avArmor(sb, "head", p.getItemBySlot(EquipmentSlot.HEAD), false);
        avArmor(sb, "chest", p.getItemBySlot(EquipmentSlot.CHEST), true);
        avArmor(sb, "legs", p.getItemBySlot(EquipmentSlot.LEGS), true);
        avArmor(sb, "feet", p.getItemBySlot(EquipmentSlot.FEET), true);
        return sb.append("}}").toString();
    }

    private static void avItem(StringBuilder sb, String key, ItemStack s) {
        String id = avId(s);
        if (id != null) sb.append(",\"").append(key).append("\":\"").append(esc(id)).append('"');
    }

    private static void avArmor(StringBuilder sb, String key, ItemStack s, boolean comma) {
        if (comma) sb.append(',');
        String id = avId(s);
        sb.append('"').append(key).append("\":");
        if (id == null) sb.append("null");
        else sb.append('"').append(esc(id)).append('"');
    }

    /** "item.minecraft.iron_helmet" -> "minecraft:iron_helmet" (getDescriptionId не зависит от переименований реестров) */
    private static String avId(ItemStack s) {
        if (s == null || s.isEmpty()) return null;
        String d = s.getItem().getDescriptionId();
        int i = d.indexOf('.');
        if (i >= 0) d = d.substring(i + 1);
        int j = d.indexOf('.');
        return j >= 0 ? d.substring(0, j) + ":" + d.substring(j + 1) : d;
    }
}
