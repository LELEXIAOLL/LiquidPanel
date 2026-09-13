package dev.liquidpanel.panels.settings;

import com.google.gson.annotations.SerializedName;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.players.BanMethod;
import dev.liquidpanel.utils.FileUtil;
import dev.liquidpanel.utils.JsonUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.function.Consumer;

/**
 * 面板自身的运行时配置，存于 {@code panelconfig.json}。
 *
 * <p>与 {@code config.yml} 的分工：{@code config.yml} 是管理员手改的启动配置，
 * 需要重启或 reload；这里是<b>从网页上直接改、改完立刻生效并落盘</b>的东西，
 * 所以单独一个文件，避免网页去覆写管理员手写的 yml（会丢注释、丢格式）。
 */
public final class PanelConfigManager {

    private static final String FILE_NAME = "panelconfig.json";

    /** 每页玩家数允许的范围 */
    private static final int MIN_PAGE_SIZE = 1;
    private static final int MAX_PAGE_SIZE = 100;

    /** 自定义封禁命令的默认值，用 LiteBans / AdvancedBan 的语法，开箱就能对上大多数服 */
    public static final String DEFAULT_BAN_COMMAND_TEMP = "tempban %player% %time% %reason%";
    public static final String DEFAULT_BAN_COMMAND_PERM = "ban %player% %reason%";
    public static final String DEFAULT_BAN_COMMAND_UNBAN = "unban %player%";

    /** 命令模板里必须出现的占位符，缺了这条命令就没有意义 */
    public static final String PLAYER_PLACEHOLDER = "%player%";

    private final JavaPlugin plugin;
    private final File file;

    private volatile PanelConfig config;

    public PanelConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /**
     * 读取配置，不存在或损坏就写一份默认的出来。
     */
    public synchronized void init() {
        if (file.isFile()) {
            PanelConfig loaded = read();
            if (loaded != null) {
                loaded.normalize();
                this.config = loaded;
                return;
            }
            File backup = FileUtil.backup(file);
            if (backup != null) {
                console("&e" + FILE_NAME + " 内容异常，已备份为 " + backup.getName());
            }
        }

        this.config = new PanelConfig();
        save();
    }

    /**
     * 读一份快照。调用方拿到的是副本语义上的值，不会被后续修改影响。
     */
    public PanelConfig get() {
        return config == null ? new PanelConfig() : config;
    }

    /**
     * 修改并立刻落盘。
     *
     * @return 是否写入成功
     */
    public synchronized boolean update(Consumer<PanelConfig> mutator) {
        PanelConfig current = get();
        mutator.accept(current);
        current.normalize();
        return save();
    }

    public int getPlayersPerPage() {
        return get().playersPerPage;
    }

    private PanelConfig read() {
        try {
            return JsonUtil.fromJson(FileUtil.read(file), PanelConfig.class);
        } catch (Exception e) {
            console("&c读取 " + FILE_NAME + " 失败: " + e.getMessage());
            return null;
        }
    }

    private boolean save() {
        try {
            FileUtil.write(file, JsonUtil.toPrettyJson(config));
            return true;
        } catch (Exception e) {
            console("&c写入 " + FILE_NAME + " 失败: " + e.getMessage());
            return false;
        }
    }

    private void console(String message) {
        Bukkit.getConsoleSender().sendMessage(MessagesManager.getPrefix() + MessagesManager.color(message));
    }

    /**
     * 字段缺失或为空白时补默认值。只处理「没写」，不覆盖管理员填的内容。
     */
    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 面板运行时配置。
     */
    public static final class PanelConfig {

        /** 玩家管理每页显示多少个 */
        @SerializedName("players_per_page")
        public int playersPerPage = 20;

        /**
         * 封禁由谁执行，取值见 {@link BanMethod}，对应设置页「兼容性设置」的下拉框。
         */
        @SerializedName("ban_method")
        public String banMethod = BanMethod.VANILLA.id();

        /** 自定义方式下，临时封禁执行的命令。占位符：%player% %reason% %time% */
        @SerializedName("ban_command_temp")
        public String banCommandTemp = DEFAULT_BAN_COMMAND_TEMP;

        /** 自定义方式下，永久封禁执行的命令。占位符：%player% %reason% */
        @SerializedName("ban_command_perm")
        public String banCommandPerm = DEFAULT_BAN_COMMAND_PERM;

        /** 自定义方式下，到期解除封禁执行的命令。占位符：%player% */
        @SerializedName("ban_command_unban")
        public String banCommandUnban = DEFAULT_BAN_COMMAND_UNBAN;

        /**
         * 是否接入 Vault 经济。
         *
         * <p>打开后玩家管理页会显示余额，并能直接增减。
         * 服务端必须同时装了 Vault 和一个向它注册的经济插件，否则开关打开也没有效果，
         * 控制台会说明原因。
         */
        @SerializedName("vault_enabled")
        public boolean vaultEnabled = false;

        /**
         * 把非法值夹回合理范围。文件是可以被手改的，不能信任里面的内容。
         *
         * <p>这里只做「补空」，不校验 banMethod 是不是已知值 ——
         * 认不出来的方式由 {@code BanService} 回退成 Vanilla，
         * 那是安全的默认（就是面板原本的行为）。
         */
        void normalize() {
            if (playersPerPage < MIN_PAGE_SIZE) {
                playersPerPage = MIN_PAGE_SIZE;
            }
            if (playersPerPage > MAX_PAGE_SIZE) {
                playersPerPage = MAX_PAGE_SIZE;
            }
            banMethod = orDefault(banMethod, BanMethod.VANILLA.id());
            banCommandTemp = orDefault(banCommandTemp, DEFAULT_BAN_COMMAND_TEMP);
            banCommandPerm = orDefault(banCommandPerm, DEFAULT_BAN_COMMAND_PERM);
            banCommandUnban = orDefault(banCommandUnban, DEFAULT_BAN_COMMAND_UNBAN);
        }

        public static int minPageSize() {
            return MIN_PAGE_SIZE;
        }

        public static int maxPageSize() {
            return MAX_PAGE_SIZE;
        }
    }
}
