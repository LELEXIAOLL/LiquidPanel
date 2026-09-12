package dev.liquidpanel.panels.ws;

import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.websockets.core.WebSockets;

/**
 * 一条已经通过身份校验的 WebSocket 长连接。
 *
 * <p>Undertow 的同一个连接不允许并发写（会撕裂帧），所以所有发送都串行化在一把锁里。
 * 面板的推送由定时任务触发，天然可能与心跳回包并发，这层保护必须要有。
 */
public final class PanelConnection {

    /** 主动关闭时使用的关闭码 */
    private static final int CLOSE_POLICY_VIOLATION = CloseMessage.MSG_VIOLATES_POLICY;

    private final WebSocketChannel channel;
    private final String username;
    private final long connectedAt = System.currentTimeMillis();

    /** 发送串行锁 */
    private final Object sendLock = new Object();

    public PanelConnection(WebSocketChannel channel, String username) {
        this.channel = channel;
        this.username = username;
    }

    /**
     * 发送一条文本消息。连接已关闭时静默忽略。
     */
    public void send(String text) {
        synchronized (sendLock) {
            if (channel.isOpen()) {
                // 回调传 null，发送失败无需感知：连接坏了会在 onError 里统一清理
                WebSockets.sendText(text, channel, null);
            }
        }
    }

    /**
     * 主动关闭连接，并让对端知道原因。
     */
    public void close(String reason) {
        synchronized (sendLock) {
            if (channel.isOpen()) {
                WebSockets.sendClose(CLOSE_POLICY_VIOLATION, reason, channel, null);
            }
        }
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    public WebSocketChannel getChannel() {
        return channel;
    }

    public String getUsername() {
        return username;
    }

    public long getConnectedAt() {
        return connectedAt;
    }
}
