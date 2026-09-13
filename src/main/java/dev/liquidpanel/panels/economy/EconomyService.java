package dev.liquidpanel.panels.economy;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.settings.PanelConfigManager;
import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 经济系统的接入层。
 *
 * <p><b>这个类里刻意不出现任何 Vault 的类型。</b>真正的调用全在 {@link VaultBridge} 里，
 * 而那个类只有在确认服务端装了 Vault 之后才会被加载。
 *
 * <p>绕这一下是因为软依赖的经典陷阱：只要本类的字节码里出现
 * {@code net.milkbowl.vault.economy.Economy} 这个符号，加载本类时 JVM 就会去解析它。
 * 服务端没装 Vault 时解析失败，直接抛 {@code NoClassDefFoundError}，
 * 整个面板跟着起不来 —— 而我们想做的只是「装了就用、没装就当没这功能」。
 *
 * <p>配合 plugin.yml 里的 {@code softdepend: [Vault]}，装了 Vault 时它先加载，
 * 我们的类加载器才看得到它那些类。
 */
public final class EconomyService {

    /** 探测失败后多久再试一次（毫秒）。Vault 与具体经济插件的加载顺序不归我们管 */
    private static final long PROBE_RETRY_MILLIS = 10_000L;

    /** 切主线程执行经济操作的等待上限 */
    private static final long TIMEOUT_MILLIS = 3000L;

    private final PanelConfigManager config;

    /** null 表示当前用不了。赋值与读取都在主线程，用 volatile 只是保险 */
    private volatile VaultBridge bridge;

    private volatile long lastProbeAt;

    /** 探测失败的提示只打一次，不然每十秒刷一行 */
    private final AtomicBoolean warned = new AtomicBoolean();

    public EconomyService(PanelConfigManager config) {
        this.config = config;
    }

    // ------------------------------------------------------------------
    // 可用性
    // ------------------------------------------------------------------

    /**
     * 经济功能是否可用。设置页打开开关、且服务端确实有 Vault 与经济插件时为 true。
     */
    public boolean isAvailable() {
        return bridge() != null;
    }

