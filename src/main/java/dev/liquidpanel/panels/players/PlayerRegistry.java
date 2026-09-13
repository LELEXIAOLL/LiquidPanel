package dev.liquidpanel.panels.players;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.database.PlayerProfile;
import dev.liquidpanel.panels.database.PlayerStore;
import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 玩家名册：面板见过谁、谁最后什么时候在线。
 *
 * <p>光靠 {@code getOnlinePlayers()} 是列不出「所有玩家」和「离线玩家」的，
 * 所以这里自己攒一份。数据来源有两处，各管一段：
 * <ul>
 *     <li><b>启动时</b>用服务端的离线玩家列表补全一次 —— 这样面板刚装上就能看到
 *         以前玩过的老玩家，而不必等他们重新上线；</li>
 *     <li><b>运行中</b>由上下线事件维护，只改内存。</li>
 * </ul>
 *
 * <h2>写盘策略</h2>
 * 上下线只更新内存并置一个脏标记，落盘交给每分钟一次的定时任务，
 * 而且<b>没改动就一个字节都不写</b>。原因是玩家网络不稳时会反复掉线重连，
 * 一次重连写一遍盘，几百人的服就是持续不断的磁盘写入 —— 而这份数据的
 * 时效性要求其实低到分钟级都嫌高，攒一批写一次完全够用。
 */
public final class PlayerRegistry implements Listener {

    /** 首次补全延迟（游戏刻），400 刻 = 20 秒，错开开服高峰 */
    private static final long INITIAL_SCAN_DELAY_TICKS = 400L;

    /** 落盘检查间隔（游戏刻），1200 刻 = 60 秒 */
    private static final long FLUSH_INTERVAL_TICKS = 1200L;

    private final PlayerStore store;

    /** 名册一变就加一，前端据此决定要不要重新拉列表 */
    private final DataRevision revision;

    /** UUID -> 档案 */
    private final Map<String, PlayerProfile> profiles = new ConcurrentHashMap<>();

    /** 攒着还没落盘的改动 */
    private final AtomicBoolean dirty = new AtomicBoolean();

    private BukkitTask flushTask;

    public PlayerRegistry(PlayerStore store, DataRevision revision) {
        this.store = store;
        this.revision = revision;
    }

    /**
     * 载入已有名册、注册事件、起落盘任务。由主类在 onEnable 中调用。
     */
    public void init(JavaPlugin plugin) {
        if (store == null) {
            // 存储没起来时连事件都不用监听：听了也存不下，白占内存
            MessagesManager.logRaw("§e玩家名册存储不可用，玩家管理将只能列出在线玩家");
            return;
        }

        loadFromStore();

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // 服务端的离线玩家列表只有主线程能取，而且几千人的服要跑上一会儿，
        // 所以推到开服高峰之后，而且只做这一次
        Bukkit.getScheduler().runTaskLater(plugin, this::completeFromServer, INITIAL_SCAN_DELAY_TICKS);

        flushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::flush, FLUSH_INTERVAL_TICKS, FLUSH_INTERVAL_TICKS);
    }

    /**
     * 停掉落盘任务并把攒着的改动写掉。由主类在 onDisable 中调用。
     */
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel();
            flushTask = null;
        }
        HandlerList.unregisterAll(this);
        // 关服前补一次，否则最后一次落盘之后的上线记录就丢了
        flush();
    }

    /**
     * 全部档案的快照。
     */
    public Collection<PlayerProfile> all() {
        return new ArrayList<>(profiles.values());
    }

    public int size() {
        return profiles.size();
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /**
     * 上线只刷新时间与名字，<b>不动位置</b> ——
     * 「最后位置」的含义是他上次下线时在哪，刚上线的人还没下线过，
     * 那个字段应该留着上一次的值。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer(), false);
    }

    /**
     * 下线时除了刷新时间，还要把位置记下来。
     *
     * <p>这是唯一能拿到「他最后在哪儿」的时机：玩家一走，实体就没了。
     * 代价是服务器被强杀时（没走正常退出流程）最后一段位置会丢，
     * 那属于可接受的损耗。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        touch(event.getPlayer(), true);
    }

    /**
     * 刷新一个玩家的档案：只改内存 + 置脏标记，<b>不碰磁盘</b>。
     *
     * @param recordLocation 是否把当前位置一起记下来（只有下线时为 true）
     */
    private void touch(Player player, boolean recordLocation) {
        if (store == null) {
            return;
        }

        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long now = System.currentTimeMillis();

        // 位置先取出来：compute 的回调跑在 ConcurrentHashMap 的锁里，
        // 不该在那个位置再去调 Bukkit 的接口
        Location location = recordLocation ? player.getLocation() : null;

        profiles.compute(uuid, (key, existing) -> {
            PlayerProfile profile = existing == null
                    ? PlayerProfile.of(uuid, name, now)
                    : existing.touch(name, now);

            if (location != null && location.getWorld() != null) {
                profile.withLocation(location.getWorld().getName(),
                        location.getX(), location.getY(), location.getZ());
            }
            return profile;
        });

        dirty.set(true);
        revision.bump();
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 从存储载入已有名册。
     *
     * <p>读盘是阻塞 IO，不能挂在 onEnable 的主线程路径上，所以丢异步。
     * 载入完成前名册是空的，但那段窗口里 {@link #completeFromServer()} 还没跑，
     * 两边都是「合并」而不是「覆盖」，先后顺序不影响结果。
     */
    private void loadFromStore() {
        TaskUtil.runAsync(() -> {
            try {
                Map<String, PlayerProfile> loaded = store.load();
                profiles.putAll(loaded);
                MessagesManager.logRaw("§7玩家名册已载入 §f" + loaded.size() + " §7条记录");
            } catch (Exception e) {
                MessagesManager.logThrowable("玩家名册载入失败", e);
            }
        });
    }

    /**
     * 用服务端的离线玩家列表补全名册。
     *
     * <p>只做这一次。之后玩家的来去全由上下线事件维护 ——
     * 反复调这个接口既慢（要逐个读玩家数据文件）又没有必要。
     */
    private void completeFromServer() {
        long start = System.currentTimeMillis();
        int added = 0;

        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            String name = player.getName();
            if (name == null || name.isBlank()) {
                continue;
            }

            String uuid = player.getUniqueId().toString();
            if (profiles.containsKey(uuid)) {
                continue;
            }

            // 服务端只给得到「最后游玩时间」，没有首次时间，就先拿它顶上
            long lastPlayed = player.getLastPlayed();
            profiles.put(uuid, PlayerProfile.of(uuid, name,
                    lastPlayed > 0L ? lastPlayed : System.currentTimeMillis()));
            added++;
        }

        if (added > 0) {
            dirty.set(true);
            revision.bump();
        }
        MessagesManager.logRaw("§7玩家名册补全完成：新增 §f" + added + " §7条，耗时 "
                + (System.currentTimeMillis() - start) + " ms");
    }

    /**
     * 把攒下的改动写进存储。
     *
     * <p>关键在开头的 CAS：<b>没有改动就直接返回，一个字节都不写</b>。
     * 任务每分钟跑一次，但只有真的有人上下线过才会碰磁盘。
     */
    private void flush() {
        if (store == null || !dirty.compareAndSet(true, false)) {
            return;
        }

        try {
            store.saveAll(new ArrayList<>(profiles.values()));
        } catch (Exception e) {
            // 把标记放回去下一轮重试，否则这批变更就永远丢了
            dirty.set(true);
            MessagesManager.logRaw("§c玩家名册写入失败: " + e.getMessage());
        }
    }
}
