package dev.liquidpanel.commands;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * 子命令接口。
 *
 * <p>{@code impl} 包下的每个命令实现本接口，只负责写自己的业务逻辑；
 * 注册、分发、权限校验、Tab 补全入口统一由 {@link CommandsManager} 处理。
 */
public interface SubCommand {

    /**
     * 子命令名称，例如 reload。
     */
    String getName();

    /**
     * 子命令别名。
     */
    default List<String> getAliases() {
        return Collections.emptyList();
    }

    /**
     * 执行本命令所需的权限节点，返回 null 或空串表示所有人可用。
     * 权限节点统一定义在 {@link PermissionManager} 中。
     */
    String getPermission();

    /**
     * 帮助列表中显示的用法部分，例如 reload。
     */
    String getUsage();

    /**
     * 描述文本在语言文件中的路径，例如 help.description.reload。
     * 文案本身放在语言文件里，方便统一维护与翻译。
     */
    String getDescriptionKey();

    /**
     * 执行命令。
     *
     * @param args 已经去掉子命令名之后的参数
     * @return 是否消耗掉本次输入
     */
    boolean onCommand(CommandSender sender, String[] args);

    /**
     * Tab 补全。
     *
     * @param args 已经去掉子命令名之后的参数
     */
    default List<String> onTabComplete(CommandSender sender, String[] args) {
        return Collections.emptyList();
    }
}
