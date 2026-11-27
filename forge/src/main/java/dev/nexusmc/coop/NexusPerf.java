package dev.nexusmc.coop;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Метрики производительности для раздела «Производительность» в оверлее NexusMC.
 * Не зависит от классов Minecraft: кадры и тики приходят из миксинов, данные мира из {@link NexusPerfMc}.
 * Раз в 500 мс отправляет JSON лаунчеру на 127.0.0.1 (протокол: PERF_HELPER.md).
 * Работает, только если лаунчер передал -Dnexus.perf.port и -Dnexus.perf.token.
 */
public final class NexusPerf {

    private static final Logger LOG = Logger.getLogger("NexusPerf");

    private static final int PORT = Integer.getInteger("nexus.perf.port", 0);
    private static final String TOKEN = System.getProperty("nexus.perf.token", "");
    public static final boolean ENABLED = PORT > 0 && !TOKEN.isEmpty();

    private static final int MAX_FRAMES = 400;
    private static final long SEND_EVERY_MS = 500;

    private static final Object LOCK = new Object();
    private static List<Float> frames = new ArrayList<Float>();
    private static long lastFrameNs;
    private static long fpsWindowStart;
    private static int fpsCount;
    private static volatile int fps = -1;
    private static volatile long renderThreadId = -1;

    // сервер (одиночный мир): интервалы между тиками и длительность тика
    private static final long[] tickStarts = new long[41];
    private static int tickN;
    private static long tickBeginNs;
    private static double msptAvg = -1;
    private static volatile double tps = -1;
    private static volatile double mspt = -1;

    // данные мира (заполняет NexusPerfMc в потоке рендера)
    private static volatile int chunks = -1;
    private static volatile int entities = -1;
    private static volatile int blockEntities = -1;
    private static volatile int renderDist = -1;
    private static volatile int chunkLoads;
    private static volatile String zonesJson = null;
    private static volatile boolean radar;
    // лаунчер показывает живой аватар: пока флаг включён, шлём чаще и добавляем поле avatar
    private static volatile boolean avatarOn;
    private static volatile String avatarJson = null;
    private static volatile String worldJson = null;

    private static Thread sender;
    private static volatile boolean stopped;

    private NexusPerf() {}

    /** Вызывается в начале каждого кадра из потока рендера. Возвращает true, если пора обновить данные мира (раз в секунду). */
    public static boolean frame() {
        if (!ENABLED || stopped) return false;
        long now = System.nanoTime();
        if (lastFrameNs != 0) {
            float ms = (now - lastFrameNs) / 1_000_000f;
            synchronized (LOCK) {
                if (frames.size() >= MAX_FRAMES) frames.remove(0);
                frames.add(ms);
            }
        }
        lastFrameNs = now;
        if (renderThreadId < 0) {
            renderThreadId = Thread.currentThread().getId();
            startSender();
        }
        fpsCount++;
        if (fpsWindowStart == 0) fpsWindowStart = now;
        long dt = now - fpsWindowStart;
        if (dt >= 1_000_000_000L) {
            fps = Math.round(fpsCount * 1_000_000_000f / dt);
            fpsCount = 0;
            fpsWindowStart = now;
            return true;
        }
        return false;
    }

    public static boolean radarWanted() {
        return radar;
    }

    public static boolean avatarWanted() {
        return avatarOn;
    }

    public static void setAvatar(String json) {
        avatarJson = json;
    }

    public static void setWorld(int chunksN, int entitiesN, int blockEntitiesN, int renderDistance, int newChunks, String zones) {
        chunks = chunksN;
        entities = entitiesN;
        blockEntities = blockEntitiesN;
        renderDist = renderDistance;
        if (newChunks > 0) chunkLoads += newChunks;
        zonesJson = zones;
    }

    public static void clearWorld() {
        chunks = entities = blockEntities = -1;
        zonesJson = null;
    }

    public static void setGame(String json) {
        worldJson = json;
    }

    public static void serverTickStart() {
        if (!ENABLED) return;
        tickBeginNs = System.nanoTime();
        if (tickN < tickStarts.length) {
            tickStarts[tickN++] = tickBeginNs;
        } else {
            System.arraycopy(tickStarts, 1, tickStarts, 0, tickStarts.length - 1);
            tickStarts[tickStarts.length - 1] = tickBeginNs;
        }
        if (tickN >= 2) {
            long span = tickStarts[tickN - 1] - tickStarts[0];
            if (span > 0) tps = Math.min(20.0, (tickN - 1) * 1_000_000_000.0 / span);
        }
    }

    public static void serverTickEnd() {
        if (!ENABLED || tickBeginNs == 0) return;
        double ms = (System.nanoTime() - tickBeginNs) / 1_000_000.0;
        msptAvg = msptAvg < 0 ? ms : msptAvg * 0.9 + ms * 0.1;
        mspt = msptAvg;
    }

    public static void serverStopped() {
        tickN = 0;
        tickBeginNs = 0;
        msptAvg = -1;
        tps = -1;
        mspt = -1;
    }

