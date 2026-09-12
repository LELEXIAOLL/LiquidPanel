package dev.liquidpanel.panels.security;

import dev.liquidpanel.utils.RandomUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录会话管理器，负责令牌的签发、校验与失效。
 *
 * <p>令牌是 256 位随机值，只保存在内存里，服务端重启即全部失效——这是刻意的：
 * 面板重启后强制重新登录，比把长期令牌写进磁盘安全得多。
 */
public final class SessionManager {

    /** 令牌随机字节数，32 字节 = 256 位 */
    private static final int TOKEN_BYTES = 32;

    private final Map<String, PanelSession> sessions = new ConcurrentHashMap<>();

    /** 会话闲置超时（毫秒），由设置注入 */
    private volatile long timeoutMillis = 120L * 60L * 1000L;

    /** 是否把会话与来源 IP 绑定 */
    private volatile boolean bindIp = false;

    public void configure(long timeoutMillis, boolean bindIp) {
        this.timeoutMillis = Math.max(60_000L, timeoutMillis);
        this.bindIp = bindIp;
    }

    /**
     * 创建会话。
     */
    public PanelSession create(String username, String ip) {
        PanelSession session = new PanelSession(RandomUtil.token(TOKEN_BYTES), username, ip);
        sessions.put(session.getToken(), session);
        return session;
    }

    /**
     * 校验令牌。
     *
     * @return 有效则返回会话并续期，否则返回 null
     */
    public PanelSession validate(String token, String ip) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        PanelSession session = sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.isExpired(timeoutMillis) || !session.matchesIp(ip, bindIp)) {
            sessions.remove(token);
            return null;
        }
        session.touch();
        return session;
    }

    /**
     * 注销单个令牌。
     */
    public void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /**
     * 让某个用户的所有会话失效，用于改密码后强制其他端重新登录。
     */
    public void invalidateAll(String username) {
        if (username == null) {
            return;
        }
        sessions.entrySet().removeIf(entry -> username.equals(entry.getValue().getUsername()));
    }

    /**
     * 让全部会话失效，用于凭据变更后强制所有人重新登录。
     */
    public void invalidateAll() {
        sessions.clear();
    }

    /**
     * 清理过期会话，由定时任务调用。返回清理数量。
     */
    public int cleanup() {
        long timeout = timeoutMillis;
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, PanelSession> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired(timeout)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(sessions::remove);
        return expired.size();
    }

    public int size() {
        return sessions.size();
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
