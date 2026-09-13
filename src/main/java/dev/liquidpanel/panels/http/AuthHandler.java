package dev.liquidpanel.panels.http;

import com.google.gson.JsonObject;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.PanelSettings;
import dev.liquidpanel.panels.security.AccountManager;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.LoginGuard;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.Map;

/**
 * 认证接口。
 *
 * <p>登录、登出、查询登录状态、修改密码。前后端所有涉及身份的流程都走这里。
 *
 * <p>凡是会做密码摘要（PBKDF2，单次约 400ms）的分支，都先经过
 * {@link LoginGuard#tryAcquireVerification()} 这道并发闸门：
 * 拿不到名额立刻回 429，既不排队也不占线程，避免有人用大量请求把 CPU 打满。
 */
public final class AuthHandler {

    /** 密码长度下限 */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /** 用户名允许的字符与长度 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");

    private final AccountManager accountManager;
    private final SessionManager sessionManager;
    private final LoginGuard loginGuard;
    private final PanelSettings settings;
    private final HostValidator hostValidator;

    public AuthHandler(AccountManager accountManager,
                       SessionManager sessionManager,
                       LoginGuard loginGuard,
                       PanelSettings settings,
                       HostValidator hostValidator) {
        this.accountManager = accountManager;
        this.sessionManager = sessionManager;
        this.loginGuard = loginGuard;
        this.settings = settings;
        this.hostValidator = hostValidator;
    }

    /**
     * POST /api/auth/login
     */
    public void login(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.POST)) {
            return;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return;
        }

        String ip = HttpUtil.clientIp(exchange);
        if (loginGuard.isBlocked(ip)) {
            sendRateLimited(exchange, ip);
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

        String username = JsonUtil.optString(body, "username", "");
        String password = JsonUtil.optString(body, "password", "");
        if (username.isEmpty() || password.isEmpty()) {
            HttpUtil.sendError(exchange, 400, "请输入账号与密码");
            return;
        }

        boolean verified;
        if (!loginGuard.tryAcquireVerification()) {
            HttpUtil.sendJson(exchange, 429, ApiResponse.error("服务器正忙，请稍后再试"));
            return;
        }
        try {
            verified = accountManager.verify(username, password);
        } finally {
            loginGuard.releaseVerification();
        }

        if (!verified) {
            loginGuard.recordFailure(ip);
            // 刻意不区分「账号不存在」和「密码错误」，避免被用来枚举账号
            HttpUtil.sendError(exchange, 401, "账号或密码错误");
            return;
        }

        loginGuard.recordSuccess(ip);
        PanelSession session = sessionManager.create(username, ip);

        // 「记住我」勾了才下发持久 Cookie，否则关掉浏览器就失效
        boolean remember = JsonUtil.optBoolean(body, "remember", false);
        int maxAgeSeconds = (int) (settings.getSessionTimeoutMinutes() * 60L);
        HttpUtil.setSessionCookie(exchange, session.getToken(), remember ? maxAgeSeconds : 0);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("username", session.getUsername());
        data.put("expiresInSeconds", maxAgeSeconds);
        data.put("remember", remember);
        data.put("mustChangePassword", accountManager.isPasswordChangeRequired());
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("登录成功", data));
    }

    /**
     * POST /api/auth/logout
     */
    public void logout(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.POST)) {
            return;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return;
        }
        sessionManager.invalidate(HttpUtil.extractToken(exchange));
        HttpUtil.clearSessionCookie(exchange);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已退出登录", null));
    }

    /**
     * GET /api/auth/session —— 前端每次加载页面都先问一次，决定显示登录页还是面板
     */
    public void session(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.GET)) {
            return;
        }
        PanelSession session = HttpUtil.resolveSession(exchange, sessionManager);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authenticated", session != null);
        if (session != null) {
            data.put("username", session.getUsername());
            // 刷新页面时同样要知道密码还没改，不能只在登录响应里给
            data.put("mustChangePassword", accountManager.isPasswordChangeRequired());
        }
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
    }

    /**
     * POST /api/auth/password —— 修改密码，同时让该账号的其他会话全部失效
     */
    public void changePassword(HttpServerExchange exchange) {
        if (!HttpUtil.requireMethod(exchange, Methods.POST)) {
            return;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return;
        }

        // 这个接口同样要校验旧密码，所以必须和登录共用同一套防护：
        // 否则拿到会话之后就能无限次爆破旧密码，甚至借 PBKDF2 的 CPU 开销拖垮服务端。
        String ip = HttpUtil.clientIp(exchange);
        if (loginGuard.isBlocked(ip)) {
            sendRateLimited(exchange, ip);
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

        String oldPassword = JsonUtil.optString(body, "oldPassword", "");
        String newPassword = JsonUtil.optString(body, "newPassword", "");
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            HttpUtil.sendError(exchange, 400, "新密码长度至少为 " + MIN_PASSWORD_LENGTH + " 位");
            return;
        }

        if (!loginGuard.tryAcquireVerification()) {
            HttpUtil.sendJson(exchange, 429, ApiResponse.error("服务器正忙，请稍后再试"));
            return;
        }
        boolean changed;
        try {
            changed = accountManager.changePassword(oldPassword, newPassword);
        } finally {
            loginGuard.releaseVerification();
        }

        if (!changed) {
            // 校验失败同样计入封禁计数，否则这里就是个可以无限试探的口子
            loginGuard.recordFailure(ip);
            // 用 403 而不是 401：前端把 401 统一当作「会话失效」并跳登录页，
            // 用 401 的话密码输错一次就会被踢出去
            HttpUtil.sendError(exchange, 403, "原密码不正确");
            return;
        }
        loginGuard.recordSuccess(ip);

        // 改完密码强制全部重新登录，当前这个也不例外
        sessionManager.invalidateAll(session.getUsername());
        HttpUtil.clearSessionCookie(exchange);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("密码修改成功，请重新登录", null));
    }

    /**
     * POST /api/auth/credentials —— 同时修改用户名与密码。
     *
     * <p>两处细节：
     * <ul>
     *     <li>仍在用初始密码时<b>不要求</b>旧密码 —— 刚刚就是用它登录的，
     *         再问一遍没有意义。密码改过之后则必须验证旧密码，
     *         否则会话一旦泄漏就能直接顶掉原主人的凭据。</li>
     *     <li>改完清空<b>全部</b>会话，包括当前这个。凭据变了，旧会话不该继续有效。</li>
     * </ul>
     */
    public void changeCredentials(HttpServerExchange exchange) {
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

        String username = JsonUtil.optString(body, "username", "").trim();
        String newPassword = JsonUtil.optString(body, "newPassword", "");
        String oldPassword = JsonUtil.optString(body, "oldPassword", "");

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            HttpUtil.sendError(exchange, 400, "用户名需为 3-32 位字母、数字、下划线、点或短横线");
            return;
        }
        if (newPassword.length() < MIN_PASSWORD_LENGTH) {
            HttpUtil.sendError(exchange, 400, "新密码长度至少为 " + MIN_PASSWORD_LENGTH + " 位");
            return;
        }

        boolean initialSetup = accountManager.isPasswordChangeRequired();

        if (!loginGuard.tryAcquireVerification()) {
            HttpUtil.sendJson(exchange, 429, ApiResponse.error("服务器正忙，请稍后再试"));
            return;
        }
        boolean applied;
        try {
            if (!initialSetup && !accountManager.verify(accountManager.getUsername(), oldPassword)) {
                loginGuard.recordFailure(HttpUtil.clientIp(exchange));
                // 同上：这里必须避开 401，否则前端会把「密码输错」当成「会话过期」
                HttpUtil.sendError(exchange, 403, "原密码不正确");
                return;
            }
            applied = accountManager.changeCredentials(username, newPassword);
        } finally {
            loginGuard.releaseVerification();
        }

        if (!applied) {
            HttpUtil.sendError(exchange, 500, "修改失败，请查看控制台日志");
            return;
        }

        MessagesManager.logRaw("§7[面板] 面板账号已更新，新用户名为 §f" + username + "§7，全部会话已失效");

        sessionManager.invalidateAll();
        HttpUtil.clearSessionCookie(exchange);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("账号密码已修改，请用新账号重新登录", null));
    }

    /**
     * 统一回 429，并告诉客户端还要等多久。
     */
    private void sendRateLimited(HttpServerExchange exchange, String ip) {
        exchange.getResponseHeaders().put(Headers.RETRY_AFTER, String.valueOf(loginGuard.blockedSeconds(ip)));
        HttpUtil.sendError(exchange, 429, "尝试次数过多，请稍后再试");
    }
}
