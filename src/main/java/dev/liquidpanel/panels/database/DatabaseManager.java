package dev.liquidpanel.panels.database;

import dev.liquidpanel.configs.ConfigManager;
import dev.liquidpanel.configs.MessagesManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Locale;

/**
 * 存储管理器：封禁记录与玩家名册。
 *
 * <p>按 config.yml 的 {@code panel.database.type} 决定用哪种实现。
 * 改类型需要重启服务端 —— 两种实现的文件格式互不相通，
 * 热切换等于把已有数据丢在旧文件里。
 *
 * <p>两份存储各自独立降级：任一个起不来都只返回 null，
 * 面板其它功能照常。调用方必须判空，并据此决定是降级还是拒绝操作。
 */
public final class DatabaseManager {

    public static final String DEFAULT_TYPE = JsonBanStore.TYPE;

    private static final String SQLITE_TYPE = SqliteBanStore.TYPE;

    /** sqlite 模式下两份数据共用一个库文件，各占一张表 */
    private static final String SQLITE_FILE = "panel.db";

    private static final String BAN_FILE_JSON = "bans.json";
    private static final String PLAYER_FILE_JSON = "players.json";

    private final JavaPlugin plugin;

    private BanStore banStore;
    private PlayerStore playerStore;
    private String type = DEFAULT_TYPE;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 按配置打开两份存储，由主类在 onEnable 中调用。
     */
    public void init() {
        type = normalize(ConfigManager.getString("panel.database.type", DEFAULT_TYPE));

        File folder = plugin.getDataFolder();
        banStore = openBanStore(folder);
        playerStore = openPlayerStore(folder);
    }

    /**
     * 当前封禁记录存储。<b>可能为 null</b> —— 初始化失败时就是 null。
     */
    public BanStore banStore() {
        return banStore;
    }

    /**
     * 当前玩家名册存储。<b>可能为 null</b>。
     */
    public PlayerStore playerStore() {
        return playerStore;
    }

    public String type() {
        return type;
    }

    public void close() {
        if (banStore != null) {
            banStore.close();
            banStore = null;
        }
        if (playerStore != null) {
            playerStore.close();
            playerStore = null;
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private BanStore openBanStore(File folder) {
        File file = new File(folder, isSqlite() ? SQLITE_FILE : BAN_FILE_JSON);
        BanStore store = isSqlite() ? new SqliteBanStore(file) : new JsonBanStore(file);

        try {
            store.open();
            MessagesManager.logRaw("§7封禁记录存储：§f" + type + " §7-> §f" + file.getAbsolutePath());
            return store;
        } catch (Throwable e) {
            // 退回「没有存储」而不是让面板起不来：服务端出问题的时候最需要管理面板可用
            MessagesManager.logThrowable(
                    "封禁记录存储(" + type + ")初始化失败，临时封禁将不可用（永久封禁不受影响）", e);
            return null;
        }
    }

    private PlayerStore openPlayerStore(File folder) {
        File file = new File(folder, isSqlite() ? SQLITE_FILE : PLAYER_FILE_JSON);
        PlayerStore store = isSqlite() ? new SqlitePlayerStore(file) : new JsonPlayerStore(file);

        try {
            store.open();
            MessagesManager.logRaw("§7玩家名册存储：§f" + type + " §7-> §f" + file.getAbsolutePath());
            return store;
        } catch (Throwable e) {
            MessagesManager.logThrowable(
                    "玩家名册存储(" + type + ")初始化失败，玩家管理将只能列出在线玩家", e);
            return null;
        }
    }

    private boolean isSqlite() {
        return SQLITE_TYPE.equals(type);
    }

    /**
     * 解析类型名。认不出来时退回 json，并明确告诉控制台写的是什么。
     */
    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (SQLITE_TYPE.equals(value)) {
            return SQLITE_TYPE;
        }
        if (!JsonBanStore.TYPE.equals(value) && !value.isEmpty()) {
            MessagesManager.logRaw("§epanel.database.type 的值 §f" + raw + " §e无法识别，已按 "
                    + JsonBanStore.TYPE + " 处理（可选：" + JsonBanStore.TYPE + " / " + SQLITE_TYPE + "）");
        }
        return JsonBanStore.TYPE;
    }
}
