package dev.liquidpanel.panels;

import dev.liquidpanel.utils.TpsTracker;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端状态采集器。
 *
 * <p><b>所有方法都必须在 Minecraft 主线程调用。</b>
 * 面板线程请通过 {@link dev.liquidpanel.utils.TaskUtil#awaitSync} 或
 * {@link dev.liquidpanel.utils.TaskUtil#supplySync} 间接调用本类。
 */
public final class PanelStatusCollector {

    private final long startTime = System.currentTimeMillis();
    private final TpsTracker tpsTracker = new TpsTracker();

    /**
     * 开始统计 TPS。需要在主线程调用（由主类在启用时触发）。
     */
    public void start(JavaPlugin plugin) {
        tpsTracker.start(plugin);
    }

    public void stop() {
        tpsTracker.stop();
    }

    /**
     * 取 1 分钟 / 5 分钟 / 15 分钟平均 TPS。
     *
     * <p>{@code TpsTracker} 的读取是加锁的，任意线程调用都安全，
     * 所以历史采样线程可以直接取，不用回主线程。
     */
    public double[] getTpsValues() {
        return new double[] { tpsTracker.getTps(60), tpsTracker.getTps(300), tpsTracker.getTps(900) };
    }

    /**
     * 采集一份完整状态，返回值可以直接序列化成 JSON 发给前端。
     */
    public Map<String, Object> collect() {
        Map<String, Object> status = new LinkedHashMap<>();

        status.put("uptimeMillis", System.currentTimeMillis() - startTime);
        status.put("tps", collectTps());
        status.put("onlinePlayers", Bukkit.getOnlinePlayers().size());
        status.put("maxPlayers", Bukkit.getMaxPlayers());
        status.put("memory", collectMemory());
        status.put("players", collectPlayers());
        status.put("worlds", collectWorlds());
        status.put("timestamp", System.currentTimeMillis());
        return status;
    }

    /**
     * Spigot 没有 TPS 查询接口，这里用自己统计的值。
     * 还没攒够样本时返回 -1，前端显示为「不可用」，不会拿假数据糊弄。
     */
    private Map<String, Object> collectTps() {
        Map<String, Object> tps = new LinkedHashMap<>();
        tps.put("oneMinute", tpsTracker.getTps(60));
        tps.put("fiveMinutes", tpsTracker.getTps(300));
        tps.put("fifteenMinutes", tpsTracker.getTps(900));
        return tps;
    }

    private Map<String, Object> collectMemory() {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long used = total - runtime.freeMemory();

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("usedMb", toMb(used));
        memory.put("allocatedMb", toMb(total));
        memory.put("maxMb", toMb(max));
        memory.put("usagePercent", max <= 0 ? 0D : round(used * 100D / max));
        return memory;
    }

    private List<Map<String, Object>> collectPlayers() {
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", player.getName());
            item.put("world", player.getWorld().getName());
            item.put("ping", player.getPing());
            item.put("health", round(player.getHealth()));
            item.put("gameMode", player.getGameMode().name());
            players.add(item);
        }
        return players;
    }

    private List<Map<String, Object>> collectWorlds() {
        List<Map<String, Object>> worlds = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", world.getName());
            item.put("players", world.getPlayers().size());
            item.put("loadedChunks", world.getLoadedChunks().length);
            item.put("time", world.getTime());
            item.put("storm", world.hasStorm());
            worlds.add(item);
        }
        return worlds;
    }

    private long toMb(long bytes) {
        return bytes / 1024L / 1024L;
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
