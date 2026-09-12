package dev.liquidpanel.panels.security;

/**
 * 一次登录会话。
 *
 * <p>{@link #token} 是登录成功后下发给浏览器的随机令牌，同时写进 Cookie，
 * 之后所有接口和 WebSocket 都靠它识别身份。
 */
public final class PanelSession {

    private final String token;
    private final String username;
    private final String ip;
    private final long createdAt;

    /** 最近一次访问时间，用于滑动续期 */
    private volatile long lastAccessAt;

    public PanelSession(String token, String username, String ip) {
        this.token = token;
        this.username = username;
        this.ip = ip == null ? "" : ip;
        this.createdAt = System.currentTimeMillis();
        this.lastAccessAt = this.createdAt;
    }

    /**
     * 是否已过期。每次访问都会刷新 lastAccessAt，所以是「闲置超时」而不是「绝对超时」。
     */
    public boolean isExpired(long timeoutMillis) {
        return System.currentTimeMillis() - lastAccessAt > timeoutMillis;
    }

    public void touch() {
        this.lastAccessAt = System.currentTimeMillis();
    }

    /**
     * 来源 IP 是否与会话绑定的 IP 一致。
     *
     * <p>未开启绑定、或取不到来源 IP 时不做校验。
     * 后者只会出现在 WebSocket 握手等拿不到连接地址的场景，
     * 此时令牌本身就是唯一凭据，不会因此降低安全性。
     */
    public boolean matchesIp(String remoteIp, boolean bindIp) {
        if (!bindIp || remoteIp == null || remoteIp.isEmpty()) {
            return true;
        }
        return ip.equals(remoteIp);
    }

    public String getToken() {
        return token;
    }

    public String getUsername() {
        return username;
    }

    public String getIp() {
        return ip;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getLastAccessAt() {
        return lastAccessAt;
    }
}
