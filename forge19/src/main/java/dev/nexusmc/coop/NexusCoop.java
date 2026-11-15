package dev.nexusmc.coop;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static volatile boolean redirected;

    private NexusCoop() {}

    public static void tick(IntegratedServer server) {
        if (!ENABLED || PORT <= 0 || FILE.isEmpty() || failed) return;
        ticks++;
        if (ticks == 1) LOG.info("NexusCoop is active, waiting for the world to load (port " + PORT + ")");

        if (!published) {
            if (ticks < WARMUP_TICKS || !server.isServerRunning()) return;

            if (server.getCurrentPlayerCount() < 1) return;
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
                write(playerCount(server));
            } else {
                failed = true;
            }
            return;
        }

        if (ticks % 40 == 0) {
            int n = playerCount(server);
            if (n != lastPlayers) write(n);
        }
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

    private static int playerCount(IntegratedServer server) {
        try {
            return Math.max(1, server.getCurrentPlayerCount());
        } catch (Throwable t) {
            return 1;
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

        Method open = null;
        for (Method m : NetworkSystem.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == InetAddress.class && p[1] == int.class) {
                open = m;
                break;
            }
        }
        if (open == null) throw new NoSuchMethodException("NetworkSystem.addLanEndpoint(InetAddress, int)");
        open.setAccessible(true);
        final Method openReal = open;

        NetworkSystem loopbackOnly = new NetworkSystem(server) {
            public void addLanEndpoint(InetAddress address, int port) throws IOException {
                redirect(real, openReal);
            }

            public void addEndpoint(InetAddress address, int port) throws IOException {
                redirect(real, openReal);
            }
        };

        field.set(server, loopbackOnly);
        redirected = false;
        try {

            boolean ok = server.shareToLAN(null, false) != null;
            if (ok && !redirected) {

                server.setOnlineMode(true);
                throw new IllegalStateException("loopback override was not applied, refusing to keep the world open");
            }
            return ok;
        } finally {
            field.set(server, real);
        }
    }

    private static void redirect(NetworkSystem real, Method open) throws IOException {
        try {
            redirected = true;
            open.invoke(real, InetAddress.getLoopbackAddress(), PORT);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof IOException) throw (IOException) c;
            throw new IOException(c);
        } catch (IllegalAccessException e) {
            throw new IOException(e);
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
