package dev.liquidpanel.panels.metrics;

import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * CPU / 内存的历史采样，供前端画实时曲线。
 *
 * <p>采样任务固定在 Bukkit 异步线程池上跑，指标本身只需要 JMX 与 {@link Runtime}；
 * 只有「在线人数」必须回主线程取，那一步通过 {@link TaskUtil#awaitSync} 完成，
 * 且只是个 {@code size()} 调用，主线程不做任何实事。
 *
 * <p>取不到的值统一存 {@code -1}，由前端断开折线，而不是拿 0 画一条假的谷底。
 */
public final class MetricsHistory {

    /** 采样点数量。配合 2 秒间隔 = 最近 4 分钟 */
    private static final int CAPACITY = 120;

    /** 采样间隔（游戏刻），40 刻 = 2 秒 */
    private static final long INTERVAL_TICKS = 40L;

    private final ArrayDeque<Sample> samples = new ArrayDeque<>(CAPACITY);

    /**
     * TPS 来源，由 {@code PanelStatusProvider} 注入。
     *
     * <p>{@code TpsTracker.getTps} 本身是 synchronized 的，直接从采样线程读即可，
     * 不必像在线人数那样跳一次主线程。
     */
    private volatile Supplier<double[]> tpsSource;

    private BukkitTask task;

    /**
     * 设置 TPS 来源，返回数组依次为 1 分钟 / 5 分钟 / 15 分钟。
     */
    public void setTpsSource(Supplier<double[]> source) {
        this.tpsSource = source;
    }

    /**
     * 开始采样。重复调用只会保留一个任务。
     */
    public synchronized void start(JavaPlugin plugin) {
        stop();
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::sample, INTERVAL_TICKS, INTERVAL_TICKS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 复制一份全部采样点，用于首次加载时一次性补给前端。
     */
    public synchronized List<Sample> snapshot() {
        return new ArrayList<>(samples);
    }

    /**
     * 最新一个采样点，用于增量推送。
     */
    public synchronized Sample latest() {
        return samples.peekLast();
    }

    private synchronized void add(Sample sample) {
        samples.addLast(sample);
        while (samples.size() > CAPACITY) {
            samples.removeFirst();
        }
    }

    private void sample() {
        Sample sample = new Sample();
        sample.t = System.currentTimeMillis();

        java.lang.management.OperatingSystemMXBean base = ManagementFactory.getOperatingSystemMXBean();
        if (base instanceof com.sun.management.OperatingSystemMXBean extended) {
            try {
                sample.cpu = toPercent(extended.getProcessCpuLoad());
                sample.cpuAll = toPercent(extended.getCpuLoad());

                long total = extended.getTotalMemorySize();
                long free = extended.getFreeMemorySize();
                long used = Math.max(0L, total - free);
                sample.memAll = total > 0L ? round(used * 100D / total) : -1D;
                sample.memAllUsed = used;
            } catch (Throwable ignored) {
                sample.cpu = -1D;
                sample.cpuAll = -1D;
                sample.memAll = -1D;
            }
        } else {
            sample.cpu = -1D;
            sample.cpuAll = -1D;
            sample.memAll = -1D;
        }

        // 本进程内存用 JVM 堆：已用 / 上限（-Xmx）
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        sample.mem = max > 0L ? round(used * 100D / max) : -1D;
        sample.memUsed = used;

        // 在线人数只有主线程拿得到。这里做一次很轻的跳转（就是个 size()），
        // 拿到后立刻返回，不会让主线程做任何实事。
        Integer online = TaskUtil.awaitSync(() -> Bukkit.getOnlinePlayers().size(), -1);
        sample.players = online == null ? -1 : online;

        sample.tps1 = -1D;
        sample.tps5 = -1D;
        sample.tps15 = -1D;
        Supplier<double[]> source = tpsSource;
        if (source != null) {
            try {
                double[] values = source.get();
                if (values != null && values.length >= 3) {
                    sample.tps1 = values[0];
                    sample.tps5 = values[1];
                    sample.tps15 = values[2];
                }
            } catch (Throwable ignored) {
                // 取不到就保持 -1，前端会断开折线
            }
        }

        add(sample);
    }

    private double toPercent(double load) {
        return load < 0D ? -1D : round(load * 100D);
    }

    private double round(double value) {
        return Math.round(value * 10D) / 10D;
    }

    /**
     * 一个采样点。字段名刻意取短，因为每次首次加载要一次性传 120 个。
     */
    public static final class Sample {

        /** 采样时间戳 */
        public long t;

        /** 本进程（Java 服务端）CPU 占用率 % */
        public double cpu;

        /** 整机 CPU 占用率 % */
        public double cpuAll;

        /** 本进程内存占用率 %（堆已用 / 堆上限） */
        public double mem;

        /** 整机内存占用率 % */
        public double memAll;

        /** 本进程堆已用字节。曲线只画百分比，但 tooltip 要显示具体数值 */
        public long memUsed;

        /** 整机已用字节 */
        public long memAllUsed;

        /** 在线人数，-1 表示取不到 */
        public int players;

        /** 1 分钟平均 TPS，-1 表示样本不足 */
        public double tps1;

        /** 5 分钟平均 TPS */
        public double tps5;

        /** 15 分钟平均 TPS */
        public double tps15;
    }
}
