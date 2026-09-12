package dev.liquidpanel.panels.http;

import com.google.gson.JsonObject;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import dev.liquidpanel.utils.TaskUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 网页面板的控制台命令接口。
 *
 * <h2>安全说明</h2>
 * <p>这个接口等同于把 RCON 开到了网页上，拿到会话就等于拿到完整服务端控制权。
 * 因此：</p>
 * <ul>
 *     <li>必须已登录，且走完整的同源校验（Host 白名单 + Origin，与其它接口一致）；</li>
 *     <li>命令长度设上限，避免有人塞进一个巨大的字符串；</li>
 *     <li><b>执行前先写一条审计日志</b>，把执行者与命令原文打进控制台 ——
 *         这条也会出现在面板自己的日志卡片里，事后可追溯。</li>
 * </ul>
 */
public final class CommandHandler {

    /** 命令长度上限 */
    private static final int MAX_LENGTH = 256;

    private final SessionManager sessionManager;
    private final HostValidator hostValidator;
    private final JavaPlugin plugin;

    public CommandHandler(SessionManager sessionManager, HostValidator hostValidator, JavaPlugin plugin) {
        this.sessionManager = sessionManager;
        this.hostValidator = hostValidator;
        this.plugin = plugin;
    }

    /**
     * POST /api/console
     */
    public void execute(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.POST)) {
            return;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return;
        }

        PanelSession session = HttpUtil.requireSession(exchange, sessionManager);
        if (session == null) {
            return;
        }

        JsonObject body;
        try {
            body = JsonUtil.parseObject(HttpUtil.readBody(exchange));
        } catch (HttpUtil.BodyTooLargeException e) {
            HttpUtil.sendError(exchange, 413, "请求体过大");
            return;
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "请求内容不合法");
            return;
        }

        String command = JsonUtil.optString(body, "command", "").trim();
        // 控制台命令不带斜杠，但玩家习惯打 /，这里替他们去掉
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }

        if (command.isEmpty()) {
            HttpUtil.sendError(exchange, 400, "请输入要执行的命令");
            return;
        }
        if (command.length() > MAX_LENGTH) {
            HttpUtil.sendError(exchange, 400, "命令过长，上限 " + MAX_LENGTH + " 个字符");
            return;
        }

        // 审计：先记录再执行，顺序不能反
        MessagesManager.logRaw("§7[面板] §f" + session.getUsername() + " §7执行了命令: §f/" + command);

        // dispatchCommand 必须在主线程；返回 false 表示服务端不认识这条命令
        String target = command;
        Boolean dispatched = TaskUtil.awaitSync(
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), target), Boolean.FALSE);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("command", command);
        data.put("dispatched", Boolean.TRUE.equals(dispatched));
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
    }
}
