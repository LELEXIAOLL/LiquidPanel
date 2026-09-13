package dev.liquidpanel.panels.http;

import com.google.gson.JsonObject;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.economy.EconomyService;
import dev.liquidpanel.panels.players.BanDuration;
import dev.liquidpanel.panels.players.BanService;
import dev.liquidpanel.panels.players.PlayerFilter;
import dev.liquidpanel.panels.players.PlayerService;
import dev.liquidpanel.panels.players.SkinService;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家管理接口。
 *
 * <p>列表支持按 全部 / 在线 / 离线 / 已封禁 筛选，分页放在前端做。
 * 踢出 / 封禁 / 解封 / 传送 / 执行命令都是高影响操作，一律写审计日志。
 */
public final class PlayerHandler {

    private final SessionManager sessionManager;
    private final HostValidator hostValidator;
    private final PlayerService players;
    private final SkinService skins;
    private final BanService bans;
    private final EconomyService economy;

    public PlayerHandler(SessionManager sessionManager,
                         HostValidator hostValidator,
                         PlayerService players,
                         SkinService skins,
                         BanService bans,
                         EconomyService economy) {
        this.sessionManager = sessionManager;
        this.hostValidator = hostValidator;
        this.players = players;
        this.skins = skins;
        this.bans = bans;
        this.economy = economy;
    }

    // ------------------------------------------------------------------
    // GET /api/players?page=N
    // ------------------------------------------------------------------

    /**
     * 按筛选条件返回玩家。
     *
     * <p>不在服务端分页：分页放前端做，翻页时不用重新请求。
     *
     * <p>调用方要注意频率 —— 选「在线」时前端其实不吃这份数据（它走 WebSocket
     * 的实时推送），会来调这里的都是低频的筛选切换。
     */
    public void list(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.GET)) {
            return;
        }

        PlayerFilter filter = PlayerFilter.parse(query(exchange, "filter"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("players", players.list(filter));
        data.put("filter", filter.id());
        // 可选值由后端给，前端照着渲染下拉框
        data.put("filters", PlayerFilter.ids());
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
    }

    // ------------------------------------------------------------------
    // GET /api/players/skin?name=xxx
    // ------------------------------------------------------------------

    /**
     * 默认头像（Steve）。
     *
     * <p>贴图本身由浏览器直接去 Mojang 取，这个接口只负责「没有皮肤」时的兜底，
     * 以及贴图加载失败时前端 onerror 的退路。
     */
    public void skin(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.GET)) {
            return;
        }

        byte[] bytes = skins.defaultSkin();
        exchange.setStatusCode(200);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/png");
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "public, max-age=86400");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(bytes.length));
        exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION, "inline");
        exchange.getResponseSender().send(ByteBuffer.wrap(bytes));
    }

    // ------------------------------------------------------------------
    // POST /api/players/action
    // ------------------------------------------------------------------

    public void action(HttpServerExchange exchange) {
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

        String action = JsonUtil.optString(body, "action", "");
        String name = JsonUtil.optString(body, "name", "");
        String value = JsonUtil.optString(body, "value", "");

        if (name.isEmpty()) {
            HttpUtil.sendError(exchange, 400, "缺少玩家名");
            return;
        }

        try {
            switch (action) {
                case "kick" -> players.kick(name, value);
                case "ban" -> bans.ban(name, value,
                        JsonUtil.optBoolean(body, "temporary", false),
                        readDuration(body),
                        currentUser(exchange));
                case "unban" -> bans.unban(name);
                case "give" -> require(economy.deposit(name, readAmount(value)));
                case "take" -> require(economy.withdraw(name, readAmount(value)));
                case "setbalance" -> require(economy.set(name, readAmount(value)));
                case "teleport" -> players.teleport(name, value);
                case "command" -> players.runAs(name, value);
                default -> {
                    HttpUtil.sendError(exchange, 400, "未知操作: " + action);
                    return;
                }
            }
        } catch (PlayerService.PlayerActionException e) {
            HttpUtil.sendError(exchange, 400, e.getMessage());
            return;
        }

        String description = describe(action, value, body);

        // 这些都是高影响操作，留一条可追溯的记录
        MessagesManager.logRaw("§7[面板] §f" + currentUser(exchange) + " §7对玩家 §f" + name
                + " §7执行了 §f" + description);

        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已对 " + name + " 执行：" + description, null));
    }

    /**
     * 解析金额。
     *
     * <p>前端已经把它限制成数字输入框了，但这个接口是公开的，
     * 不能假设过来的东西一定合法。
     */
    private double readAmount(String value) {
        try {
            double amount = Double.parseDouble(value.trim());
            if (!Double.isFinite(amount) || amount < 0D) {
                throw new NumberFormatException();
            }
            return amount;
        } catch (NumberFormatException e) {
            throw new PlayerService.PlayerActionException("金额需要是一个不小于 0 的数字");
        }
    }

    /**
     * 经济操作失败就抛出去，由上面统一转成带原因的 400。
     */
    private void require(EconomyService.Result result) {
        if (!result.success()) {
            throw new PlayerService.PlayerActionException(result.error());
        }
    }

    /**
     * 读出临时封禁的时长。七个字段哪个没传就按 0 算，
     * 是不是临时封禁由 {@code temporary} 决定，与这里的值无关。
     */
    private BanDuration readDuration(JsonObject body) {
        return BanDuration.of(
                JsonUtil.optInt(body, "years", 0),
                JsonUtil.optInt(body, "months", 0),
                JsonUtil.optInt(body, "weeks", 0),
                JsonUtil.optInt(body, "days", 0),
                JsonUtil.optInt(body, "hours", 0),
                JsonUtil.optInt(body, "minutes", 0),
                JsonUtil.optInt(body, "seconds", 0));
    }

    private String describe(String action, String value, JsonObject body) {
        return switch (action) {
            case "kick" -> "踢出" + (value.isEmpty() ? "" : "（原因：" + value + "）");
            case "ban" -> describeBan(value, body);
            case "unban" -> "解除封禁";
            case "give" -> "存入 " + value;
            case "take" -> "扣除 " + value;
            case "setbalance" -> "把余额设为 " + value;
            case "teleport" -> "传送到 " + value;
            case "command" -> "以最高权限、玩家身份执行 /" + value;
            default -> action;
        };
    }

    private String describeBan(String reason, JsonObject body) {
        StringBuilder text = new StringBuilder("封禁");
        if (JsonUtil.optBoolean(body, "temporary", false)) {
            text.append("（临时 ").append(readDuration(body).format()).append("）");
        } else {
            text.append("（永久）");
        }
        if (!reason.isEmpty()) {
            text.append("（原因：").append(reason).append("）");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

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

    private String currentUser(HttpServerExchange exchange) {
        PanelSession session = HttpUtil.resolveSession(exchange, sessionManager);
        return session == null ? "未知" : session.getUsername();
    }

    private String query(HttpServerExchange exchange, String key) {
        return HttpUtil.parseQuery(exchange.getQueryString()).getOrDefault(key, "");
    }

    private int parsePage(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
