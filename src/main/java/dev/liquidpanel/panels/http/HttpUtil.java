package dev.liquidpanel.panels.http;

import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP 请求/响应的通用处理。
 *
 * <p>把「取 Cookie、读请求体、回 JSON、写安全响应头、判同源」这些每个接口都要做的事收在这里，
 * Handler 里只写业务。
 */
public final class HttpUtil {

    /** 会话 Cookie 名 */
    public static final String SESSION_COOKIE = "liquidpanel_session";

    /** 已收完的请求体，由 PanelRouter 写入、各 Handler 读取 */
    private static final AttachmentKey<byte[]> REQUEST_BODY = AttachmentKey.create(byte[].class);

    /** 请求体上限。PanelServer 会把这个值同步给 Undertow 的 MAX_ENTITY_SIZE */
    public static final int MAX_BODY_BYTES = 16 * 1024;

    private static final HttpString ORIGIN = new HttpString("Origin");
    private static final HttpString SEC_FETCH_SITE = new HttpString("Sec-Fetch-Site");
    private static final HttpString X_CONTENT_TYPE_OPTIONS = new HttpString("X-Content-Type-Options");
    private static final HttpString X_FRAME_OPTIONS = new HttpString("X-Frame-Options");
    private static final HttpString REFERRER_POLICY = new HttpString("Referrer-Policy");
    private static final HttpString CONTENT_SECURITY_POLICY = new HttpString("Content-Security-Policy");
    private static final HttpString PERMISSIONS_POLICY = new HttpString("Permissions-Policy");
    private static final HttpString CROSS_ORIGIN_OPENER_POLICY = new HttpString("Cross-Origin-Opener-Policy");

    /** 脚本只允许同源外链，不允许内联，杜绝 XSS 注入执行；样式放开 inline 是为了方便动态改宽度之类的属性 */
    private static final String CSP =
            "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            // 玩家头像由浏览器直接加载（走管理员自己的网络）：
            //   textures.minecraft.net —— Mojang 官方贴图，有皮肤时优先用
            //   mc-heads.net / minotar.net —— 按玩家名直接取正版头像，
            //     离线模式下也能用（Mojang 的 Profile 里没有贴图，只能靠名字去查）
            // 只放开这三个图片域名，脚本与样式仍然只允许同源。
            + "img-src 'self' data: https://textures.minecraft.net https://mc-heads.net https://minotar.net; "
            + "font-src 'self'; "
            + "connect-src 'self' ws: wss:; "
            + "frame-ancestors 'none'; "
            + "base-uri 'none'; "
            + "form-action 'self'; "
            + "object-src 'none'";

    private HttpUtil() {
    }

    // ------------------------------------------------------------------
    // 响应头
    // ------------------------------------------------------------------

    /**
     * 打上安全响应头。默认不缓存，静态资源可以在此之后单独覆盖 Cache-Control。
     */
    public static void applySecurityHeaders(HttpServerExchange exchange) {
        var headers = exchange.getResponseHeaders();
        headers.put(X_CONTENT_TYPE_OPTIONS, "nosniff");
        headers.put(X_FRAME_OPTIONS, "DENY");
        headers.put(REFERRER_POLICY, "no-referrer");
        headers.put(CONTENT_SECURITY_POLICY, CSP);
        headers.put(PERMISSIONS_POLICY, "geolocation=(), microphone=(), camera=()");
        headers.put(CROSS_ORIGIN_OPENER_POLICY, "same-origin");
        headers.put(Headers.CACHE_CONTROL, "no-store");
        // 面板默认跑在 HTTP 上，因此不能加 Secure，否则浏览器根本不会回传 Cookie。
        // 日后如果套了 HTTPS 反向代理，可以在这里补上 Secure。
    }

    // ------------------------------------------------------------------
    // 响应
    // ------------------------------------------------------------------

    public static void sendJson(HttpServerExchange exchange, int status, ApiResponse response) {
        sendJson(exchange, status, response.toMap());
    }

    public static void sendJson(HttpServerExchange exchange, int status, Object body) {
        byte[] data = JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json; charset=utf-8");
        // 用 ByteBuffer 显式指定 UTF-8，避免中文提示被按平台编码输出成乱码
        exchange.getResponseSender().send(ByteBuffer.wrap(data));
    }

    public static void sendError(HttpServerExchange exchange, int status, String message) {
        sendJson(exchange, status, ApiResponse.error(message));
    }

