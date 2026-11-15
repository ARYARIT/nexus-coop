package dev.nexusmc.coop;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;

public final class NexusCoop {

    private static final Logger LOG = Logger.getLogger("NexusCoop");

    public static final boolean ENABLED = "1".equals(System.getProperty("nexus.coop"));
    private static final int PORT = Integer.getInteger("nexus.coop.port", 0);
    private static final String FILE = System.getProperty("nexus.coop.file", "");

    private static final int WARMUP_TICKS = 60;

    private static int ticks;
    private static int lastPlayers = -1;
    private static boolean published;
    private static boolean failed;

    private NexusCoop() {}

    public static void tick(IntegratedServer server) {
        if (!ENABLED || PORT <= 0 || FILE.isEmpty() || failed) return;
        ticks++;
        if (ticks == 1) LOG.info("NexusCoop is active, waiting for the world to load (port " + PORT + ")");

        if (!published) {
            if (ticks < WARMUP_TICKS || !server.isServerRunning()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.world == null) return;
            if (server.getPublic()) {
                failed = true;
                return;
            }
            try {

                server.setOnlineMode(false);
                published = publish(server);
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Failed to open the world for friends", t);
            }
            if (published) {
                LOG.info("World opened for friends on 127.0.0.1:" + PORT);
                write(server.getCurrentPlayerCount());
            } else {
                failed = true;
            }
            return;
        }

        if (ticks % 40 == 0) {
            int n = server.getCurrentPlayerCount();
            if (n != lastPlayers) write(n);
        }
    }

    private static boolean publish(IntegratedServer server) throws Exception {
        Field field = null;
        for (Field f : MinecraftServer.class.getDeclaredFields()) {
            if (f.getType() == NetworkSystem.class) {
                field = f;
                break;
            }
        }
        if (field == null) throw new NoSuchFieldException("MinecraftServer.networkSystem");
        field.setAccessible(true);

        final NetworkSystem real = (NetworkSystem) field.get(server);
        NetworkSystem loopbackOnly = new NetworkSystem(server) {
            @Override
            public void addEndpoint(InetAddress address, int port) {
                try {
                    real.addEndpoint(InetAddress.getLoopbackAddress(), PORT);
                } catch (Exception e) {

                    throw NexusCoop.<RuntimeException>sneaky(e);
                }
            }
        };

        field.set(server, loopbackOnly);
        try {
            return server.shareToLAN(null, false, PORT);
        } finally {
            field.set(server, real);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }

    public static void stop() {
        if (!ENABLED) return;
        published = false;
        failed = false;
        ticks = 0;
        lastPlayers = -1;
        if (!FILE.isEmpty()) {
            try {
                Files.deleteIfExists(Paths.get(FILE));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to delete the session file", e);
            }
        }
    }

    private static void write(int players) {
        lastPlayers = players;
        String json = "{\"v\":1,\"state\":\"open\",\"port\":" + PORT + ",\"players\":" + players + "}";
        Path file = Paths.get(FILE);
        Path tmp = Paths.get(FILE + ".tmp");
        try {
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to write the session file", e);
        }
    }
}
