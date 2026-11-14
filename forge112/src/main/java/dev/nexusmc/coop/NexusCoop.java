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

import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;

/**
 * Помощник совместной игры для Minecraft 1.12 - 1.12.2 (Forge). Включается только если лаунчер NexusMC
 * запустил игру с параметрами:
 *   -Dnexus.coop=1  -Dnexus.coop.port=<порт>  -Dnexus.coop.file=<путь к nexus-session.json>
 * Без них ничего не делает.
 *
 * В 1.12.2 у shareToLAN нет параметра «порт»: игра сама выбирает случайный порт и слушает все сетевые интерфейсы.
 * Поэтому на время вызова мы подменяем NetworkSystem сервера на свой, который открывает слушающий сокет
 * на нужном порту и только на 127.0.0.1. Всё остальное (флаг «мир открыт», чтобы игра не вставала на паузу
 * вместе с меню хозяина, игровой режим, права) делает сама игра.
 */
public final class NexusCoop {
    private static final Logger LOG = Logger.getLogger("NexusCoop");

    public static final boolean ENABLED = "1".equals(System.getProperty("nexus.coop"));
    private static final int PORT = Integer.getInteger("nexus.coop.port", 0);
    private static final String FILE = System.getProperty("nexus.coop.file", "");

    /** Сколько тиков ждём после появления мира, прежде чем открывать его для сети. */
    private static final int WARMUP_TICKS = 60;

    private static int ticks;
    private static int lastPlayers = -1;
    private static boolean published;
    private static boolean failed;
    private static volatile boolean redirected;

    private NexusCoop() {}

    /** Вызывается каждый тик клиента, пока идёт одиночный мир. */
    public static void tick(IntegratedServer server) {
        if (!ENABLED || PORT <= 0 || FILE.isEmpty() || failed) return;
        ticks++;
        if (ticks == 1) LOG.info("NexusCoop is active, waiting for the world to load (port " + PORT + ")");

        if (!published) {
            if (ticks < WARMUP_TICKS || !server.isServerRunning()) return;
            // shareToLAN обращается к игроку хозяина, поэтому ждём, пока он войдёт в мир
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.player == null || mc.world == null) return;
            if (server.getPublic()) { // игрок сам открыл мир для сети: не вмешиваемся
                failed = true;
                return;
            }
            try {
                // Без проверки Mojang пускаем и лицензию, и игроков без неё.
                // Безопасно: порт слушает только 127.0.0.1 (см. publish),
                // а до него доходят лишь друзья, которых пустил лаунчер.
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

    private static int playerCount(IntegratedServer server) {
        try {
            return Math.max(1, server.getCurrentPlayerCount());
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Открывает мир через игру, но слушающий сокет создаётся на нашем порту и только на локальном адресе. */
    private static boolean publish(IntegratedServer server) throws Exception {
        // поле с сетевой частью сервера ищем по типу: так не зависим от рабочих имён полей
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
        // метод «слушать на адресе и порту» ищем по сигнатуре (InetAddress, int): его имя зависит от маппингов
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
        // Игра просит случайный порт на всех адресах, а нужен наш порт и 127.0.0.1. Сокет создаёт настоящая сетевая часть
        // сервера, чтобы соединения обрабатывались как обычно. Метод объявлен под обоими возможными именами
        // (без @Override): подходящее имя переопределит настоящий метод, второе просто не используется.
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
            // игровой режим null = как в мире, права для всех = нет (как раньше «разрешить читы: выкл»)
            boolean ok = server.shareToLAN(null, false) != null;
            if (ok && !redirected) {
                // подмена не сработала: мир открылся бы на случайном порту во всей сети - так нельзя, закрываем
                server.setOnlineMode(true); // хотя бы вернуть проверку Mojang для случайного порта
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
