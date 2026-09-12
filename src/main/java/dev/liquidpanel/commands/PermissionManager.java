package dev.liquidpanel.commands;

import org.bukkit.command.CommandSender;

/**
 * 权限管理器。
 *
 * <p>本插件用到的所有权限节点都在这里集中定义，其他类不要自己拼字符串，
 * 一律通过下面的常量与判断方法使用，避免出现写错节点的低级问题。
 */
public final class PermissionManager {

    /** 所有权限的根节点 */
    public static final String ROOT = "liquidpanel";

    /** /liquidpanel help */
    public static final String COMMAND_HELP = ROOT + ".command.help";

    /** /liquidpanel reload */
    public static final String COMMAND_RELOAD = ROOT + ".command.reload";

    /** /liquidpanel openpanel */
    public static final String COMMAND_OPENPANEL = ROOT + ".command.openpanel";

    /** 管理权限，拥有它等同于拥有全部子命令权限 */
    public static final String ADMIN = ROOT + ".admin";

    private PermissionManager() {
    }

    /**
     * 判断发送者是否拥有某个权限节点。节点为空表示不需要权限。
     */
    public static boolean has(CommandSender sender, String permission) {
        if (permission == null || permission.isEmpty()) {
            return true;
        }
        return sender != null && sender.hasPermission(permission);
    }

    /**
     * 是否为管理员（拥有 {@link #ADMIN}）。
     * 控制台默认拥有全部权限，所以这里控制台同样返回 true。
     */
    public static boolean isAdmin(CommandSender sender) {
        return has(sender, ADMIN);
    }

    /**
     * 判断发送者能否使用某个子命令。
     */
    public static boolean canUse(CommandSender sender, SubCommand command) {
        if (command == null) {
            return false;
        }
        return isAdmin(sender) || has(sender, command.getPermission());
    }
}
