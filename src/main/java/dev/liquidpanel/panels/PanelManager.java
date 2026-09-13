package dev.liquidpanel.panels;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.database.DatabaseManager;
import dev.liquidpanel.panels.economy.EconomyService;
import dev.liquidpanel.panels.http.AuthHandler;
import dev.liquidpanel.panels.files.ArchiveService;
import dev.liquidpanel.panels.files.FileService;
import dev.liquidpanel.panels.files.ServerPaths;
import dev.liquidpanel.panels.http.CommandHandler;
import dev.liquidpanel.panels.http.FileHandler;
import dev.liquidpanel.panels.http.LogHandler;
import dev.liquidpanel.panels.http.PlayerHandler;
import dev.liquidpanel.panels.http.SettingsHandler;
import dev.liquidpanel.panels.players.BanService;
import dev.liquidpanel.panels.players.DataRevision;
import dev.liquidpanel.panels.players.PlayerRegistry;
import dev.liquidpanel.panels.players.PlayerService;
import dev.liquidpanel.panels.players.SkinService;
import dev.liquidpanel.panels.settings.PanelConfigManager;
import dev.liquidpanel.panels.http.StatusHandler;
import dev.liquidpanel.panels.logs.ConsoleLogCollector;
import dev.liquidpanel.panels.logs.ConsoleLogStore;
import dev.liquidpanel.panels.metrics.SystemMetrics;
import dev.liquidpanel.panels.security.AccountManager;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.LoginGuard;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.panels.ws.PanelWebSocketHandler;
import dev.liquidpanel.panels.ws.WebSocketHub;
import dev.liquidpanel.utils.BrowserUtil;
import dev.liquidpanel.utils.JsonUtil;
import dev.liquidpanel.utils.TaskUtil;
import io.undertow.Handlers;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网页面板总管理器。
 *
 * <p>负责把各个零件组装起来（账户 / 会话 / 防爆破 / 路由 / WebSocket），
 * 并管理整个面板的生命周期：启动、停止、重载、状态推送。
 *
 * <p>主类只需要调用 {@link #init()}、{@link #reload()}、{@link #shutdown()} 三个方法。
 */
public final class PanelManager {

    /** 状态推送间隔（游戏刻），40 刻 = 2 秒 */
    private static final long PUSH_INTERVAL_TICKS = 40L;

    /** 维护任务间隔（游戏刻），每 5 分钟清理一次过期会话与失败记录 */
    private static final long MAINTENANCE_INTERVAL_TICKS = 6000L;

    /** 日志推送间隔（游戏刻），10 刻 = 0.5 秒。日志和指标不同，慢了就没有「实时」的感觉 */
    private static final long LOG_PUSH_INTERVAL_TICKS = 10L;

    /**
     * 玩家列表推送间隔（游戏刻），3 刻 ≈ 0.15 秒。
     *
     * <p>玩家坐标一直在动，传送到 {@code ~ ~10 ~} 这类相对坐标时，
     * 弹窗里显示的坐标必须是新的，慢了就等于给了个过时的参考值。
     * 这份数据很小（每个玩家几个字段），单独高频推，不拖累 2 秒一次的整体状态。
     */
    private static final long PLAYER_PUSH_INTERVAL_TICKS = 3L;

    /**
     * 临时封禁到期检查间隔（游戏刻），200 刻 = 10 秒。
     *
     * <p>到期时间本身只是给管理员填的一个大概时长，
     * 多等十秒对「封三天」没有任何意义，而每次检查都要读一遍存储，
     * 频率再高只是白烧 IO。
     */
    private static final long BAN_CHECK_INTERVAL_TICKS = 200L;

    private final JavaPlugin plugin;

    private final PanelSettings settings = new PanelSettings();
    private final WebAssetManager assetManager;
    private final AccountManager accountManager;
    private final SessionManager sessionManager = new SessionManager();
    private final LoginGuard loginGuard = new LoginGuard();
    private final HostValidator hostValidator = new HostValidator();
    private final WebSocketHub webSocketHub = new WebSocketHub();
    private final SystemMetrics systemMetrics;
    private final ConsoleLogCollector consoleLog;
    private final ServerPaths serverPaths;
    private final PanelConfigManager panelConfig;
    private final EconomyService economyService;
    private final DatabaseManager database;
    private final SkinService skinService = new SkinService();

    /**
     * 名册与封禁状态的版本号。名册那边由上下线事件推高，
     * 封禁那边由封禁 / 解封 / 到期解除推高，最后跟着状态推送发给前端。
     */
    private final DataRevision dataRevision = new DataRevision();

    /*
     * 下面这一串的依赖链是 database -> PlayerRegistry -> PlayerService
     * -> PanelStatusCollector -> PanelStatusProvider，全都要等存储开好之后才能建，
     * 所以放在 init() 里而不是构造器里，也都不是 final。
     */
    private PlayerRegistry playerRegistry;
    private PlayerService playerService;
    private PanelStatusCollector statusCollector;
    private PanelStatusProvider statusProvider;
    private BanService banService;

    private PanelServer server;
    private BukkitTask pushTask;
    private BukkitTask maintenanceTask;
    private BukkitTask logTask;
    private BukkitTask playerTask;
    private BukkitTask banTask;

    public PanelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.assetManager = new WebAssetManager(plugin);
        this.accountManager = new AccountManager(plugin);
        this.systemMetrics = new SystemMetrics(resolveServerRoot());
        this.consoleLog = new ConsoleLogCollector();
        this.serverPaths = resolvePaths();
        this.panelConfig = new PanelConfigManager(plugin);
        this.economyService = new EconomyService(panelConfig);
        this.database = new DatabaseManager(plugin);
    }

    /** 依赖存储的那些组件，统一在这里按依赖顺序建起来 */
    private void buildStores() {
        database.init();

        playerRegistry = new PlayerRegistry(database.playerStore(), dataRevision);
        playerRegistry.init(plugin);

        playerService = new PlayerService(playerRegistry, database.banStore(), economyService);
        banService = new BanService(database.banStore(), panelConfig, dataRevision);

        statusCollector = new PanelStatusCollector(playerService, dataRevision, economyService);
        statusProvider = new PanelStatusProvider(statusCollector, systemMetrics);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 初始化并启动面板，由主类在 onEnable 中调用。
     */
    public void init() {
        settings.reload();
        applySettings();

        // 账户文件无论面板是否启用都先生成，方便管理员提前拿到密码
        accountManager.init();
        // 面板运行时设置（网页上直接改的那些），不存在就写一份默认的
        panelConfig.init();

        // 存储放前面：它跟面板开关无关 —— 面板关着的时候，
        // 已经记下的临时封禁照样要到期解除，名册也照样要维护
        buildStores();

        if (!settings.isEnabled()) {
            MessagesManager.log("panel.server.disabled");
            return;
        }
        start();
    }

    /**
     * 重新载入面板配置。监听地址没变就不重启服务器，避免无谓地断开所有连接。
     */
    public void reload() {
        String oldHost = settings.getHost();
        int oldPort = settings.getPort();
        boolean wasEnabled = settings.isEnabled();

        settings.reload();
        applySettings();

        if (!settings.isEnabled()) {
            if (wasEnabled) {
                stop();
                MessagesManager.log("panel.server.disabled");
            }
            return;
        }

        boolean endpointChanged = !settings.sameEndpoint(oldHost, oldPort);
        if (isRunning() && !endpointChanged) {
            return;
        }
        if (isRunning()) {
            stop();
        }
        start();
    }

    /**
     * 关闭面板，由主类在 onDisable 中调用。
     */
    public void shutdown() {
        stop();
        // 名册在这里收尾而不是 stop() 里：面板停掉不代表玩家不来了，
        // 名册要一直维护到插件卸载为止
        if (playerRegistry != null) {
            playerRegistry.stop();
        }
        // 存储只在关服时收尾：reload 会把面板停掉再拉起来，
        // 不该顺带把库关一遍又开一遍
        database.close();
    }

    /**
     * 启动 HTTP / WebSocket 服务器。
     */
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        if (server == null) {
            server = new PanelServer(settings, buildRouter());
        }
        try {
            server.start();
        } catch (PanelServer.PanelStartException e) {
            MessagesManager.log("panel.server.start_failed", "error", e.getMessage());
            return;
        }

        scheduleTasks();
        statusCollector.start(plugin);
        systemMetrics.start(plugin);
        consoleLog.install();
        MessagesManager.log("panel.server.started", "url", settings.browseUrl());
    }

    /**
     * 停止服务器并回收所有后台任务与连接。
     */
    public synchronized void stop() {
        cancelTasks();
        // 挂在调度器上的任务必须一起停掉。
        // statusCollector 是 init() 里才建的，加个判空兜住「init 没跑完就被停」的极端情况
        if (statusCollector != null) {
            statusCollector.stop();
        }
        systemMetrics.stop();
        consoleLog.uninstall();
        webSocketHub.closeAll("面板已关闭");

        if (server != null) {
            server.stop();
            MessagesManager.log("panel.server.stopped");
        }
    }

    public boolean isRunning() {
        return server != null && server.isRunning();
    }

    /**
     * 服务端根目录：插件数据目录是 {@code <服务端>/plugins/LiquidPanel}，往上两级即是。
     * 世界文件夹与「服务端所在盘符」都以它为基准。
     *
     * <p>必须先 {@code getAbsoluteFile()} 再取父目录：若数据目录拿到的是相对路径
     * （只有一层父目录），{@code getParentFile()} 会返回 null，
     * 结果退化成在插件目录里找 world，三个世界文件夹就全被判成「不存在」。
     */
    private File resolveServerRoot() {
        File dataFolder = plugin.getDataFolder().getAbsoluteFile();
        File plugins = dataFolder.getParentFile();
        File root = plugins == null ? null : plugins.getParentFile();
        return root == null ? dataFolder : root;
    }

    /**
     * 文件管理的安全边界。根目录解析失败（正常不会发生）时返回 null，
     * 此时文件相关的接口一律回 503，不影响面板其它功能。
     */
    private ServerPaths resolvePaths() {
        try {
            return new ServerPaths(resolveServerRoot());
        } catch (Exception e) {
            MessagesManager.logRaw("§c无法解析服务端根目录，文件管理已禁用: " + e.getMessage());
            return null;
        }
    }

    /**
     * 把配置同步到各个组件。init 与 reload 都走这里，避免两边漏掉某一项。
     */
    private void applySettings() {
        sessionManager.configure(settings.getSessionTimeoutMillis(), settings.isBindSessionIp());
        loginGuard.configure(
                settings.getMaxFailures(),
                settings.getFailWindowMinutes(),
                settings.getBlockMinutes(),
                settings.getMaxConcurrentVerifications());
        // Host 白名单由配置与网卡共同决定，两者任一变化都要重建
        hostValidator.refresh(settings);
    }

    // ------------------------------------------------------------------
    // 对外能力
    // ------------------------------------------------------------------

    /**
     * 在服务端所在的机器上打开浏览器访问面板。
     *
     * <p>唤起浏览器要启动外部进程，是阻塞操作，所以丢到异步线程；
     * 面板没起来就先拉起来，起不来就如实告诉管理员手动访问。
     */
    public void openInBrowser(CommandSender sender) {
        if (!settings.isEnabled()) {
            MessagesManager.send(sender, "panel.server.disabled");
            return;
        }
        if (!isRunning()) {
            start();
            if (!isRunning()) {
                MessagesManager.send(sender, "panel.server.not_running");
                return;
            }
        }

        String url = settings.browseUrl();
        MessagesManager.send(sender, "panel.browser.opening", "url", url);

        TaskUtil.runAsync(() -> {
            boolean opened = BrowserUtil.open(url);
            if (!opened) {
                // 服务端通常没有桌面环境，这种情况引导管理员手动访问
                TaskUtil.runSync(() -> MessagesManager.send(sender, "panel.browser.failed", "url", url));
            }
        });
    }

    // ------------------------------------------------------------------
    // 组装
    // ------------------------------------------------------------------

    private PanelRouter buildRouter() {
        AuthHandler authHandler = new AuthHandler(accountManager, sessionManager, loginGuard, settings, hostValidator);
        StatusHandler statusHandler = new StatusHandler(sessionManager, statusProvider);
        LogHandler logHandler = new LogHandler(sessionManager, consoleLog);
        CommandHandler commandHandler = new CommandHandler(sessionManager, hostValidator, plugin);
        FileHandler fileHandler = new FileHandler(sessionManager, hostValidator, serverPaths,
                new FileService(serverPaths), new ArchiveService());
        PlayerHandler playerHandler = new PlayerHandler(sessionManager, hostValidator,
                playerService, skinService, banService, economyService);
        SettingsHandler settingsHandler = new SettingsHandler(sessionManager, hostValidator,
                panelConfig, economyService);
        PanelWebSocketHandler webSocketHandler =
                new PanelWebSocketHandler(sessionManager, webSocketHub, hostValidator);

        return new PanelRouter(
                assetManager,
                authHandler,
                statusHandler,
                logHandler,
                commandHandler,
                fileHandler,
                playerHandler,
                settingsHandler,
                sessionManager,
                Handlers.websocket(webSocketHandler));
    }

    // ------------------------------------------------------------------
    // 定时任务：全部跑在 Bukkit 异步线程池上，不占用主线程
    // ------------------------------------------------------------------

    private void scheduleTasks() {
        cancelTasks();

        pushTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pushStatus, PUSH_INTERVAL_TICKS, PUSH_INTERVAL_TICKS);

        maintenanceTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::runMaintenance, MAINTENANCE_INTERVAL_TICKS, MAINTENANCE_INTERVAL_TICKS);

        logTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pushLogs, LOG_PUSH_INTERVAL_TICKS, LOG_PUSH_INTERVAL_TICKS);

        playerTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::pushPlayers, PLAYER_PUSH_INTERVAL_TICKS, PLAYER_PUSH_INTERVAL_TICKS);

        banTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                plugin, this::liftExpiredBans, BAN_CHECK_INTERVAL_TICKS, BAN_CHECK_INTERVAL_TICKS);
    }

    private void cancelTasks() {
        if (pushTask != null) {
            pushTask.cancel();
            pushTask = null;
        }
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
        if (logTask != null) {
            logTask.cancel();
            logTask = null;
        }
        if (playerTask != null) {
            playerTask.cancel();
            playerTask = null;
        }
        if (banTask != null) {
            banTask.cancel();
            banTask = null;
        }
    }

    /**
     * 高频推送在线玩家，供玩家列表与传送弹窗实时刷新。
     */
    private void pushPlayers() {
        if (webSocketHub.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "players");
        payload.put("players", playerService.listOnline());
        webSocketHub.broadcast(JsonUtil.toJson(payload));
    }

    /**
     * 把新增的控制台日志推给所有连接。
     *
     * <p>没人连着就不 drain，让 tailer 自己的环形缓冲兜住；
     * 否则行会一直堆在待推送队列里。
     */
    private void pushLogs() {
        if (webSocketHub.isEmpty()) {
            return;
        }
        List<ConsoleLogStore.LogLine> lines = consoleLog.drainPending();
        if (lines.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "log");
        payload.put("lines", lines);
        webSocketHub.broadcast(JsonUtil.toJson(payload));
    }

    /**
     * 向所有 WebSocket 连接推送一次状态。
     */
    private void pushStatus() {
        // 没人连着就完全不去打扰主线程
        if (webSocketHub.isEmpty()) {
            return;
        }

        // provider 内部负责切主线程取 Bukkit 数据，并合并机器级指标。
        // 推送只带最新采样点，历史在首次加载时已经给过前端了。
        Map<String, Object> status = statusProvider.collect(false);
        if (status == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "status");
        payload.put("data", status);
        webSocketHub.broadcast(JsonUtil.toJson(payload));
    }

    /**
     * 解除到期的临时封禁。
     *
     * <p>跑在异步线程上：读存储是阻塞 IO，切主线程执行解除动作由
     * {@link BanService} 自己负责。
     */
    private void liftExpiredBans() {
        if (banService != null) {
            banService.liftExpired();
        }
    }

    private void runMaintenance() {
        sessionManager.cleanup();
        loginGuard.cleanup();
        webSocketHub.cleanup();
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public PanelSettings getSettings() {
        return settings;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public WebSocketHub getWebSocketHub() {
        return webSocketHub;
    }

    public AccountManager getAccountManager() {
        return accountManager;
    }

    public WebAssetManager getAssetManager() {
        return assetManager;
    }
}
