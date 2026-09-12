package dev.liquidpanel.panels.ws;

import com.google.gson.JsonObject;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.websockets.WebSocketConnectionCallback;
import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;
import io.undertow.websockets.spi.WebSocketHttpExchange;
import org.bukkit.Bukkit;

import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WebSocket 接入点（/ws）。
 *
 * <p>握手阶段就完成身份校验：没登录的连接直接关掉，不会进入连接池，
 * 因此不可能靠伪造一个 WebSocket 连接来偷听推送。
 */
public final class PanelWebSocketHandler implements WebSocketConnectionCallback {

    /** 未登录时的关闭原因 */
    private static final String UNAUTHORIZED = "未登录或登录状态已过期";

    /** 会话 Cookie 名，与 HTTP 侧保持一致 */
    private static final String SESSION_COOKIE = "liquidpanel_session";

    private final SessionManager sessionManager;
    private final WebSocketHub hub;
    private final HostValidator hostValidator;

    public PanelWebSocketHandler(SessionManager sessionManager, WebSocketHub hub, HostValidator hostValidator) {
        this.sessionManager = sessionManager;
        this.hub = hub;
        this.hostValidator = hostValidator;
    }

    @Override
    public void onConnect(WebSocketHttpExchange exchange, WebSocketChannel channel) {
        // 与 HTTP 侧同一道 Host 白名单。会话 Cookie 本身是 host-only 的，
        // DNS Rebinding 拿不到它，这里是同一类攻击的纵深防御。
        if (hostValidator == null || !hostValidator.isAllowed(exchange.getRequestHeader("Host"))) {
            WebSockets.sendClose(CloseMessage.MSG_VIOLATES_POLICY, UNAUTHORIZED, channel, null);
            return;
        }

        PanelSession session = sessionManager.validate(extractToken(exchange), resolveIp(channel));
        if (session == null) {
            // 握手已经完成，只能发一个 1008 关闭帧把连接踢掉
            WebSockets.sendClose(CloseMessage.MSG_VIOLATES_POLICY, UNAUTHORIZED, channel, null);
            return;
        }

        PanelConnection connection = new PanelConnection(channel, session.getUsername());
        hub.add(connection);

        channel.getReceiveSetter().set(new AbstractReceiveListener() {
            @Override
            protected void onFullTextMessage(WebSocketChannel webSocketChannel, BufferedTextMessage message) {
                handleMessage(connection, message.getData());
            }

            @Override
            protected void onCloseMessage(CloseMessage closeMessage, WebSocketChannel webSocketChannel) {
                hub.remove(connection);
            }

            @Override
            protected void onError(WebSocketChannel webSocketChannel, Throwable error) {
                hub.remove(connection);
            }
        });
        channel.resumeReceives();

        connection.send(JsonUtil.toJson(welcome(connection)));
    }

    /**
     * 处理前端发来的消息。目前只用于心跳保活，回一个 pong 让前端确认链路还活着。
     */
    private void handleMessage(PanelConnection connection, String raw) {
        if (raw == null || raw.isBlank()) {
            return;
        }

        String type;
        try {
            JsonObject object = JsonUtil.parseObject(raw);
            type = JsonUtil.optString(object, "type", "");
        } catch (RuntimeException ignored) {
            // 非法内容直接忽略
            return;
        }

        if ("ping".equalsIgnoreCase(type)) {
            Map<String, Object> pong = new LinkedHashMap<>();
            pong.put("type", "pong");
            pong.put("timestamp", System.currentTimeMillis());
            connection.send(JsonUtil.toJson(pong));
        }
    }

    private Map<String, Object> welcome(PanelConnection connection) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "welcome");
        payload.put("username", connection.getUsername());
        payload.put("serverVersion", Bukkit.getVersion());
        payload.put("timestamp", System.currentTimeMillis());
        return payload;
    }

    // ------------------------------------------------------------------
    // 握手阶段的取值
    // ------------------------------------------------------------------

    /**
     * 从 Cookie 取令牌。
     *
     * <p>只认 Cookie，<b>不支持</b>把令牌放在 URL 上（如 {@code /ws?token=...}）。
     * URL 会进浏览器历史、反向代理访问日志、Referer 头，
     * 令牌一旦这样泄出去，等于把已登录的会话直接交出去。
     * 同源请求浏览器会自动带上 Cookie，脚本客户端也可以自己设 Cookie 头。
     */
    private String extractToken(WebSocketHttpExchange exchange) {
        String cookie = exchange.getRequestHeader("Cookie");
        if (cookie == null) {
            return null;
        }
        for (String pair : cookie.split(";")) {
            int index = pair.indexOf('=');
            if (index > 0 && SESSION_COOKIE.equals(pair.substring(0, index).trim())) {
                String value = pair.substring(index + 1).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 取来源 IP，用于会话与 IP 绑定时校验。
     * 这里取的是真实连接地址，不信任任何可以在请求头里伪造的代理字段。
     *
     * @return 取不到时返回 null，此时跳过 IP 校验
     */
    private String resolveIp(WebSocketChannel channel) {
        InetSocketAddress address = channel.getSourceAddress();
        if (address != null && address.getAddress() != null) {
            return address.getAddress().getHostAddress();
        }
        return null;
    }
}
