package dev.liquidpanel.panels.metrics;

import dev.liquidpanel.utils.CpuModelUtil;
import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 机器级指标：CPU、物理内存、硬盘占用。
 *
 * <p>和 {@code PanelStatusCollector} 里的 JVM 堆内存是两回事 ——
 * 这里报的是<b>整台机器</b>的情况，所以要用 {@code com.sun.management} 那套 JMX 接口。
 *
 * <p>本类不碰任何 Bukkit API，因此可以在任意线程调用，不需要切回主线程。
 * 其中目录大小由 {@link DirectorySizeScanner} 异步扫描后缓存，这里只取结果。
 */
public final class SystemMetrics {

    private final File serverRoot;
    private final DirectorySizeScanner directoryScanner;
    private final MetricsHistory history = new MetricsHistory();

    /** CPU 型号不会变，解析一次就缓存住。初值走不启动进程的兜底，异步再升级 */
    private volatile String cachedCpuModel = CpuModelUtil.fallback();

    /** 保证外部进程只起一次 */
    private final AtomicBoolean cpuModelResolved = new AtomicBoolean();

    public SystemMetrics(File serverRoot) {
        this.serverRoot = serverRoot;
        this.directoryScanner = new DirectorySizeScanner(serverRoot);
    }

    public void start(JavaPlugin plugin) {
        directoryScanner.start(plugin);
        history.start(plugin);
        refreshCpuModelAsync();
    }

    public void stop() {
        directoryScanner.stop();
        history.stop();
    }

    /**
     * 注入 TPS 来源，交给历史采样一起记录。
     */
    public void setTpsSource(java.util.function.Supplier<double[]> source) {
        history.setTpsSource(source);
    }

    /**
     * 汇总一份系统指标。
     *
     * @param includeHistory true 时附带全部历史采样（首次加载用）；
     *                       false 时只带最新一个点（WebSocket 增量推送用，
     *                       否则每 2 秒重传 120 个采样点太浪费）
     */
    public Map<String, Object> snapshot(boolean includeHistory) {
        Map<String, Object> system = new LinkedHashMap<>();
        system.put("cpu", cpu());
        system.put("memory", memory());
        system.put("processMemory", processMemory());
        system.put("disk", disk());
        system.put("folders", directoryScanner.snapshot());
        system.put("foldersScannedAt", directoryScanner.getLastScanAt());

        if (includeHistory) {
            system.put("history", history.snapshot());
        } else {
            MetricsHistory.Sample latest = history.latest();
            if (latest != null) {
                system.put("sample", latest);
            }
        }
        return system;
    }

    // ------------------------------------------------------------------
    // CPU 型号
    // ------------------------------------------------------------------

    /**
     * CPU 型号。
     *
     * <p>友好型号在 Windows 上要起外部进程，冷启动可能被杀软扫描拖到几秒
     * （见 {@link CpuModelUtil} 里记录的实测数据）。
     * 所以这里先用不启动进程的兜底值保证任何时候读都不为空，
     * 再在异步线程上升级成友好型号 —— 既不阻塞任何人，也不会出现空白。
     */
    private String cpuModel() {
        return cachedCpuModel;
    }

    private void refreshCpuModelAsync() {
        // 这个进程只值得起一次：reload 会再次走到这里，而冷启动可能要几秒
        if (!cpuModelResolved.compareAndSet(false, true)) {
            return;
        }
        TaskUtil.runAsync(() -> {
            String friendly = CpuModelUtil.resolve();
            if (friendly != null && !friendly.isBlank()) {
                cachedCpuModel = friendly;
            }
        });
    }

    // ------------------------------------------------------------------
    // CPU
    // ------------------------------------------------------------------

    private Map<String, Object> cpu() {
        Map<String, Object> cpu = new LinkedHashMap<>();
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        cpu.put("model", cpuModel());
        cpu.put("cores", base.getAvailableProcessors());

        double systemLoad = -1D;
        double processLoad = -1D;
        if (base instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                systemLoad = extended.getCpuLoad();
                processLoad = extended.getProcessCpuLoad();
            } catch (Throwable ignored) {
                // 部分 JVM/容器环境取不到，保持 -1 让前端显示「不可用」
            }
        }
        cpu.put("systemLoadPercent", toPercent(systemLoad));
        cpu.put("processLoadPercent", toPercent(processLoad));
        return cpu;
    }

    /**
     * JDK 在取不到负载时返回负数，这里统一转成 -1 由前端显示「不可用」，
     * 免得把 -1% 当成真实数据画出去。
     */
    private double toPercent(double load) {
        return load < 0D ? -1D : round(load * 100D);
    }

    // ------------------------------------------------------------------
    // 物理内存
    // ------------------------------------------------------------------

    private Map<String, Object> memory() {
        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();

        if (base instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                long total = extended.getTotalMemorySize();
                long free = extended.getFreeMemorySize();
                long used = Math.max(0L, total - free);

                Map<String, Object> memory = new LinkedHashMap<>();
                memory.put("totalBytes", total);
                memory.put("usedBytes", used);
                memory.put("freeBytes", free);
                memory.put("usagePercent", total <= 0L ? 0D : round(used * 100D / total));
                return memory;
            } catch (Throwable ignored) {
                // 落到下面的不可用分支
            }
        }

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("totalBytes", 0L);
        memory.put("usedBytes", 0L);
        memory.put("freeBytes", 0L);
        memory.put("usagePercent", -1D);
        return memory;
    }

    /**
     * 本进程（Java 服务端）的堆内存，卡片上要显示 -Xmx 上限。
     */
    private Map<String, Object> processMemory() {
        Runtime runtime = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxBytes", runtime.maxMemory());
        memory.put("usedBytes", runtime.totalMemory() - runtime.freeMemory());
        return memory;
    }

    // ------------------------------------------------------------------
    // 硬盘
    // ------------------------------------------------------------------

    /**
     * 服务端所在盘符的占用情况。
     *
     * <p>用 {@code getUsableSpace} 而不是 {@code getFreeSpace}：
     * 前者扣掉了进程无权使用的保留块，更接近「还能不能写进去」。
     */
    private Map<String, Object> disk() {
        Path path = serverRoot.toPath().toAbsolutePath();
        Path root = path.getRoot();

        Map<String, Object> disk = new LinkedHashMap<>();
        disk.put("path", path.toString());
        disk.put("root", root == null ? "/" : root.toString());

        long total = 0L;
        long usable = 0L;
        try {
            FileStore store = Files.getFileStore(path);
            total = store.getTotalSpace();
            usable = store.getUsableSpace();
        } catch (Exception e) {
            // 拿不到 FileStore 就退回 File 的实现
            total = serverRoot.getTotalSpace();
            usable = serverRoot.getUsableSpace();
        }

        long used = Math.max(0L, total - usable);
        disk.put("totalBytes", total);
        disk.put("usedBytes", used);
        disk.put("freeBytes", usable);
        disk.put("usagePercent", total <= 0L ? 0D : round(used * 100D / total));
        return disk;
    }

    private double round(double value) {
        return Math.round(value * 10D) / 10D;
    }
}
