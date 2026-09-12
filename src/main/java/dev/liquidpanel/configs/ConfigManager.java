package dev.liquidpanel.configs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 配置文件管理器。
 *
 * <p>负责缓存 config.yml，并对外提供统一的读取入口，其他类直接静态调用即可：
 * <pre>
 *     Object value = ConfigManager.getConfig("player.test");
 *     int amount   = ConfigManager.getInt("panel.size", 54);
 *     boolean flag = ConfigManager.getBoolean("auto_open_panel");
 * </pre>
 *
 * <p>{@link #getConfig(String)} 的返回类型由配置项本身决定，调用方按需接收即可；
 * 若需要明确的类型（或需要默认值），请使用下面的类型化方法，它们会在类型不符时返回默认值，
 * 不会抛出 ClassCastException。
 */
public final class ConfigManager {

    private static final String FILE_NAME = "config.yml";

    private static ConfigFileLoader loader;

    /** 永远不为 null，未初始化时是一个空配置，保证其他类随时可以安全调用 */
    private static YamlConfiguration config = new YamlConfiguration();

    private ConfigManager() {
    }

    /**
     * 初始化并读取配置，由主类在 onEnable 中调用。
     */
    public static void init(JavaPlugin plugin) {
        loader = new ConfigFileLoader(plugin, FILE_NAME, FILE_NAME);
        reload();
    }

    /**
     * 重新读取并缓存配置。磁盘文件异常时，{@link ConfigFileLoader} 会自动备份并释放默认文件。
     */
    public static void reload() {
        if (loader == null) {
            return;
        }
        YamlConfiguration loaded = loader.load();
        if (loaded != null) {
            config = loaded;
        }
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 读取原始配置项，返回类型由配置项本身决定。
     *
     * <p>配置项不存在时返回 {@code null}，调用方接收基本类型时请改用下面的类型化方法。
     */
    @SuppressWarnings("unchecked")
    public static <T> T getConfig(String path) {
        return (T) config.get(path);
    }

    public static boolean contains(String path) {
        return config.contains(path);
    }

    public static String getString(String path) {
        return getString(path, "");
    }

    public static String getString(String path, String def) {
        Object value = config.get(path);
        return value == null ? def : String.valueOf(value);
    }

    public static int getInt(String path) {
        return getInt(path, 0);
    }

    public static int getInt(String path, int def) {
        Object value = config.get(path);
        return value instanceof Number ? ((Number) value).intValue() : def;
    }

    public static long getLong(String path) {
        return getLong(path, 0L);
    }

    public static long getLong(String path, long def) {
        Object value = config.get(path);
        return value instanceof Number ? ((Number) value).longValue() : def;
    }

    public static double getDouble(String path) {
        return getDouble(path, 0D);
    }

    public static double getDouble(String path, double def) {
        Object value = config.get(path);
        return value instanceof Number ? ((Number) value).doubleValue() : def;
    }

    public static boolean getBoolean(String path) {
        return getBoolean(path, false);
    }

    public static boolean getBoolean(String path, boolean def) {
        Object value = config.get(path);
        return value instanceof Boolean ? (Boolean) value : def;
    }

    public static List<String> getStringList(String path) {
        List<?> list = config.getList(path);
        if (list == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            result.add(String.valueOf(element));
        }
        return result;
    }

    public static ConfigurationSection getSection(String path) {
        return config.getConfigurationSection(path);
    }

    /**
     * 读取当前使用的语言代码，对应语言文件 messages_&lt;language&gt;.yml。
     */
    public static String getLanguage() {
        return getString("language", MessagesFileLoader.DEFAULT_LANGUAGE);
    }

    /**
     * 拿到缓存本身，供需要遍历全部配置项的场景使用。
     */
    public static YamlConfiguration getRaw() {
        return config;
    }
}
