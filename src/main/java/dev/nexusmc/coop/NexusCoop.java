package dev.nexusmc.coop;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.minecraft.client.server.IntegratedServer;

/**
 * Помощник совместной игры. Включается только если лаунчер NexusMC запустил игру с параметрами:
 *   -Dnexus.coop=1  -Dnexus.coop.port=<порт>  -Dnexus.coop.file=<путь к nexus-session.json>
 * Без них ничего не делает.
 */
public final class NexusCoop {
    // java.util.logging есть во всех версиях Java и Minecraft (slf4j в старых версиях игры нет)
    private static final Logger LOG = Logger.getLogger("NexusCoop");

    public static final boolean ENABLED = "1".equals(System.getProperty("nexus.coop"));
    private static final int PORT = Integer.getInteger("nexus.coop.port", 0);
    private static final String FILE = System.getProperty("nexus.coop.file", "");

    /** Сколько тиков сервера ждём после старта мира, прежде чем открывать его для сети. */
    private static final int WARMUP_TICKS = 60;

    private static int ticks;
    private static int lastPlayers = -1;
    private static boolean published;
    private static boolean failed;

    private NexusCoop() {}

    public static void tick(IntegratedServer server) {
        if (!ENABLED || PORT <= 0 || FILE.isEmpty() || failed) return;
        ticks++;

        if (!published) {
            if (ticks < WARMUP_TICKS || !server.isRunning()) return;
            if (server.isPublished()) { // игрок сам открыл мир для сети: не вмешиваемся
                failed = true;
                return;
            }
            try {
                // Без проверки Mojang пускаем и лицензию, и игроков без неё.
                // Безопасно: порт слушает только 127.0.0.1 (см. ServerConnectionListenerMixin),
                // а до него доходят лишь друзья, которых пустил лаунчер.
                server.setUsesAuthentication(false);
                published = server.publishServer(null, false, PORT);
            } catch (Throwable t) {
                LOG.log(Level.SEVERE, "Failed to open the world for friends", t);
            }
            if (published) {
                LOG.info("World opened for friends on 127.0.0.1:" + PORT);
                write(server.getPlayerCount());
            } else {
                failed = true;
            }
            return;
        }

        if (ticks % 40 == 0) {
            int n = server.getPlayerCount();
            if (n != lastPlayers) write(n);
        }
    }

    /** Мир закрыт (игрок вышел в меню или закрыл игру): чистим состояние, следующий мир откроется заново. */
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
