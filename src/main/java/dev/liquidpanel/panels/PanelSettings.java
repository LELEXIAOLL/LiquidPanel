package dev.liquidpanel.panels;

import dev.liquidpanel.configs.ConfigManager;
import dev.liquidpanel.panels.security.LoginGuard;

import java.util.Collections;
import java.util.List;

/**
 * 网页面板的运行参数，全部来自 config.yml 的 {@code panel} 段。
 *
 * <p>所有取值都做了兜底，配置写错不会导致面板起不来。
 */
public final class PanelSettings {

    public static final int DEFAULT_PORT = 1357;
    public static final String DEFAULT_HOST = "0.0.0.0";
    public static final int DEFAULT_SESSION_TIMEOUT_MINUTES = 120;
    public static final boolean DEFAULT_BIND_SESSION_IP = false;

    private boolean enabled = true;
    private int port = DEFAULT_PORT;
    private String host = DEFAULT_HOST;
    private int sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;
    private boolean bindSessionIp = DEFAULT_BIND_SESSION_IP;

    /** 额外允许的 Host（域名），供 HostValidator 生成白名单 */
    private List<String> allowedHosts = Collections.emptyList();

    // ---- 防爆破参数，对应 config.yml 的 panel.security ----

    private int maxFailures = LoginGuard.DEFAULT_MAX_FAILURES;
    private int failWindowMinutes = LoginGuard.DEFAULT_FAIL_WINDOW_MINUTES;
    private int blockMinutes = LoginGuard.DEFAULT_BLOCK_MINUTES;
    private int maxConcurrentVerifications = LoginGuard.DEFAULT_MAX_CONCURRENT_VERIFICATIONS;

    /**
     * 从配置缓存里重新读取。
     */
    public void reload() {
        enabled = ConfigManager.getBoolean("panel.enabled", true);

        port = ConfigManager.getInt("panel.port", DEFAULT_PORT);
        if (port < 1 || port > 65535) {
            port = DEFAULT_PORT;
        }

        host = ConfigManager.getString("panel.host", DEFAULT_HOST);
        if (host == null || host.isBlank()) {
            host = DEFAULT_HOST;
        }

        sessionTimeoutMinutes = ConfigManager.getInt("panel.session_timeout_minutes", DEFAULT_SESSION_TIMEOUT_MINUTES);
        if (sessionTimeoutMinutes < 1) {
            sessionTimeoutMinutes = DEFAULT_SESSION_TIMEOUT_MINUTES;
        }

        bindSessionIp = ConfigManager.getBoolean("panel.bind_session_ip", DEFAULT_BIND_SESSION_IP);

        List<String> hosts = ConfigManager.getStringList("panel.allowed_hosts");
        allowedHosts = hosts == null ? Collections.emptyList() : hosts;

        maxFailures = positive(ConfigManager.getInt("panel.security.max_failures",
                LoginGuard.DEFAULT_MAX_FAILURES), LoginGuard.DEFAULT_MAX_FAILURES);
        failWindowMinutes = positive(ConfigManager.getInt("panel.security.fail_window_minutes",
                LoginGuard.DEFAULT_FAIL_WINDOW_MINUTES), LoginGuard.DEFAULT_FAIL_WINDOW_MINUTES);
        blockMinutes = positive(ConfigManager.getInt("panel.security.block_minutes",
                LoginGuard.DEFAULT_BLOCK_MINUTES), LoginGuard.DEFAULT_BLOCK_MINUTES);
        maxConcurrentVerifications = positive(ConfigManager.getInt("panel.security.max_concurrent_verifications",
                LoginGuard.DEFAULT_MAX_CONCURRENT_VERIFICATIONS), LoginGuard.DEFAULT_MAX_CONCURRENT_VERIFICATIONS);
    }

    /**
     * 小于 1 的值没有意义，退回默认值。
     */
    private int positive(int value, int fallback) {
        return value < 1 ? fallback : value;
    }

    /**
     * 给管理员点击访问用的地址。
     *
     * <p>监听 0.0.0.0 时不能直接给浏览器，换成回环地址，
     * 否则在某些系统上会打不开。
     */
    public String browseUrl() {
        return "http://" + browseHost() + ":" + port + "/";
    }

    private String browseHost() {
        if ("0.0.0.0".equals(host) || "::".equals(host) || "*".equals(host)) {
            return "127.0.0.1";
        }
        return host;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getPort() {
        return port;
    }

    public String getHost() {
        return host;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public long getSessionTimeoutMillis() {
        return sessionTimeoutMinutes * 60_000L;
    }

    public boolean isBindSessionIp() {
        return bindSessionIp;
    }

    /**
     * 管理员额外配置的允许 Host，用于按域名访问面板的场景。
     */
    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    /** 统计窗口内允许的失败次数 */
    public int getMaxFailures() {
        return maxFailures;
    }

    /** 失败统计窗口（分钟） */
    public int getFailWindowMinutes() {
        return failWindowMinutes;
    }

    /** 触发后封禁时长（分钟） */
    public int getBlockMinutes() {
        return blockMinutes;
    }

    /** 同时进行的密码校验上限 */
    public int getMaxConcurrentVerifications() {
        return maxConcurrentVerifications;
    }

    /**
     * 监听参数是否与给定值一致，用于判断重载后要不要重启服务器。
     */
    public boolean sameEndpoint(String otherHost, int otherPort) {
        return host.equals(otherHost) && port == otherPort;
    }
}