    /**
     * 302 跳转。未登录访问页面时用它把人送去登录页。
     */
    public static void sendRedirect(HttpServerExchange exchange, String location) {
        exchange.setStatusCode(302);
        exchange.getResponseHeaders().put(Headers.LOCATION, location);
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.endExchange();
    }

    /**
     * 校验请求方法，不匹配时自动回 405。
     *
     * @return 是否匹配
     */
    public static boolean requireMethod(HttpServerExchange exchange, HttpString... allowed) {
        HttpString actual = exchange.getRequestMethod();
        if (Arrays.asList(allowed).contains(actual)) {
            return true;
        }
        exchange.getResponseHeaders().put(Headers.ALLOW, joinMethods(allowed));
        sendError(exchange, 405, "请求方法不被允许");
        return false;
    }

    private static String joinMethods(HttpString[] methods) {
        StringBuilder builder = new StringBuilder();
        for (HttpString method : methods) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(method.toString());
        }
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /**
     * 暂存已经收完的请求体，由 {@code PanelRouter} 在 IO 线程上调用。
     */
    public static void attachBody(HttpServerExchange exchange, byte[] body) {
        exchange.putAttachment(REQUEST_BODY, body);
    }

    /**
     * 取回请求体（UTF-8）。
     *
     * <p>请求体在进入工作线程之前就已经由 {@code PanelRouter} 异步收完，
     * 这里只是读取内存里的字节，<b>不会发生任何阻塞</b>。
     *
     * <p>这正是半开连接 DoS 的根治点：旧实现用 {@code startBlocking()} + {@code InputStream}
     * 同步读体，客户端声明了 {@code Content-Length} 却一个字节不发时，
     * 工作线程会永久卡在读取上；Undertow 的 {@code IDLE_TIMEOUT} 也覆盖不到这条阻塞读路径。
     * 改成先异步收完再派发，收不完就永远不占工作线程。
     */
    public static String readBody(HttpServerExchange exchange) {
        byte[] data = exchange.getAttachment(REQUEST_BODY);
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length > MAX_BODY_BYTES) {
            throw new BodyTooLargeException(data.length);
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /**
     * 请求体超过上限。属于请求级错误，调用方回 413。
     */
    public static final class BodyTooLargeException extends RuntimeException {

        public BodyTooLargeException(long actualBytes) {
            super("请求体过大: " + actualBytes + " 字节，上限 " + MAX_BODY_BYTES);
        }
    }

    /**
     * 解析 Cookie。
     */
    public static Map<String, String> parseCookies(HttpServerExchange exchange) {
        Map<String, String> cookies = new HashMap<>();
        List<String> headerValues = exchange.getRequestHeaders().get(Headers.COOKIE);
        if (headerValues == null) {
            return cookies;
        }
        for (String header : headerValues) {
            for (String pair : header.split(";")) {
                int index = pair.indexOf('=');
                if (index <= 0) {
                    continue;
                }
                cookies.put(pair.substring(0, index).trim(), pair.substring(index + 1).trim());
            }
        }
        return cookies;
    }

    /**
     * 解析查询串。出错的参数原样保留，不抛异常。
     */
    public static Map<String, String> parseQuery(String queryString) {
        Map<String, String> params = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return params;
        }
        for (String pair : queryString.split("&")) {
            int index = pair.indexOf('=');
            if (index <= 0) {
                continue;
            }
            try {
                params.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                // 百分号编码有问题就按原文存，不因为一个参数把整个请求打回
                params.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }
        return params;
    }

    /**
     * 取来源 IP。直接取 TCP 连接地址，不信任 X-Forwarded-For，避免伪造 IP 绕过登录限制。
     */
    public static String clientIp(HttpServerExchange exchange) {
        InetSocketAddress address = exchange.getSourceAddress();
        if (address == null || address.getAddress() == null) {
            return "unknown";
        }
        return address.getAddress().getHostAddress();
    }

    /**
     * 判断请求是否来自面板自身页面。
     *
     * <p>三道检查，缺一不可：
     * <ol>
     *     <li><b>Host 白名单</b> —— DNS Rebinding 的标准防御。攻击者把域名解析到面板 IP 后，
     *         Origin 与 Host 都由他控制且天然相等，只比较这两个值必然被绕过，
     *         所以必须先确认 Host 是面板本来就该被访问到的名字。</li>
     *     <li><b>Origin == Host</b> —— 挡住常规跨站请求。</li>
     *     <li><b>默认拒绝</b> —— Origin 与 Sec-Fetch-Site 都缺失时按非同源处理。
     *         现代浏览器跨站 POST 必定带其中一个头，因此这条只影响不带 Origin 的脚本调用。</li>
     * </ol>
     *
     * <p>会话令牌放在 Cookie 里，配合 SameSite=Strict，四层一起挡住跨站请求。
     *
     * @param hostValidator Host 白名单。为 null 时一律拒绝，避免配置漏接导致校验被架空
     */
    public static boolean isSameOrigin(HttpServerExchange exchange, HostValidator hostValidator) {
        String hostHeader = exchange.getRequestHeaders().getFirst(Headers.HOST);

        // 第一步：Host 必须在白名单里。这一步才是拦 DNS Rebinding 的关键。
        if (hostValidator == null || !hostValidator.isAllowed(hostHeader)) {
            return false;
        }
        String host = HostValidator.extractHost(hostHeader);
        if (host == null) {
            return false;
        }

        String origin = exchange.getRequestHeaders().getFirst(ORIGIN);
        if (origin != null && !origin.isBlank()) {
            try {
                // 只比主机名，不比端口：反向代理常见
                // proxy_set_header Host $host（nginx 的 $host 会去掉端口），
                // 于是 Origin 带端口、Host 不带端口，比完整 authority 会把正常登录误杀成 403。
                // 安全性由上面的白名单承担，这一步只是确认来源指向的是同一个主机。
                return host.equals(HostValidator.extractHost(URI.create(origin).getAuthority()));
            } catch (RuntimeException e) {
                return false;
            }
        }

        // 没有 Origin，退而看 Fetch Metadata；两者都没有则拒绝
        String fetchSite = exchange.getRequestHeaders().getFirst(SEC_FETCH_SITE);
        return "same-origin".equalsIgnoreCase(fetchSite) || "none".equalsIgnoreCase(fetchSite);
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    /**
     * 从 Cookie 或 Authorization 头里取出令牌。
     */
    public static String extractToken(HttpServerExchange exchange) {
        String cookie = parseCookies(exchange).get(SESSION_COOKIE);
        if (cookie != null && !cookie.isEmpty()) {
            return cookie;
        }
        String authorization = exchange.getRequestHeaders().getFirst(Headers.AUTHORIZATION);
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return authorization.substring(7).trim();
        }
        return null;
    }

    /**
     * 取出并校验会话。
     *
     * @return 未登录或已过期返回 null
     */
    public static PanelSession resolveSession(HttpServerExchange exchange, SessionManager sessionManager) {
        return sessionManager.validate(extractToken(exchange), clientIp(exchange));
    }

    /**
     * 要求已登录，未登录时自动回 401。
     *
     * <p><b>约定：面板内需要登录的接口，401 一律只表示「会话无效」。</b>
     * 前端对 401 的处理是直接跳登录页，所以任何「已登录但不该做这件事」的错误
     * （比如旧密码不正确）都必须用 403，否则用户只是输错一次密码就会被踢出去。
     *
     * <p>登录接口自己是例外：那里还没有会话，401 就是标准的「凭据不对」，
     * 而且它的调用方是登录页，不套用上面这条跳转规则。
     */
    public static PanelSession requireSession(HttpServerExchange exchange, SessionManager sessionManager) {
        PanelSession session = resolveSession(exchange, sessionManager);
        if (session == null) {
            sendError(exchange, 401, "尚未登录或登录状态已过期");
        }
        return session;
    }

    /**
     * 下发会话 Cookie。
     *
     * <p>HttpOnly 阻止脚本读取，SameSite=Strict 阻止跨站携带，Path 限定在面板根路径下。
     *
     * @param maxAgeSeconds 大于 0 时是持久 Cookie（对应「记住我」）；
     *                      传 0 或负数则不带 Max-Age，关掉浏览器即失效
     */
    public static void setSessionCookie(HttpServerExchange exchange, String token, int maxAgeSeconds) {
        StringBuilder value = new StringBuilder();
        value.append(SESSION_COOKIE).append('=').append(token)
                .append("; Path=/")
                .append("; HttpOnly")
                .append("; SameSite=Strict");
        if (maxAgeSeconds > 0) {
            value.append("; Max-Age=").append(maxAgeSeconds);
        }
        exchange.getResponseHeaders().add(Headers.SET_COOKIE, value.toString());
    }

    /**
     * 清除会话 Cookie。
     */
    public static void clearSessionCookie(HttpServerExchange exchange) {
        exchange.getResponseHeaders().add(Headers.SET_COOKIE,
                SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

}
