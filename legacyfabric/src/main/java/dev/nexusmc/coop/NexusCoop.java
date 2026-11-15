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

import net.minecraft.server.ServerNetworkIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
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
    private static volatile long lastTick;
    private static volatile IntegratedServer current;
    private static Thread watchdog;
    private static volatile boolean redirected;

    private NexusCoop() {}

    public static void tick(IntegratedServer server) {
        if (!ENABLED || PORT <= 0 || FILE.isEmpty()) return;
        lastTick = System.nanoTime();
        if (server != current) {
            if (current != null) stop();
            current = server;
        }
        if (failed) return;
        ticks++;
        if (ticks == 1) LOG.info("NexusCoop is active, waiting for the world to load (port " + PORT + ")");

        if (!published) {

            if (ticks < WARMUP_TICKS || playerCount(server, 0) < 1) return;
            try {

                server.setOnlineMode(false);
                published = publish(server);
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Failed to open the world for friends", t);
            }
            if (published) {
                LOG.info("World opened for friends on 127.0.0.1:" + PORT);
                startWatchdog();
                write(Math.max(1, playerCount(server, 1)));
            } else {
                failed = true;
            }
            return;
        }

        if (ticks % 40 == 0) {
            int n = Math.max(1, playerCount(server, 1));
            if (n != lastPlayers) write(n);
        }
    }

    public static void stop() {
        if (!ENABLED) return;
        current = null;
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

    private static boolean publish(IntegratedServer server) throws Exception {

        Field field = null;
        for (Field f : MinecraftServer.class.getDeclaredFields()) {
            if (f.getType() == ServerNetworkIo.class) {
                field = f;
                break;
            }
        }
        if (field == null) throw new NoSuchFieldException("MinecraftServer.networkSystem");
        field.setAccessible(true);

        final ServerNetworkIo real = (ServerNetworkIo) field.get(server);

        Method open = null;
        for (Method m : ServerNetworkIo.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 && p[0] == InetAddress.class && p[1] == int.class) {
                open = m;
                break;
            }
        }
        if (open == null) throw new NoSuchMethodException("ServerNetworkIo.addLanEndpoint(InetAddress, int)");
        open.setAccessible(true);
        final Method openReal = open;

        ServerNetworkIo loopbackOnly = new ServerNetworkIo(server) {
            public void addLanEndpoint(InetAddress address, int port) throws IOException {
                redirect(real, openReal);
            }

            public void bind(InetAddress address, int port) throws IOException {
                redirect(real, openReal);
            }

            public void addEndpoint(InetAddress address, int port) throws IOException {
                redirect(real, openReal);
            }
        };

        field.set(server, loopbackOnly);
        redirected = false;
        try {

            boolean ok = share(server);
            if (ok && !redirected) {

                server.setOnlineMode(true);
                throw new IllegalStateException("loopback override was not applied, refusing to keep the world open");
            }
            return ok;
        } finally {
            field.set(server, real);
        }
    }

    private static boolean share(IntegratedServer server) throws Exception {
        for (Method m : IntegratedServer.class.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getReturnType() == String.class && p.length == 2 && p[1] == boolean.class && !p[0].isPrimitive()) {
                m.setAccessible(true);
                try {
                    return m.invoke(server, null, false) != null;
                } catch (InvocationTargetException e) {
                    Throwable c = e.getCause();
                    throw c instanceof Exception ? (Exception) c : new RuntimeException(c);
                }
            }
        }
        throw new NoSuchMethodException("IntegratedServer.shareToLAN");
    }

    private static int playerCount(MinecraftServer server, int fallback) {
        try {
            for (Field f : MinecraftServer.class.getDeclaredFields()) {
                if (f.getType() != PlayerManager.class) continue;
                f.setAccessible(true);
                Object pm = f.get(server);
                if (pm == null) return fallback;
                for (Field g : PlayerManager.class.getDeclaredFields()) {
                    if (g.getType() == java.util.List.class) {
                        g.setAccessible(true);
                        return ((java.util.List<?>) g.get(pm)).size();
                    }
                }
            }
        } catch (Throwable t) {

        }
        return fallback;
    }

    private static void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (published && System.nanoTime() - lastTick > 8_000_000_000L) stop();
            }
        }, "NexusCoop-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void redirect(ServerNetworkIo real, Method open) throws IOException {
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
