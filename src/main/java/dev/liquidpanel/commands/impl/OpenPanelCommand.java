package dev.liquidpanel.commands.impl;

import dev.liquidpanel.LiquidPanel;
import dev.liquidpanel.commands.PermissionManager;
import dev.liquidpanel.commands.SubCommand;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.PanelManager;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * /liquidpanel openpanel —— 在服务端所在机器的浏览器里打开网页面板。
 *
 * <p>仅控制台可执行：打开浏览器依赖服务端机器的桌面环境，
 * 玩家客户端执行既没有意义，也不该让玩家碰到这个入口。
 */
public class OpenPanelCommand implements SubCommand {

    private static final List<String> ALIASES = Arrays.asList("open", "panel");

    @Override
    public String getName() {
        return "openpanel";
    }

    @Override
    public List<String> getAliases() {
        return ALIASES;
    }

    @Override
    public String getPermission() {
        return PermissionManager.COMMAND_OPENPANEL;
    }

    @Override
    public String getUsage() {
        return "openpanel";
    }

    @Override
    public String getDescriptionKey() {
        return "help.description.openpanel";
    }

    @Override
    public boolean onCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            MessagesManager.send(sender, "panel.command.console_only");
            return true;
        }

        PanelManager panelManager = LiquidPanel.getInstance().getPanelManager();
        if (panelManager == null) {
            MessagesManager.send(sender, "panel.server.not_running");
            return true;
        }

        panelManager.openInBrowser(sender);
        return true;
    }
}
