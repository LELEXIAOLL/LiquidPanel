package dev.liquidpanel.commands;

import dev.liquidpanel.commands.impl.HelpCommand;
import dev.liquidpanel.commands.impl.OpenPanelCommand;
import dev.liquidpanel.commands.impl.ReloadCommand;
import dev.liquidpanel.configs.MessagesManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 命令管理器。
 *
 * <p>职责：
 * <ul>
 *     <li>注册 {@code impl} 包下的子命令（{@link #register(SubCommand)}）；</li>
 *     <li>把根命令 /liquidpanel（缩写 /ldp）的 executor 与 tab completer 挂上；</li>
 *     <li>把玩家输入的子命令名分发到对应的 {@link SubCommand}，并统一做权限校验；</li>
 * </ul>
 *
 * <p>新增命令时只需要在 {@code impl} 包下实现 {@link SubCommand}，
 * 然后在 {@link #registerDefaults()} 里加一行注册即可。
 */
public final class CommandsManager {

    /** 根命令，对应 plugin.yml 中的 commands 节点 */
    public static final String ROOT_COMMAND = "liquidpanel";

    /** 根命令缩写 */
    public static final String ROOT_ALIAS = "ldp";

    private final JavaPlugin plugin;

    /** 保持注册顺序，帮助列表按此顺序输出 */
    private final List<SubCommand> commands = new ArrayList<>();

    /** 名称/别名 -> 子命令 */
    private final Map<String, SubCommand> index = new LinkedHashMap<>();

    public CommandsManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 注册一个子命令，同时登记它的全部别名。
     */
    public void register(SubCommand command) {
        if (command == null || command.getName() == null) {
            return;
        }
        commands.add(command);
        index.put(normalize(command.getName()), command);
        for (String alias : command.getAliases()) {
            if (alias != null && !alias.isEmpty()) {
                index.put(normalize(alias), command);
            }
        }
    }

    /**
     * 注册本插件自带的子命令。新增命令在这里加一行即可。
     */
    public void registerDefaults() {
        register(new HelpCommand(this));
        register(new ReloadCommand());
        register(new OpenPanelCommand());
    }

    /**
     * 把根命令的 executor 与 tab completer 挂到本管理器上。
     */
    public void setup() {
        PluginCommand root = plugin.getCommand(ROOT_COMMAND);
        if (root == null) {
            MessagesManager.log("command.not_registered", "command", ROOT_COMMAND);
            return;
        }
        root.setExecutor(this::handleCommand);
        root.setTabCompleter(this::handleTabComplete);
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    /**
     * 按名称或别名查找子命令。
     */
    public SubCommand get(String name) {
        return name == null ? null : index.get(normalize(name));
    }

    /**
     * 全部已注册的子命令（含调用者无权限的）。
     */
    public List<SubCommand> getCommands() {
        return Collections.unmodifiableList(commands);
    }

    /**
     * 调用者有权限使用的子命令，用于帮助列表。
     */
    public List<SubCommand> getAvailable(CommandSender sender) {
        List<SubCommand> available = new ArrayList<>();
        for (SubCommand command : commands) {
            if (PermissionManager.canUse(sender, command)) {
                available.add(command);
            }
        }
        return available;
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    private boolean handleCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            // 不带参数时等同于 /liquidpanel help
            SubCommand help = get("help");
            return help != null && dispatch(sender, help, new String[0]);
        }

        SubCommand target = get(args[0]);
        if (target == null) {
            MessagesManager.send(sender, "general.unknown_command", "command", args[0]);
            return true;
        }
        return dispatch(sender, target, Arrays.copyOfRange(args, 1, args.length));
    }

    private boolean dispatch(CommandSender sender, SubCommand command, String[] args) {
        if (!PermissionManager.canUse(sender, command)) {
            MessagesManager.send(sender, "general.no_permission");
            return true;
        }
        try {
            return command.onCommand(sender, args);
        } catch (Exception e) {
            MessagesManager.log("general.command_error", "command", command.getName(), "error", String.valueOf(e));
            plugin.getLogger().severe("执行子命令 " + command.getName() + " 时发生异常");
            e.printStackTrace();
            return true;
        }
    }

    private List<String> handleTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 0) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String prefix = normalize(args[0]);
            List<String> result = new ArrayList<>();
            for (SubCommand sub : commands) {
                if (!PermissionManager.canUse(sender, sub)) {
                    continue;
                }
                addIfMatches(result, sub.getName(), prefix);
                for (String subAlias : sub.getAliases()) {
                    addIfMatches(result, subAlias, prefix);
                }
            }
            Collections.sort(result);
            return result;
        }

        SubCommand target = get(args[0]);
        if (target == null || !PermissionManager.canUse(sender, target)) {
            return Collections.emptyList();
        }
        return target.onTabComplete(sender, Arrays.copyOfRange(args, 1, args.length));
    }

    private void addIfMatches(List<String> result, String candidate, String prefix) {
        if (candidate == null || candidate.isEmpty()) {
            return;
        }
        String normalized = normalize(candidate);
        if (normalized.startsWith(prefix) && !result.contains(normalized)) {
            result.add(normalized);
        }
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
