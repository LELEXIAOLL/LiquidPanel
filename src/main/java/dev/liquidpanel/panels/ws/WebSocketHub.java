package dev.liquidpanel.panels.ws;

import dev.liquidpanel.utils.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 连接中心。
 *
 * <p>所有长连接在这里登记，推送时统一广播。
 * 本类的方法可以在任意线程调用（包括 Bukkit 的异步任务线程）。
 */
public final class WebSocketHub {

    /** 当前在线连接。用对象身份做键，避免依赖 channel 的内部 id */
    private final Set<PanelConnection> connections = ConcurrentHashMap.newKeySet();

    /**
     * 登记一条新连接。
     */
    public void add(PanelConnection connection) {
        connections.add(connection);
    }

    /**
     * 移除连接。
     */
    public void remove(PanelConnection connection) {
        if (connection != null) {
            connections.remove(connection);
        }
    }

    public boolean isEmpty() {
        return connections.isEmpty();
    }

    public int size() {
        return connections.size();
    }

    /**
     * 广播一段已经序列化好的 JSON 文本。
     */
    public void broadcast(String json) {
        if (connections.isEmpty()) {
            return;
        }
        for (PanelConnection connection : connections) {
            if (connection.isOpen()) {
                connection.send(json);
            } else {
                connections.remove(connection);
            }
        }
    }

    /**
     * 广播一个对象，自动序列化。
     */
    public void broadcast(Object payload) {
        if (connections.isEmpty()) {
            return;
        }
        broadcast(JsonUtil.toJson(payload));
    }

    /**
     * 踢掉某个账号的全部连接，用于登出、改密码、封禁等场景。
     */
    public void closeUser(String username, String reason) {
        if (username == null) {
            return;
        }
        for (PanelConnection connection : snapshot()) {
            if (username.equals(connection.getUsername())) {
                connection.close(reason);
                connections.remove(connection);
            }
        }
    }

    /**
     * 清理已经关闭的连接。
     */
    public void cleanup() {
        connections.removeIf(connection -> !connection.isOpen());
    }

    /**
     * 关闭全部连接，插件卸载时调用。
     */
    public void closeAll(String reason) {
        for (PanelConnection connection : snapshot()) {
            connection.close(reason);
        }
        connections.clear();
    }

    private List<PanelConnection> snapshot() {
        return new ArrayList<>(connections);
    }
}
