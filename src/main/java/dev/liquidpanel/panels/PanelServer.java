package dev.liquidpanel.panels;

import dev.liquidpanel.panels.http.HttpUtil;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;

/**
 * 网页面板服务器，对 Undertow 的薄封装。
 *
 * <p>Undertow 自带 IO 线程与工作线程两套线程池，与 Minecraft 主线程完全独立，
 * 所以面板的请求处理、WebSocket 收发都不会占用服务端的 tick 时间。
 *
 * <p>本类只管启停与端口绑定，路由交给 {@link PanelRouter}。
 */
public final class PanelServer {

    /**
     * 连接空闲多久后断开（毫秒）。
     *
     * <p>这是半开连接 DoS 的关键防线：攻击者声明一个 {@code Content-Length} 却一个字节都不发，
     * 工作线程会卡在读请求体上。Undertow 把本项挂到连接的读超时管道上，
     * 因此「正在等请求体」的空闲连接同样会被断开。
     */
    private static final int IDLE_TIMEOUT_MILLIS = 30_000;

    /** 请求头必须在这个时限内发完，否则断开 */
    private static final int NO_REQUEST_TIMEOUT_MILLIS = 10_000;

    /** 请求解析总时限 */
    private static final int REQUEST_PARSE_TIMEOUT_MILLIS = 10_000;

    /** 请求体上限，与 HttpUtil.MAX_BODY_BYTES 对齐，超出由 Undertow 在协议层直接拒绝 */
    private static final long MAX_ENTITY_SIZE_BYTES = HttpUtil.MAX_BODY_BYTES;

    /** IO 线程数：与 CPU 核数相当即可 */
    private static final int IO_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    /**
     * 工作线程数上限。
     *
     * <p>默认值是 CPU 核数 × 8，在 64 核机器上会开出 512 条线程。
     * 这里封顶，但不会低于默认值，避免反而降低并发能力。
     */
    private static final int WORKER_THREADS =
            Math.min(64, Math.max(16, Runtime.getRuntime().availableProcessors() * 8));

    private final PanelSettings settings;
    private final HttpHandler handler;

    /** 未运行时为 null */
    private volatile Undertow undertow;

    /** 当前实际监听的地址，重启判断用 */
    private volatile String boundHost;
    private volatile int boundPort;

    public PanelServer(PanelSettings settings, PanelRouter router) {
        this.settings = settings;
        // 线程派发由 PanelRouter 内部按路径决定：接口/静态资源进工作线程，
        // WebSocket 升级留在 IO 线程。这里直接用路由本身即可。
        this.handler = router;
    }

    /**
     * 启动服务器。
     *
     * @return 启动成功返回 true；端口被占用等失败情况返回 false
     */
    public synchronized boolean start() {
        if (isRunning()) {
            return true;
        }

        String host = settings.getHost();
        int port = settings.getPort();

        try {
            Undertow server = Undertow.builder()
                    .addHttpListener(port, host)
                    .setHandler(handler)
                    // 面板跑在明文 HTTP 上，关掉 HTTP/2 让行为可预期
                    .setServerOption(UndertowOptions.ENABLE_HTTP2, false)
                    // 以下四项 Undertow 默认都是 -1（即不限制），必须显式设置，
                    // 否则未认证者用几十条不发数据的连接就能占满工作线程，让整个面板失去响应。
                    .setServerOption(UndertowOptions.IDLE_TIMEOUT, IDLE_TIMEOUT_MILLIS)
                    .setServerOption(UndertowOptions.NO_REQUEST_TIMEOUT, NO_REQUEST_TIMEOUT_MILLIS)
                    .setServerOption(UndertowOptions.REQUEST_PARSE_TIMEOUT, REQUEST_PARSE_TIMEOUT_MILLIS)
                    .setServerOption(UndertowOptions.MAX_ENTITY_SIZE, MAX_ENTITY_SIZE_BYTES)
                    .setIoThreads(IO_THREADS)
                    .setWorkerThreads(WORKER_THREADS)
                    .build();
            server.start();

            this.undertow = server;
            this.boundHost = host;
            this.boundPort = port;
            return true;
        } catch (Exception e) {
            this.undertow = null;
            this.boundHost = null;
            this.boundPort = 0;
            throw new PanelStartException(host + ":" + port + " -> " + e.getMessage(), e);
        }
    }

    /**
     * 停止服务器。
     */
    public synchronized void stop() {
        Undertow server = this.undertow;
        if (server == null) {
            return;
        }
        this.undertow = null;
        try {
            server.stop();
        } catch (Exception ignored) {
            // 停止过程中的异常没有补救价值，忽略
        }
    }

    /**
     * 监听参数是否与当前运行的一致。
     */
    public boolean isBoundTo(String host, int port) {
        return isRunning() && host.equals(boundHost) && port == boundPort;
    }

    public boolean isRunning() {
        return undertow != null;
    }

    public int getBoundPort() {
        return boundPort;
    }

    /**
     * 启动失败的包装异常，方便上层区分「端口冲突」这类可预期错误。
     */
    public static final class PanelStartException extends RuntimeException {

        public PanelStartException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
