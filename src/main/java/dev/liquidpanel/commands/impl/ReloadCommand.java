package dev.liquidpanel.commands.impl;

import dev.liquidpanel.LiquidPanel;
import dev.liquidpanel.commands.PermissionManager;
import dev.liquidpanel.commands.SubCommand;
import dev.liquidpanel.configs.ConfigManager;
import dev.liquidpanel.configs.MessagesManager;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * /liquidpanel reload —— 重新载入并缓存配置文件与语言文件。
 *
 * <p>加载顺序必须是先配置后语言：语言文件名由 config.yml 的 {@code language} 决定。
 * 因此本命令执行完后，前缀等文案会立刻变成新语言文件里的内容。
 */
public class ReloadCommand implements SubCommand {

    private static final List<String> ALIASES = Arrays.asList("rl", "reloadconfig");

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public List<String> getAliases() {
        return ALIASES;
    }

    @Override
    public String getPermission() {
        return PermissionManager.COMMAND_RELOAD;
    }

    @Override
    public String getUsage() {
        return "reload";
    }

    @Override
    public String getDescriptionKey() {
        return "help.description.reload";
    }

    @Override
    public boolean onCommand(CommandSender sender, String[] args) {
        long start = System.currentTimeMillis();

        // 这条用的是重载前的旧前缀
        MessagesManager.send(sender, "reload.start");

        try {
            ConfigManager.reload();
            MessagesManager.reload();
            // 面板端口等信息也在 config.yml 里，配置重载后同步生效
            LiquidPanel.getInstance().getPanelManager().reload();
        } catch (Exception e) {
            MessagesManager.send(sender, "reload.failed");
            MessagesManager.log("reload.failed_console", "error", String.valueOf(e.getMessage()));
            MessagesManager.logThrowable(null, e);
            return true;
        }

        long time = System.currentTimeMillis() - start;

        // 重载完成后 getPrefix() 读到的已经是新的前缀了
        MessagesManager.send(sender, "reload.success", "time", String.valueOf(time));
        MessagesManager.log("reload.success_console",
                "player", sender.getName(),
                "time", String.valueOf(time));
        return true;
    }
}
