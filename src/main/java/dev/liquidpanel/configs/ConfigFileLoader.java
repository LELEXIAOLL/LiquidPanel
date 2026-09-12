package dev.liquidpanel.configs;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * 通用 YAML 文件读取器。
 *
 * <p>负责四件事：
 * <ol>
 *     <li>文件不存在时释放 jar 内的默认文件；</li>
 *     <li>读取磁盘上的文件；</li>
 *     <li>以 jar 内的默认文件为模板，逐项校验（缺失 / 类型错误），并把问题配置项打印到控制台；</li>
 *     <li>校验不通过时，把原文件加上 {@code .bak} 后缀备份，重新释放默认文件并读取。</li>
 * </ol>
 *
 * <p>本类永远不会返回 {@code null}，调用方无需做空判断。
 */
public class ConfigFileLoader {

    /** 备份文件后缀 */
    private static final String BACKUP_SUFFIX = ".bak";

    /** 单次最多逐条打印多少处问题，避免文件版本过旧时刷屏 */
    private static final int MAX_REPORTED_PROBLEMS = 10;

    protected final JavaPlugin plugin;

    private final String fileName;
    private final String resourceName;

    /**
     * 磁盘文件名与 jar 内默认资源名相同时使用。
     */
    public ConfigFileLoader(JavaPlugin plugin, String fileName) {
        this(plugin, fileName, fileName);
    }

    /**
     * @param plugin       插件实例
     * @param fileName     磁盘上的文件名，例如 config.yml
     * @param resourceName jar 内作为模板的默认资源名，通常与 fileName 相同
     */
    public ConfigFileLoader(JavaPlugin plugin, String fileName, String resourceName) {
        this.plugin = plugin;
        this.fileName = fileName;
        this.resourceName = resourceName;
    }

    public String getFileName() {
        return fileName;
    }

    /**
     * 读取并校验文件。任何异常都会走"备份 + 释放默认文件"流程。
     */
    public YamlConfiguration load() {
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            saveDefault(file, false);
            log(Level.INFO, "未找到 " + fileName + "，已释放默认文件");
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (Exception e) {
            log(Level.SEVERE, fileName + " 无法解析: " + e.getMessage());
            return repair(file, "文件格式错误");
        }

        YamlConfiguration defaults = readDefault();
        if (defaults == null) {
            // jar 内没有对应的默认文件，没有模板可比对，跳过校验
            return config;
        }

        List<String> problems = new ArrayList<>();
        validate(defaults, config, "", problems);
        if (problems.isEmpty()) {
            return config;
        }

        for (int i = 0; i < problems.size() && i < MAX_REPORTED_PROBLEMS; i++) {
            log(Level.SEVERE, fileName + " -> " + problems.get(i));
        }
        if (problems.size() > MAX_REPORTED_PROBLEMS) {
            log(Level.SEVERE, fileName + " -> 其余 " + (problems.size() - MAX_REPORTED_PROBLEMS) + " 处不再逐条列出");
        }
        return repair(file, "共发现 " + problems.size() + " 处异常配置项");
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    /**
     * 以 defaults 为模板递归校验 target，把问题描述收集到 problems 中。
     * defaults 中多出的配置项一律以 defaults 为准；target 中多出的配置项不会被判定为错误。
     */
    private void validate(ConfigurationSection defaults, ConfigurationSection target, String parent, List<String> problems) {
        for (String key : defaults.getKeys(false)) {
            // 查值一律用相对当前的 key：
            // ConfigurationSection 的取值方法是相对于自身解析的，
            // 递归进来后 target 已经是子节点，再拿 "panel.enabled" 这种全路径去查会永远查不到，
            // 结果就是嵌套的配置项被整片误报为缺失。
            String path = parent.isEmpty() ? key : parent + "." + key;
            Object expected = defaults.get(key);

            if (expected instanceof ConfigurationSection) {
                if (target.isConfigurationSection(key)) {
                    validate((ConfigurationSection) expected, target.getConfigurationSection(key), path, problems);
                } else if (target.contains(key)) {
                    problems.add("配置项 " + path + " 类型错误，需要 节点，实际为 " + typeName(target.get(key)));
                } else {
                    problems.add("缺少配置项 " + path + "（节点）");
                }
                continue;
            }

            if (!target.contains(key)) {
                problems.add("缺少配置项 " + path + "，应为 " + typeName(expected));
                continue;
            }

            Object actual = target.get(key);
            if (!typeName(expected).equals(typeName(actual))) {
                problems.add("配置项 " + path + " 类型错误，需要 " + typeName(expected) + "，实际为 " + typeName(actual));
            }
        }
    }

    /**
     * 取配置项的大类名称。只比较大类，避免 1 与 1L、1 与 1.0 这类底层差异被误判。
     */
    private String typeName(Object value) {
        if (value == null) return "空";
        if (value instanceof ConfigurationSection || value instanceof Map) return "节点";
        if (value instanceof List) return "列表";
        if (value instanceof Number) return "数字";
        if (value instanceof Boolean) return "布尔值";
        if (value instanceof String) return "文本";
        return value.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------
    // 备份与释放默认文件
    // ------------------------------------------------------------------

    /**
     * 把原文件备份为 {@code 文件名.bak}，再重新释放默认文件并读取。
     */
    private YamlConfiguration repair(File file, String reason) {
        log(Level.SEVERE, reason + "，正在备份为 " + fileName + BACKUP_SUFFIX + " 并释放默认文件");

        File backup = new File(file.getParentFile(), fileName + BACKUP_SUFFIX);
        try {
            if (backup.exists() && !backup.delete()) {
                log(Level.WARNING, "无法删除旧备份 " + backup.getName());
            }
            if (file.exists()) {
                Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (!file.delete()) {
                    log(Level.WARNING, "无法删除异常的文件 " + fileName);
                }
            }
        } catch (IOException e) {
            log(Level.SEVERE, "备份 " + fileName + " 失败: " + e.getMessage());
        }

        saveDefault(file, true);

        YamlConfiguration fresh = new YamlConfiguration();
        try {
            fresh.load(file);
            log(Level.INFO, "已重新释放并读取默认文件 " + fileName);
        } catch (Exception e) {
            log(Level.SEVERE, "默认文件 " + fileName + " 读取失败: " + e.getMessage());
        }
        return fresh;
    }

    /**
     * 从 jar 内释放默认文件。
     *
     * @param replace true 表示覆盖已存在的文件
     */
    protected void saveDefault(File file, boolean replace) {
        if (file.exists() && !replace) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log(Level.SEVERE, "无法创建目录 " + parent.getAbsolutePath());
            return;
        }
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                log(Level.SEVERE, "插件内置资源 " + resourceName + " 不存在，无法释放默认文件");
                return;
            }
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log(Level.SEVERE, "释放默认文件 " + fileName + " 失败: " + e.getMessage());
        }
    }

    /**
     * 读取 jar 内的默认文件，作为校验模板。
     */
    protected YamlConfiguration readDefault() {
        try (InputStream in = plugin.getResource(resourceName)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log(Level.WARNING, "读取内置默认文件 " + resourceName + " 失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 控制台输出。统一带上语言文件中的 prefix。
     */
    protected void log(Level level, String message) {
        ChatColor color;
        if (level == Level.SEVERE) {
            color = ChatColor.RED;
        } else if (level == Level.WARNING) {
            color = ChatColor.YELLOW;
        } else {
            color = ChatColor.GRAY;
        }
        Bukkit.getConsoleSender().sendMessage(MessagesManager.getPrefix() + color + message);
    }
}
