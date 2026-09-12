package dev.liquidpanel.panels;

import dev.liquidpanel.panels.http.ApiResponse;
import dev.liquidpanel.panels.http.AuthHandler;
import dev.liquidpanel.panels.http.CommandHandler;
import dev.liquidpanel.panels.http.HttpUtil;
import dev.liquidpanel.panels.http.LogHandler;
import dev.liquidpanel.panels.http.StatusHandler;
import dev.liquidpanel.panels.security.SessionManager;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

import java.nio.ByteBuffer;
import java.util.Set;

/**
 * 面板的总路由。
 *
 * <p>所有请求先进这里，再按路径分发：
 * <ul>
 *     <li>{@code /api/**} —— 接口，交回各自的 Handler；</li>
 *     <li>{@code /ws} —— WebSocket 升级，交给 {@code PanelWebSocketHandler}；</li>
 *     <li>其余 —— 前端资源，统一由 {@link WebAssetManager} 分发。</li>
 * </ul>
 *
 * <h2>两阶段处理</h2>
 * <p>请求分两步走，目的是让「只发请求头、不发请求体」的半开连接无法占用工作线程：
 * <ol>
 *     <li><b>阶段一（IO 线程）</b>：用 {@code receiveFullBytes} 异步收完请求体。
 *         这一步不占用任何工作线程。客户端声明了 {@code Content-Length} 却迟迟不发数据时，
 *         回调永远不会触发，也就永远不会进入阶段二。</li>
 *     <li><b>阶段二（工作线程）</b>：请求体收完后才派发出去，在这里做 PBKDF2 等耗时操作。</li>
 * </ol>
 *
 * <p>旧实现用的是 {@code BlockingHandler} + {@code startBlocking()} 同步读体，
 * 工作线程会永久卡在读取上，且 Undertow 的 {@code IDLE_TIMEOUT} 覆盖不到那条路径。
 */
public final class PanelRouter implements HttpHandler {

    private static final String API_PREFIX = "/api/";
    private static final String WEBSOCKET_PATH = "/ws";

    /** 登录页所在目录，这一整个前缀都不做登录校验 */
    private static final String LOGIN_PREFIX = "/login/";

    /** 未登录时被送去的地方 */
    private static final String LOGIN_PAGE = "/login/login.html";

    /** 错误页，任何人都能直接访问 */
    private static final String NOT_FOUND_PAGE = "/404.html";

    /** 站点图标。登录页与错误页都要用，必须对未登录开放，否则页签图标加载不出来 */
    private static final String ICON_FILE = "/icon.png";

    /**
     * 无需登录即可访问的路径前缀。
     *
     * <p>登录页自身的 css / js / 图标都放在这个目录下；
     * 往 /login/ 里新增文件不需要改代码。
     * 若把公共资源放到别处，在这里加一条前缀即可。
     */
    private static final Set<String> PUBLIC_PREFIXES = Set.of(LOGIN_PREFIX);

    /** 无需登录即可访问的独立路径 */
    private static final Set<String> PUBLIC_PATHS = Set.of(NOT_FOUND_PAGE, ICON_FILE);

    /** 无请求体时用的空数组，避免每次都新建 */
    private static final byte[] EMPTY_BODY = new byte[0];

    private final WebAssetManager assetManager;
    private final AuthHandler authHandler;
    private final StatusHandler statusHandler;
    private final LogHandler logHandler;
    private final CommandHandler commandHandler;
    private final SessionManager sessionManager;
    private final HttpHandler webSocketHandler;

    /** 阶段二的入口：请求体已收完，运行在 Undertow 的工作线程上 */
    private final HttpHandler workerHandler;

    public PanelRouter(WebAssetManager assetManager,
                       AuthHandler authHandler,
                       StatusHandler statusHandler,
                       LogHandler logHandler,
                       CommandHandler commandHandler,
                       SessionManager sessionManager,
                       HttpHandler webSocketHandler) {
        this.assetManager = assetManager;
        this.authHandler = authHandler;
        this.statusHandler = statusHandler;
        this.logHandler = logHandler;
        this.commandHandler = commandHandler;
        this.sessionManager = sessionManager;
        this.webSocketHandler = webSocketHandler;
        this.workerHandler = this::handleInWorker;
    }

    // ------------------------------------------------------------------
    // 阶段一：IO 线程
    // ------------------------------------------------------------------

    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
        HttpUtil.applySecurityHeaders(exchange);

        String path = exchange.getRequestPath();

        // WebSocket 升级必须在 IO 线程上完成，不能经过工作线程派发
        if (WEBSOCKET_PATH.equals(path)) {
            webSocketHandler.handleRequest(exchange);
            return;
        }