    private static synchronized void startSender() {
        if (sender != null) return;
        sender = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "NexusPerf-Sender");
        sender.setDaemon(true);
        sender.start();
        LOG.info("NexusPerf is active, sending metrics to the launcher on port " + PORT);
    }

    private static void loop() {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        boolean cpuOk = false;
        try {
            cpuOk = threads.isThreadCpuTimeSupported();
            if (cpuOk && !threads.isThreadCpuTimeEnabled()) threads.setThreadCpuTimeEnabled(true);
        } catch (Throwable ignored) {
            cpuOk = false;
        }
        List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
        long[] lastCount = new long[gcs.size()];
        long[] lastTime = new long[gcs.size()];
        for (int i = 0; i < gcs.size(); i++) {
            lastCount[i] = gcs.get(i).getCollectionCount();
            lastTime[i] = gcs.get(i).getCollectionTime();
        }
        long lastCpu = -1;
        long lastWall = System.nanoTime();
        int failures = 0;

        while (!stopped) {
            try {
                Thread.sleep(avatarOn ? 100 : SEND_EVERY_MS);
            } catch (InterruptedException e) {
                return;
            }

            List<Float> batch;
            synchronized (LOCK) {
                batch = frames;
                frames = new ArrayList<Float>();
            }

            StringBuilder gc = new StringBuilder();
            for (int i = 0; i < gcs.size(); i++) {
                long c = gcs.get(i).getCollectionCount();
                long t = gcs.get(i).getCollectionTime();
                long dc = c - lastCount[i];
                long dt = t - lastTime[i];
                lastCount[i] = c;
                lastTime[i] = t;
                if (dc > 0 && dt >= 0) {
                    long each = Math.max(1, dt / dc);
                    for (long k = 0; k < Math.min(dc, 5); k++) {
                        if (gc.length() > 0) gc.append(',');
                        gc.append("{\"ms\":").append(each).append('}');
                    }
                }
            }

            double mainCpu = -1;
            if (cpuOk && renderThreadId >= 0) {
                try {
                    long cpu = threads.getThreadCpuTime(renderThreadId);
                    long wall = System.nanoTime();
                    if (lastCpu >= 0 && cpu >= 0 && wall > lastWall) {
                        mainCpu = Math.min(100.0, 100.0 * (cpu - lastCpu) / (wall - lastWall));
                    }
                    lastCpu = cpu;
                    lastWall = wall;
                } catch (Throwable ignored) {
                    cpuOk = false;
                }
            }

            StringBuilder sb = new StringBuilder(512);
            sb.append('{');
            sb.append("\"frames\":[");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(Math.round(batch.get(i) * 10f) / 10f);
            }
            sb.append(']');
            if (fps >= 0) sb.append(",\"fps\":").append(fps);
            if (tps >= 0) sb.append(",\"tps\":").append(round1(tps));
            if (mspt >= 0) sb.append(",\"mspt\":").append(round1(mspt));
            sb.append(",\"gc\":[").append(gc).append(']');
            try {
                Runtime rt = Runtime.getRuntime();
                MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                MemoryUsage non = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
                sb.append(",\"heapUsed\":").append(heap.getUsed());
                sb.append(",\"heapMax\":").append(heap.getMax() > 0 ? heap.getMax() : rt.maxMemory());
                sb.append(",\"nonHeap\":").append(non.getUsed());
            } catch (Throwable ignored) {
                // память не прочитали: пропускаем поля
            }
            if (chunks >= 0) sb.append(",\"chunks\":").append(chunks);
            if (entities >= 0) sb.append(",\"entities\":").append(entities);
            if (blockEntities >= 0) sb.append(",\"blockEntities\":").append(blockEntities);
            if (renderDist > 0) sb.append(",\"renderDist\":").append(renderDist);
            if (mainCpu >= 0) sb.append(",\"mainCpu\":").append(round1(mainCpu));
            int loads = chunkLoads;
            chunkLoads = 0;
            sb.append(",\"chunkLoads\":").append(loads);
            String z = zonesJson;
            if (radar && z != null) sb.append(",\"zones\":").append(z);
            String wj = worldJson;
            if (wj != null) sb.append(",\"world\":").append(wj);
            String av = avatarOn ? avatarJson : null;
            if (av != null) sb.append(",\"avatar\":").append(av);
            sb.append('}');

            if (post(sb.toString())) {
                failures = 0;
            } else if (++failures >= 40) {
                // лаунчер не отвечает 20 секунд подряд (закрыт или порт сменился): перестаём слать
                stopped = true;
                LOG.warning("NexusPerf: launcher is not responding, metrics are turned off");
                return;
            }
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static boolean post(String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/").openConnection();
            c.setConnectTimeout(500);
            c.setReadTimeout(800);
            c.setRequestMethod("POST");
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-nexus-token", TOKEN);
            c.setDoOutput(true);
            byte[] data = json.getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream out = c.getOutputStream();
            out.write(data);
            out.close();
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            in.close();
            String resp = n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            radar = resp.contains("\"radar\":true");
            avatarOn = resp.contains("\"avatar\":true");
            return true;
        } catch (Exception e) {
            if (LOG.isLoggable(Level.FINE)) LOG.log(Level.FINE, "NexusPerf send failed", e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
