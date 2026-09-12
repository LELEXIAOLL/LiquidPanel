package dev.liquidpanel.utils;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * TPS 统计工具。
 *
 * <p>Spigot 的 {@code Server} 接口没有提供 TPS 查询（那是 Paper 才有的），所以这里自己算：
 * 用固定游戏刻间隔的调度任务测量真实经过的墙钟时间。
 * 任务按「游戏刻」计时，两次采样之间恒为 {@link #SAMPLE_INTERVAL_TICKS} 个刻；
 * 服务端一旦卡顿，这段真实耗时就会变长，算出来的 TPS 自然低于 20。
 *
 * <p>任务必须在主线程调度，所以由主类在启用时启动、卸载时停止。
 */
public final class TpsTracker {

    /** 采样间隔（游戏刻），100 刻 = 5 秒 */
    private static final long SAMPLE_INTERVAL_TICKS = 100L;

    /** 一个采样点覆盖的秒数 */
    private static final int SAMPLE_SECONDS = 5;

    /** 每分钟的采样点数 */
    private static final int SAMPLES_PER_MINUTE = 60 / SAMPLE_SECONDS;

    /** 保留最近 15 分钟的采样 */
    private static final int SAMPLE_COUNT = SAMPLES_PER_MINUTE * 15;

    /** 理论最大 TPS */
    private static final double MAX_TPS = 20.0D;

    /** 环形缓冲区 */
    private final double[] samples = new double[SAMPLE_COUNT];

    private int cursor;
    private int filled;
    private long lastSampleMillis;
    private BukkitTask task;

    /**
     * 开始统计。重复调用只会保留一个任务。
     */
    public synchronized void start(JavaPlugin plugin) {
        stop();
        lastSampleMillis = System.currentTimeMillis();
        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::sample, SAMPLE_INTERVAL_TICKS, SAMPLE_INTERVAL_TICKS);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 最近 1 分钟的平均 TPS。
     */
    public synchronized double getTps() {
        return getTps(60);
    }

    /**
     * 最近若干秒的平均 TPS。
     *
     * @return 样本不足时按已有样本估算；一个样本都没有时返回 -1，由调用方显示为「不可用」
     */
    public synchronized double getTps(int seconds) {
        int wanted = Math.min(Math.max(seconds, SAMPLE_SECONDS) / SAMPLE_SECONDS, filled);
        if (wanted <= 0) {
            return -1D;
        }

        double sum = 0D;
        for (int i = 0; i < wanted; i++) {
            int index = Math.floorMod(cursor - 1 - i, SAMPLE_COUNT);
            sum += samples[index];
        }
        return Math.round(sum / wanted * 100D) / 100D;
    }

    /**
     * 采样一次，由调度任务在主线程调用。
     */
    private synchronized void sample() {
        long now = System.currentTimeMillis();
        double elapsedSeconds = (now - lastSampleMillis) / 1000.0D;
        lastSampleMillis = now;

        if (elapsedSeconds <= 0D) {
            return;
        }

        samples[cursor] = Math.min(MAX_TPS, SAMPLE_INTERVAL_TICKS / elapsedSeconds);
        cursor = (cursor + 1) % SAMPLE_COUNT;
        if (filled < SAMPLE_COUNT) {
            filled++;
        }
    }
}