        if (exchange.isRequestComplete()) {
            // GET 之类没有请求体，直接进入阶段二
            dispatch(exchange, EMPTY_BODY);
            return;
        }

        // 异步收完请求体。收不完就不会派发，工作线程全程不被占用。
        // 请求体大小已由 UndertowOptions.MAX_ENTITY_SIZE 限制，所以这里的缓冲是有界的。
        exchange.getRequestReceiver().receiveFullBytes(
                (ex, body) -> dispatch(ex, body),
                (ex, error) -> {
                    // 连接中断、超过实体上限等，都会走到这里
                    if (!ex.isResponseStarted()) {
                        HttpUtil.sendError(ex, 413, "请求体无效或过大");
                    }
                });
    }

    /**
     * 阶段一与阶段二的交接：把请求体挂到 exchange 上，再派发到工作线程。
     */
    private void dispatch(HttpServerExchange exchange, byte[] body) {
        HttpUtil.attachBody(exchange, body);
        exchange.dispatch(workerHandler);
    }

    // ------------------------------------------------------------------
    // 阶段二：工作线程
    // ------------------------------------------------------------------

    private void handleInWorker(HttpServerExchange exchange) {
        String path = exchange.getRequestPath();
        if (path.startsWith(API_PREFIX)) {
            handleApi(exchange, path);
            return;
        }
        handleStatic(exchange, path);
    }

    private void handleApi(HttpServerExchange exchange, String path) {
        switch (path) {
            case "/api/auth/login" -> authHandler.login(exchange);
            case "/api/auth/logout" -> authHandler.logout(exchange);
            case "/api/auth/session" -> authHandler.session(exchange);
            case "/api/auth/password" -> authHandler.changePassword(exchange);
            case "/api/auth/credentials" -> authHandler.changeCredentials(exchange);
            case "/api/status" -> statusHandler.status(exchange);
            case "/api/logs" -> logHandler.logs(exchange);
            case "/api/console" -> commandHandler.execute(exchange);
            default -> HttpUtil.sendJson(exchange, 404, ApiResponse.error("接口不存在"));
        }
    }

    // ------------------------------------------------------------------
    // 前端资源
    // ------------------------------------------------------------------

    private void handleStatic(HttpServerExchange exchange, String path) {
        // 用 requireMethod 而不是直接 sendError：它会按 RFC 7231 补上 Allow 头
        if (!HttpUtil.requireMethod(exchange, Methods.GET, Methods.HEAD)) {
            return;
        }

        // 精确解析，不回退：拿到的必须是这个路径真实对应的文件
        WebAssetManager.Asset asset = assetManager.resolve(path);
        boolean publicPath = isPublicPath(path);

        if (publicPath) {
            // 公共目录下确实不存在的文件，直接 404。
            // 不要跳登录页，否则登录页少一个图标就会拿到一份 HTML 当 CSS 用。
            if (asset == null) {
                serveNotFound(exchange);
                return;
            }
        } else if (HttpUtil.resolveSession(exchange, sessionManager) == null) {
            HttpUtil.sendRedirect(exchange, LOGIN_PAGE);
            return;
        } else if (asset == null) {
            // 已登录才允许前端路由兜底，且只对无扩展名的路径生效
            if (assetManager.hasExtension(path)) {
                serveNotFound(exchange);
                return;
            }
            asset = assetManager.fallbackToIndex();
            if (asset == null) {
                serveNotFound(exchange);
                return;
            }
        }

        serve(exchange, asset, 200, assetManager.cacheControl(path));
    }

    /**
     * 是否是无需登录的公共路径。
     *
     * <p>这里是白名单而不是「看后缀」的黑名单：黑名单只要漏掉一种情况就会放行
     * （比如 .htm、.bak，或 /login/ 下的任意路径），
     * 而白名单默认拒绝，新增公共资源时显式加一条即可。
     *
     * <p>调用方必须同时确认该路径真实存在，否则 /login/ 下任意不存在的路径
     * 也会因为前缀命中而被放行。
     */
    private boolean isPublicPath(String path) {
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void serveNotFound(HttpServerExchange exchange) {
        WebAssetManager.Asset asset = assetManager.loadNotFound();
        if (asset == null) {
            HttpUtil.sendError(exchange, 404, "资源不存在");
            return;
        }
        // 404 页面本身不缓存，改了立刻生效
        serve(exchange, asset, 404, "no-store");
    }

    private void serve(HttpServerExchange exchange, WebAssetManager.Asset asset, int status, String cacheControl) {
        byte[] content = asset.getContent();
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, asset.getContentType());
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, cacheControl);

        if (Methods.HEAD.equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(content.length));
            exchange.endExchange();
            return;
        }
        exchange.getResponseSender().send(ByteBuffer.wrap(content));
    }
}
