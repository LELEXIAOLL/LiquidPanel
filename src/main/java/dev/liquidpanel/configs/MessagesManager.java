package dev.liquidpanel.configs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 语言文件管理器。
 *
 * <p>加载时会把文件里所有字符串的 {@code &} 颜色代码统一转换为 {@code §} 之后再缓存，
 * 因此其他类拿到的文本可以直接发送给玩家：
 * <pre>
 *     String text = MessagesManager.getMessage("messages.test");
 *     MessagesManager.send(sender, "reload.success", "time", "12");
 * </pre>
 *
 * <p>语言文件由 config.yml 的 {@code language} 配置项决定，所以重载时必须先重载配置再重载语言。
 */
public final class MessagesManager {

    /** 语言文件还没有加载时的兜底前缀，保证 getPrefix() 任何时候都不会返回 null */
    private static final String FALLBACK_PREFIX = "&bLiquidPanel &7>> ";

    private static final String MISSING_PREFIX = "&c[缺失语言项: ";
    private static final String MISSING_SUFFIX = "]";

    private static JavaPlugin plugin;
    private static MessagesFileLoader loader;

    private static String language = MessagesFileLoader.DEFAULT_LANGUAGE;

    /** 永远不为 null，未初始化时是一个空配置 */
    private static YamlConfiguration messages = new YamlConfiguration();

    private MessagesManager() {
    }

    /**
     * 初始化并读取语言文件，由主类在 onEnable 中调用（必须在 ConfigManager.init 之后）。
     */
    public static void init(JavaPlugin plugin) {
        MessagesManager.plugin = plugin;
        reload();
    }

    /**
     * 按当前配置的语言重新读取并缓存语言文件，同时完成 {@code &} -> {@code §} 的转换。
     */
    public static void reload() {
        language = ConfigManager.getLanguage();
        loader = new MessagesFileLoader(plugin, language);

        YamlConfiguration loaded = loader.load();
        if (loaded != null) {
            messages = loaded;
        }
        colorize(messages);
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /**
     * 读取语言文本。文本中的颜色代码已经在加载时转换完毕，可以直接使用。
     *
     * @param path         配置项路径
     * @param replacements 占位符替换，成对传入，例如 {@code "time", "12"} 会替换掉 {@code %time%}
     */
    public static String getMessage(String path, String... replacements) {
        Object value = messages.get(path);
        if (value == null) {
            // 加载时已经按默认语言文件校验过，正常情况不会走到这里
            return color(MISSING_PREFIX + path + MISSING_SUFFIX);
        }
        return applyReplacements(String.valueOf(value), replacements);
    }

    /**
     * 读取插件前缀，对应语言文件的 {@code prefix} 配置项。
     */
    public static String getPrefix() {
        Object value = messages.get("prefix");
        return value == null ? color(FALLBACK_PREFIX) : String.valueOf(value);
    }

    /**
     * 读取原始值（未做占位符替换），一般不需要使用。
     */
    public static Object getRaw(String path) {
        return messages.get(path);
    }

    public static boolean contains(String path) {
        return messages.contains(path);
    }

    public static String getLanguage() {
        return language;
    }

    // ------------------------------------------------------------------
    // 输出（统一带前缀）
    // ------------------------------------------------------------------

    /**
     * 向命令发送者发送一条带前缀的提示。
     */
    public static void send(CommandSender sender, String path, String... replacements) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(getPrefix() + getMessage(path, replacements));
    }

    /**
     * 向控制台发送一条带前缀的提示。
     */
    public static void log(String path, String... replacements) {
        Bukkit.getConsoleSender().sendMessage(getPrefix() + getMessage(path, replacements));
    }

    /**
     * 直接输出一段带前缀的控制台文本，不做语言项查找。
     *
     * <p>用于诊断信息这类含运行时数据、不适合写进语言文件的内容。
     */
    public static void logRaw(String text) {
        Bukkit.getConsoleSender().sendMessage(getPrefix() + color(text));
    }

    /**
     * 向控制台发送一条带前缀的红色提示，并打印异常堆栈。
     */
    public static void logThrowable(String message, Throwable throwable) {
        if (message != null) {
            Bukkit.getConsoleSender().sendMessage(getPrefix() + ChatColor.RED + message);
        }
        if (throwable != null) {
            throwable.printStackTrace();
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 把 {@code &} 无条件替换为 {@code §}。
     *
     * <p>注意：这里不做颜色代码合法性判断，文件中出现任何 {@code &}（包括普通文本里的）
     * 都会被替换掉，因此语言文件中请勿使用裸的 {@code &}。
     */
    public static String color(String text) {
        return text == null ? "" : text.replace('&', ChatColor.COLOR_CHAR);
    }

    private static String applyReplacements(String text, String... replacements) {
        if (replacements == null || replacements.length == 0) {
            return text;
        }
        String result = text;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            String key = replacements[i];
            if (key == null) {
                continue;
            }
            result = result.replace("%" + key + "%", String.valueOf(replacements[i + 1]));
        }
        return result;
    }

    /**
     * 递归转换整份语言文件里的所有字符串（含列表元素）。
     */
    private static void colorize(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);

            if (value instanceof ConfigurationSection) {
                colorize((ConfigurationSection) value);
                continue;
            }

            if (value instanceof String) {
                String original = (String) value;
                String translated = color(original);
                if (!original.equals(translated)) {
                    section.set(key, translated);
                }
                continue;
            }

            if (value instanceof List) {
                List<?> list = (List<?>) value;
                List<Object> translated = new ArrayList<>(list.size());
                boolean changed = false;
                for (Object element : list) {
                    if (element instanceof String) {
                        String converted = color((String) element);
                        changed |= !converted.equals(element);
                        translated.add(converted);
                    } else {
                        translated.add(element);
                    }
                }
                if (changed) {
                    section.set(key, translated);
                }
            }
        }
    }
}
