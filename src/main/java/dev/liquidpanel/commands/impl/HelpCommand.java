package dev.liquidpanel.commands.impl;

import dev.liquidpanel.commands.CommandsManager;
import dev.liquidpanel.commands.PermissionManager;
import dev.liquidpanel.commands.SubCommand;
import dev.liquidpanel.configs.MessagesManager;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/**
 * /liquidpanel help —— 输出帮助列表。
 *
 * <p>只列出调用者有权限使用的子命令，文案全部取自语言文件。
 */
public class HelpCommand implements SubCommand {

    /** 描述文本在语言文件中的路径前缀 */
    private static final String DESCRIPTION_PREFIX = "help.description.";

    private final CommandsManager commandsManager;

    public HelpCommand(CommandsManager commandsManager) {
        this.commandsManager = commandsManager;
    }

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("?");
    }

    @Override
    public String getPermission() {
        return PermissionManager.COMMAND_HELP;
    }

    @Override
    public String getUsage() {
        return "help";
    }

    @Override
    public String getDescriptionKey() {
        return DESCRIPTION_PREFIX + "help";
    }

    @Override
    public boolean onCommand(CommandSender sender, String[] args) {
        List<SubCommand> available = commandsManager.getAvailable(sender);

        MessagesManager.send(sender, "help.header");
        for (SubCommand command : available) {
            MessagesManager.send(sender, "help.entry",
                    "command", CommandsManager.ROOT_COMMAND,
                    "usage", command.getUsage(),
                    "description", MessagesManager.getMessage(command.getDescriptionKey()));
        }
        MessagesManager.send(sender, "help.footer", "amount", String.valueOf(available.size()));
        return true;
    }
}
