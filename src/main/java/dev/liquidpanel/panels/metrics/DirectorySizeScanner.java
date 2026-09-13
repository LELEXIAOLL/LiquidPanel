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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 目录大小扫描器：整个服务端文件夹多大，以及各个世界文件夹各占多少。
 *
 * <p>目录大小必须递归遍历每个文件，几个 GB 的世界动辄几万个文件，
 * 所以<b>不能</b>在每次状态推送时现算。这里改成后台异步定时扫描 + 缓存，
 * 前端读的始终是上一次的结果。
 *
 * <p>扫描走的是纯元数据读取（{@code attrs.size()}），不读取文件内容，
 * 因此对服务端磁盘 IO 的影响很小；再加上间隔是分钟级，不会和存档写入打架。
 */
public final class DirectorySizeScanner {

    /** 需要单独列出大小的目录，相对于服务端根目录 */
    private static final List<String> TRACKED_FOLDERS = List.of("world", "world_nether", "world_the_end");

    /** 首次扫描延迟（游戏刻）：错开启动高峰 */
    private static final long FIRST_DELAY_TICKS = 200L;

    /** 刷新间隔（游戏刻），6000 刻 = 5 分钟 */
    private static final long REFRESH_INTERVAL_TICKS = 6000L;

    private final File serverRoot;

    /** 顶层目录名 -> 字节数。世界之外的目录也留着，反正一趟遍历顺手就统计了 */
    private final Map<String, Long> sizes = new ConcurrentHashMap<>();

    /** 不存在的目录（比如没开下界/末地） */
    private final Set<String> absent = ConcurrentHashMap.newKeySet();

    /** 根目录下所有文件的总字节数 */
    private volatile long rootBytes;

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
     * 服务端根目录下所有文件的总字节数，还没扫过时为 0。
     */
    public long getRootBytes() {
        return rootBytes;
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

    /**
     * 从服务端根目录走一趟，同时算出根目录总量与每个顶层子目录的大小。
     *
     * <p>原来是「要哪个文件夹就单独遍历哪个」，三个世界就是三趟。
     * 改成从根目录走一趟之后，不但少了两趟 IO，还顺手把整个服务端文件夹的大小
     * 算了出来 —— 它和各子目录的和出自同一次遍历，不会因为两次扫描之间有文件变动而对不上。
     */
    private void scanAll() {
        if (!scanning.compareAndSet(false, true)) {
            // 上一轮还在跑，跳过这一次，避免堆积
            return;
        }
        try {
            Map<String, Long> found = new HashMap<>();
            long total = walk(found);

            sizes.clear();
            sizes.putAll(found);

            absent.clear();
            for (String name : TRACKED_FOLDERS) {
                if (!found.containsKey(name)) {
                    absent.add(name);
                }
            }

            rootBytes = total;
            lastScanAt = System.currentTimeMillis();
            logFirstScan();
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 遍历根目录，把每个文件的字节数同时累加到「总量」和「它所属的顶层目录」上。
     *
     * <p>所属目录由 {@code preVisitDirectory} 记下来，没有在 {@code visitFile} 里
     * 逐个做路径解析 —— 那要对几十万个文件各算一次相对路径，纯属浪费。
     *
     * <p>不跟随符号链接（{@code walkFileTree} 的默认行为），
     * 避免链接指回上级导致无限递归或把目录外的内容算进来。
     */
    private long walk(Map<String, Long> byTopFolder) {
        Path root = serverRoot.toPath();
        long[] total = {0L};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {

                /**
                 * 当前所在的顶层目录名。
                 *
                 * <p>根目录自己的直属文件不属于任何顶层目录，所以初值是 null ——
                 * {@code walkFileTree} 会先处理完根目录的直接文件再递归子目录，
                 * 那批文件正好落在 null 上，不计入任何子目录。
                 */
                private String topFolder;

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path parent = dir.getParent();
                    if (parent != null && parent.equals(root)) {
                        topFolder = dir.getFileName().toString();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    long size = attrs.size();
                    total[0] += size;
                    if (topFolder != null) {
                        byTopFolder.merge(topFolder, size, Long::sum);
                    }
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

    /**
     * 第一次扫描后把根目录与各文件夹的结果打出来。
     * 世界目录名不是默认值（改了 level-name）或者根目录判断错了，看这条日志就能定位。
     */
    private void logFirstScan() {
        if (logged.compareAndSet(false, true)) {
            StringBuilder message = new StringBuilder("目录扫描完成，根目录 ")
                    .append(serverRoot.getAbsolutePath())
                    .append("，合计 ").append(toMb(rootBytes)).append(" MB");
            for (String name : TRACKED_FOLDERS) {
                message.append("，").append(name).append(absent.contains(name)
                        ? " 不存在"
                        : " " + toMb(sizes.getOrDefault(name, 0L)) + " MB");
            }
            MessagesManager.logRaw(message.toString());
        }
    }

    private long toMb(long bytes) {
        return bytes / 1024L / 1024L;
    }
}
