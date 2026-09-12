package dev.liquidpanel.panels;

import dev.liquidpanel.panels.metrics.SystemMetrics;
import dev.liquidpanel.utils.TaskUtil;

import java.util.Map;

/**
 * 面板状态汇总入口。
 *
 * <p>把两块数据合成一份给前端的 payload：
 * <ul>
 *     <li>{@link PanelStatusCollector} —— 需要主线程的 Bukkit 数据（玩家、世界、TPS、JVM 堆）</li>
 *     <li>{@link SystemMetrics} —— 机器级指标（CPU、物理内存、硬盘、目录大小），不需要主线程</li>
 * </ul>
 *
 * <p>HTTP 接口与 WebSocket 推送都走这里，保证两条路径给出的结构完全一致。
 */
public final class PanelStatusProvider {

    private final PanelStatusCollector collector;
    private final SystemMetrics systemMetrics;

    public PanelStatusProvider(PanelStatusCollector collector, SystemMetrics systemMetrics) {
        this.collector = collector;
        this.systemMetrics = systemMetrics;
        // TPS 由采集器统计，历史采样要用它画曲线，这里把两者接上
        systemMetrics.setTpsSource(collector::getTpsValues);
    }

    /**
     * 采集一份完整状态。可以在任意非主线程调用：
     * Bukkit 那部分会自动切回主线程，且带超时兜底。
     *
     * @param includeHistory true 附带全部历史采样，供首次加载一次画满曲线；
     *                       false 只带最新一个采样点，供 WebSocket 增量推送
     * @return 采集结果；主线程超时无响应时返回 null
     */
    public Map<String, Object> collect(boolean includeHistory) {
        Map<String, Object> status = TaskUtil.awaitSync(collector::collect, null);
        if (status == null) {
            return null;
        }
        status.put("system", systemMetrics.snapshot(includeHistory));
        return status;
    }
}
