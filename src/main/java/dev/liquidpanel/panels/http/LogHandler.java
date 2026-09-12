package dev.liquidpanel.panels.http;

import dev.liquidpanel.panels.logs.ConsoleLogCollector;
import dev.liquidpanel.panels.security.SessionManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 控制台日志接口。
 *
 * <p>只负责首次加载时把最近若干行一次性给前端；
 * 之后的新行走 WebSocket 增量推送，不走这里。
 */
public final class LogHandler {

    private final SessionManager sessionManager;
    private final ConsoleLogCollector logCollector;

    public LogHandler(SessionManager sessionManager, ConsoleLogCollector logCollector) {
        this.sessionManager = sessionManager;
        this.logCollector = logCollector;
    }

    /**
     * GET /api/logs
     */
    public void logs(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.GET)) {
            return;
        }
        if (HttpUtil.requireSession(exchange, sessionManager) == null) {
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lines", logCollector.snapshot());
        data.put("source", "latest.log");
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
    }
}
