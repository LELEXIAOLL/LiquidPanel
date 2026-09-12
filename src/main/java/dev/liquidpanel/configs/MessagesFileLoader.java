package dev.liquidpanel.configs;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.util.Locale;

/**
 * 语言文件读取器。
 *
 * <p>文件名为 {@code messages_<language>.yml}，其中 {@code language} 取自 config.yml 的
 * {@code language} 配置项（例如 zhcn -> messages_zhcn.yml）。
 * 若配置的语言在插件内置资源里不存在，会自动回退到 {@link #DEFAULT_LANGUAGE}，
 * 避免因为写错一个语言代码导致整个插件的提示全部失效。
 *
 * <p>校验、备份、释放默认文件的逻辑全部复用 {@link ConfigFileLoader}。
 */
public class MessagesFileLoader extends ConfigFileLoader {

    /** 兜底语言 */
    public static final String DEFAULT_LANGUAGE = "zhcn";

    /** 语言文件前缀 */
    public static final String FILE_PREFIX = "messages_";

    public MessagesFileLoader(JavaPlugin plugin, String language) {
        super(plugin, resolveFileName(plugin, language));
    }

    /**
     * 根据语言代码解析出实际要使用的语言文件名。
     */
    private static String resolveFileName(JavaPlugin plugin, String language) {
        String normalized = (language == null || language.isBlank())
                ? DEFAULT_LANGUAGE
                : language.trim().toLowerCase(Locale.ROOT);

        String candidate = FILE_PREFIX + normalized + ".yml";
        if (resourceExists(plugin, candidate)) {
            return candidate;
        }

        if (!DEFAULT_LANGUAGE.equals(normalized)) {
            plugin.getLogger().warning("未找到内置语言文件 " + candidate + "，已回退至 " + FILE_PREFIX + DEFAULT_LANGUAGE + ".yml");
        }
        return FILE_PREFIX + DEFAULT_LANGUAGE + ".yml";
    }

    private static boolean resourceExists(JavaPlugin plugin, String resource) {
        try (InputStream in = plugin.getResource(resource)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }
}
