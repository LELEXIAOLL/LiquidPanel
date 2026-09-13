package dev.liquidpanel.panels.http;

import com.google.gson.JsonObject;
import dev.liquidpanel.panels.economy.EconomyService;
import dev.liquidpanel.panels.players.BanMethod;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.panels.settings.PanelConfigManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面板运行时设置接口，读写 {@code panelconfig.json}。
 *
 * <p>与账号密码分开：那一套走 {@code /api/auth/credentials}，改动会让全部会话失效；
 * 这里只是显示偏好，改完立刻生效、立刻落盘，不影响登录状态。
 */
public final class SettingsHandler {

    private final SessionManager sessionManager;
    private final HostValidator hostValidator;
    private final PanelConfigManager config;
    private final EconomyService economy;

    public SettingsHandler(SessionManager sessionManager,
                           HostValidator hostValidator,
                           PanelConfigManager config,
                           EconomyService economy) {
        this.sessionManager = sessionManager;
        this.hostValidator = hostValidator;
        this.config = config;
        this.economy = economy;
    }

    /**
     * 按请求方法分发：GET 读、POST 写。
     */
    public void handle(HttpServerExchange exchange) {
        if (Methods.POST.equals(exchange.getRequestMethod())) {
            write(exchange);
            return;
        }
        read(exchange);
    }

    private void read(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.GET)) {
            return;
        }
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(snapshot()));
    }

    /**
     * 保存设置，改完立刻写盘。
     *
     * <p>是<b>部分更新</b>：请求里没出现的字段沿用当前值。
     * 前端按「哪个控件改了就把哪几个字段一起发过来」来调用，
     * 所以这里不能要求每次都带全。
     *
     * <p>所有字段先校验、再统一落盘一次 —— {@code update} 每次都会重写整个文件，
     * 逐字段调用会写好几遍。
     */
    private void write(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.POST)) {
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
        if (body == null) {
            HttpUtil.sendError(exchange, 400, "请求内容不合法");
            return;
        }

        PanelConfigManager.PanelConfig current = config.get();

        int pageSize = JsonUtil.optInt(body, "playersPerPage", current.playersPerPage);
        String methodId = JsonUtil.optString(body, "banMethod", current.banMethod);
        String commandTemp = JsonUtil.optString(body, "banCommandTemp", current.banCommandTemp);
        String commandPerm = JsonUtil.optString(body, "banCommandPerm", current.banCommandPerm);
        String commandUnban = JsonUtil.optString(body, "banCommandUnban", current.banCommandUnban);
        boolean vaultEnabled = JsonUtil.optBoolean(body, "vaultEnabled", current.vaultEnabled);

        if (pageSize < PanelConfigManager.PanelConfig.minPageSize()
                || pageSize > PanelConfigManager.PanelConfig.maxPageSize()) {
            HttpUtil.sendError(exchange, 400, "每页玩家数需在 "
                    + PanelConfigManager.PanelConfig.minPageSize() + " - "
                    + PanelConfigManager.PanelConfig.maxPageSize() + " 之间");
            return;
        }

        BanMethod method = BanMethod.parse(methodId);
        if (method == null) {
            HttpUtil.sendError(exchange, 400, "未知的封禁方式：" + methodId);
            return;
        }

        // 自定义方式下命令里必须有 %player%，否则执行时根本没有作用对象。
        // 这条在保存时就挡住，免得等到真去封人时才发现封了个寂寞。
        if (method == BanMethod.CUSTOM
                && (!hasPlayerPlaceholder(commandTemp)
                || !hasPlayerPlaceholder(commandPerm)
                || !hasPlayerPlaceholder(commandUnban))) {
            HttpUtil.sendError(exchange, 400,
                    "自定义命令必须包含 " + PanelConfigManager.PLAYER_PLACEHOLDER + " 占位符");
            return;
        }

        boolean saved = config.update(settings -> {
            settings.playersPerPage = pageSize;
            settings.banMethod = method.id();
            settings.banCommandTemp = commandTemp;
            settings.banCommandPerm = commandPerm;
            settings.banCommandUnban = commandUnban;
            settings.vaultEnabled = vaultEnabled;
        });

        if (!saved) {
            HttpUtil.sendError(exchange, 500, "写入 panelconfig.json 失败，请查看控制台日志");
            return;
        }

        // 开关刚被改动，让经济模块立刻重新探测一次，不用等惰性重试
        if (vaultEnabled != current.vaultEnabled) {
            economy.refresh();
        }

        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("设置已保存", snapshot()));
    }

    private boolean hasPlayerPlaceholder(String command) {
        return command != null && command.contains(PanelConfigManager.PLAYER_PLACEHOLDER);
    }

    private Map<String, Object> snapshot() {
        PanelConfigManager.PanelConfig settings = config.get();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("playersPerPage", settings.playersPerPage);
        data.put("minPlayersPerPage", PanelConfigManager.PanelConfig.minPageSize());
        data.put("maxPlayersPerPage", PanelConfigManager.PanelConfig.maxPageSize());

        // 可选值由后端给，前端照着渲染下拉框 —— 以后加新方式不用同步改前端
        data.put("banMethod", settings.banMethod);
        data.put("banMethods", BanMethod.ids());
        data.put("banCommandTemp", settings.banCommandTemp);
        data.put("banCommandPerm", settings.banCommandPerm);
        data.put("banCommandUnban", settings.banCommandUnban);
        data.put("playerPlaceholder", PanelConfigManager.PLAYER_PLACEHOLDER);

        // 开关是用来「想不想用」的，available 是「实际能不能用」——
        // 服务端没装 Vault 或没装经济插件时，开关打开也依然是 false，
        // 前端据此提示服主去装，而不是显示一个永远为 0 的余额
        data.put("vaultEnabled", settings.vaultEnabled);
        data.put("vaultAvailable", economy.isAvailable());
        return data;
    }

    private boolean guard(HttpServerExchange exchange, io.undertow.util.HttpString method) {
        if (!HttpUtil.requireMethod(exchange, method)) {
            return false;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return false;
        }
        PanelSession session = HttpUtil.requireSession(exchange, sessionManager);
        return session != null;
    }
}