    /**
     * 设置页改了开关之后调一次，让改动立刻生效。
     */
    public void refresh() {
        bridge = null;
        lastProbeAt = 0L;
        warned.set(false);

        // 立刻探一次，让控制台马上反映出结果。
        // 探测要碰 Bukkit 的服务注册表，切主线程做 —— 这个方法的调用方是网页请求线程
        TaskUtil.runSync(() -> bridge());
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 查余额。取不到一律返回 0，不抛异常打扰调用方。
     */
    public double balance(OfflinePlayer player) {
        VaultBridge current = bridge();
        if (current == null || player == null) {
            return 0D;
        }
        return current.balance(player);
    }

    /**
     * 把钱格式化好（带货币符号或单位）。
     *
     * <p>交给经济插件自己做：不同插件用的是「$100」还是「100 金币」只有它知道。
     */
    public String format(double amount) {
        VaultBridge current = bridge();
        return current == null ? String.valueOf(amount) : current.format(amount);
    }

    /**
     * 给玩家加钱。
     *
     * <p>按名字找人不要求在线 —— 经济插件都是按 UUID 记账的，
     * 只要这个玩家在本服玩过，离线也能收到钱。
     */
    public Result deposit(String playerName, double amount) {
        return onMainThread(() -> {
            VaultBridge current = bridge();
            if (current == null) {
                return Result.failure("经济系统不可用");
            }
            OfflinePlayer player = resolve(playerName);
            return player == null ? unknownPlayer(playerName) : current.deposit(player, amount);
        });
    }

    /**
     * 扣玩家的钱。余额不足时由经济插件拒绝，错误原因会原样带回来。
     */
    public Result withdraw(String playerName, double amount) {
        return onMainThread(() -> {
            VaultBridge current = bridge();
            if (current == null) {
                return Result.failure("经济系统不可用");
            }
            OfflinePlayer player = resolve(playerName);
            return player == null ? unknownPlayer(playerName) : current.withdraw(player, amount);
        });
    }

    /**
     * 把余额直接设成指定数额。
     *
     * <p>Vault 没有「设置余额」这个操作，只能先读当前值、再补差值 ——
     * 多退少补，效果一样。
     */
    public Result set(String playerName, double amount) {
        return onMainThread(() -> {
            VaultBridge current = bridge();
            if (current == null) {
                return Result.failure("经济系统不可用");
            }
            OfflinePlayer player = resolve(playerName);
            if (player == null) {
                return unknownPlayer(playerName);
            }

            double currentBalance = current.balance(player);
            double difference = amount - currentBalance;

            // 浮点误差之内就当已经相等，别做一次没有意义的写操作
            if (Math.abs(difference) < 0.0001D) {
                return new Result(true, currentBalance, null);
            }
            return difference > 0
                    ? current.deposit(player, difference)
                    : current.withdraw(player, -difference);
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 取当前的经济实现，必要时探一次。
     *
     * <p>探测失败会隔十秒重试：本插件 onEnable 的时候，Vault 或经济插件
     * 很可能还没把服务注册上去，一次探不到就永远放弃是不对的。
     */
    private VaultBridge bridge() {
        if (!config.get().vaultEnabled) {
            return null;
        }

        VaultBridge current = bridge;
        if (current != null) {
            return current;
        }

        long now = System.currentTimeMillis();
        if (now - lastProbeAt < PROBE_RETRY_MILLIS) {
            return null;
        }
        lastProbeAt = now;
        probe();
        return bridge;
    }

    private void probe() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            warnOnce("已开启 Vault 支持，但服务端上没有装 Vault");
            return;
        }

        try {
            VaultBridge created = VaultBridge.create();
            if (created == null) {
                warnOnce("Vault 已装，但还没有任何经济插件向它注册（例如 EssentialsX、CMI）");
                return;
            }
            bridge = created;
            MessagesManager.logRaw("§7经济系统已接入：§f" + created.name());
        } catch (Throwable e) {
            warnOnce("接入 Vault 经济失败：" + e);
        }
    }

    private void warnOnce(String message) {
        if (warned.compareAndSet(false, true)) {
            MessagesManager.logRaw("§e" + message);
        }
    }

    /**
     * 按名字找玩家。
     *
     * <p>先看在线列表，命中就不用走下面那步可能读 usercache 的查找。
     * 找不到再按名字取来，但<b>要求他确实在本服玩过</b> ——
     * 否则等于凭空造一个没人认领的账户出来，钱就丢进虚空了。
     */
    private OfflinePlayer resolve(String playerName) {
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) {
            return online;
        }

        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerName);
        return offline.hasPlayedBefore() ? offline : null;
    }

    private Result unknownPlayer(String playerName) {
        return Result.failure("服务端不认识玩家 " + playerName + "，无法操作他的余额");
    }

    /**
     * 把操作挪到主线程执行。
     *
     * <p>Bukkit 的玩家查找与 Vault 的经济接口都该在主线程调，而调用方
     * 可能是 Undertow 的工作线程。已经在主线程时 {@code supplySync}
     * 会直接执行，不会绕一圈调度，所以从主线程调它也是安全的。
     */
    private Result onMainThread(Supplier<Result> action) {
        CompletableFuture<Result> future = TaskUtil.supplySync(action);
        try {
            return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            return Result.failure(cause == null || cause.getMessage() == null
                    ? "操作失败"
                    : cause.getMessage());
        } catch (Exception e) {
            return Result.failure("操作超时，服务端可能正在卡顿");
        }
    }

    /**
     * 一次经济操作的结果。
     *
     * @param success 经济插件是否认可这次操作
     * @param balance 操作之后的余额
     * @param error   失败原因，成功时为 null
     */
    public record Result(boolean success, double balance, String error) {

        static Result failure(String message) {
            return new Result(false, 0D, message);
        }
    }
}
