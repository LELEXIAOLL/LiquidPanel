package dev.liquidpanel.panels.metrics;

import dev.liquidpanel.configs.MessagesManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 世界文件夹大小扫描器。
 *
 * <p>目录大小必须递归遍历每个文件，几个 GB 的世界动辄几万个文件，
 * 所以<b>不能</b>在每次状态推送时现算。这里改成后台异步定时扫描 + 缓存，
 * 前端读的始终是上一次的结果。
 *
 * <p>扫描走的是纯元数据读取（{@code attrs.size()}），不读取文件内容，
 * 因此对服务端磁盘 IO 的影响很小；再加上间隔是分钟级，不会和存档写入打架。
 */
public final class DirectorySizeScanner {

    /** 要统计的目录，相对于服务端根目录 */
    private static final List<String> TRACKED_FOLDERS = List.of("world", "world_nether", "world_the_end");

    /** 首次扫描延迟（游戏刻）：错开启动高峰 */
    private static final long FIRST_DELAY_TICKS = 200L;

    /** 刷新间隔（游戏刻），6000 刻 = 5 分钟 */
    private static final long REFRESH_INTERVAL_TICKS = 6000L;

    private final File serverRoot;

    /** 目录名 -> 字节数 */
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();

    /** 不存在的目录（比如没开下界/末地） */
    private final Set<String> absent = ConcurrentHashMap.newKeySet();

    /** 防止上一次还没扫完就又起一次 */
    private final AtomicBoolean scanning = new AtomicBoolean();

    /** 只提示一次扫描结果 */
    private final AtomicBoolean logged = new AtomicBoolean();

    private volatile long lastScanAt;
    private BukkitTask task;

    public DirectorySizeScanner(File serverRoot) {
        this.serverRoot = serverRoot;
    }

    /**
     * 开始定时扫描。任务跑在 Bukkit 异步线程池上，不占用主线程。
     */
    public synchronized void start(JavaPlugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::scanAll, FIRST_DELAY_TICKS, REFRESH_INTERVAL_TICKS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 取缓存的大小数据。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> folders = new LinkedHashMap<>();
        for (String name : TRACKED_FOLDERS) {
            Map<String, Object> entry = new LinkedHashMap<>();
            boolean exists = !absent.contains(name);
            entry.put("exists", exists);
            entry.put("bytes", exists ? sizes.getOrDefault(name, 0L) : 0L);
            folders.put(name, entry);
        }
        return folders;
    }

    /**
     * 上次扫描完成的时间戳，0 表示还没扫过。
     */
    public long getLastScanAt() {
        return lastScanAt;
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private void scanAll() {
        if (!scanning.compareAndSet(false, true)) {
            // 上一轮还在跑，跳过这一次，避免堆积
            return;
        }
        try {
            for (String name : TRACKED_FOLDERS) {
                File directory = new File(serverRoot, name);
                if (!directory.isDirectory()) {
                    absent.add(name);
                    sizes.remove(name);
                    continue;
                }
                absent.remove(name);
                sizes.put(name, measure(directory.toPath()));
            }
            lastScanAt = System.currentTimeMillis();
            logFirstScan();
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 第一次扫描后把根目录与各文件夹的结果打出来。
     * 世界目录名不是默认值（改了 level-name）或者根目录判断错了，看这条日志就能定位。
     */
    private void logFirstScan() {
        if (logged.compareAndSet(false, true)) {
            StringBuilder message = new StringBuilder("世界目录扫描完成，根目录 ").append(serverRoot.getAbsolutePath());
            for (String name : TRACKED_FOLDERS) {
                message.append("，").append(name).append(absent.contains(name)
                        ? " 不存在"
                        : " " + (sizes.getOrDefault(name, 0L) / 1024L / 1024L) + " MB");
            }
            MessagesManager.logRaw(message.toString());
        }
    }

    /**
     * 递归统计目录下所有文件的字节数。
     *
     * <p>不跟随符号链接（{@code walkFileTree} 默认行为），
     * 避免链接指回上级导致无限递归或算进目录外的内容。
     */
    private long measure(Path directory) {
        long[] total = {0L};
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    // 单个文件读不到（被占用、权限不足）就跳过，不要让整次扫描失败
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // 目录中途被删等情况，返回已经统计到的部分
        }
        return total[0];
    }
}
