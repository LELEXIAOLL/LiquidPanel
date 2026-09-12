package dev.liquidpanel;

import dev.liquidpanel.commands.CommandsManager;
import dev.liquidpanel.configs.ConfigManager;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.PanelManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class LiquidPanel extends JavaPlugin {

    private static LiquidPanel instance;

    private CommandsManager commandsManager;
    private PanelManager panelManager;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 配置文件与语言文件。语言文件名依赖配置里的 language 项，所以配置必须先加载。
        ConfigManager.init(this);
        MessagesManager.init(this);

        // 2. 网页面板。之后再注册命令，保证 /ldp openpanel 一上来就能用。
        panelManager = new PanelManager(this);
        panelManager.init();

        // 3. 注册命令。/liquidpanel 与缩写 /ldp 在 plugin.yml 中定义。
        commandsManager = new CommandsManager(this);
        commandsManager.registerDefaults();
        commandsManager.setup();

        getServer().getConsoleSender().sendMessage(MessagesManager.color(
                MessagesManager.getPrefix() + "&a插件已成功加载"));
    }

    @Override
    public void onDisable() {
        // 先停面板：关闭 HTTP 监听、断开所有 WebSocket、取消推送任务
        if (panelManager != null) {
            panelManager.shutdown();
        }

        getServer().getConsoleSender().sendMessage(MessagesManager.color(
                MessagesManager.getPrefix() + "&c插件已卸载"));
    }

    public static LiquidPanel getInstance() {
        return instance;
    }

    public CommandsManager getCommandsManager() {
        return commandsManager;
    }

    public PanelManager getPanelManager() {
        return panelManager;
    }
}
