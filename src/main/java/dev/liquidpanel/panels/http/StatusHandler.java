package dev.liquidpanel.panels.http;

import dev.liquidpanel.panels.PanelStatusProvider;
import dev.liquidpanel.panels.security.SessionManager;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Methods;

import java.util.Map;

/**
 * 状态接口。
 *
 * <p>请求跑在 Undertow 工作线程上，Bukkit 相关的数据必须回主线程采集，
 * 这一步由 {@link PanelStatusProvider} 内部用超时兜底完成；
 * 系统指标（CPU / 内存 / 硬盘 / 目录大小）则在当前线程直接算，
 * 不占主线程也不占 IO 线程。
 */
public final class StatusHandler {

    private final SessionManager sessionManager;
    private final PanelStatusProvider statusProvider;

    public StatusHandler(SessionManager sessionManager, PanelStatusProvider statusProvider) {
        this.sessionManager = sessionManager;
        this.statusProvider = statusProvider;
    }

    /**
     * GET /api/status
     */
    public void status(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.GET)) {
            return;
        }
        if (HttpUtil.requireSession(exchange, sessionManager) == null) {
            return;
        }

        // 首次拉取带上完整历史，曲线一进来就是满的
        Map<String, Object> data = statusProvider.collect(true);
        if (data == null) {
            // 主线程超过 3 秒没响应，多半是服务端正在卡顿，如实告诉前端
            HttpUtil.sendError(exchange, 503, "服务端暂时无响应，请稍后重试");
            return;
        }
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
    }
}
