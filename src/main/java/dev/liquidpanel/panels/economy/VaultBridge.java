package dev.liquidpanel.panels.economy;

import dev.liquidpanel.configs.MessagesManager;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 的实际调用点。
 *
 * <p>这是整个插件里<b>唯一</b>引用 Vault 类型的地方，而且只在
 * {@link EconomyService} 确认服务端装了 Vault 之后才会被加载。
 * 为什么要这样绕，那边的类注释里写了。
 *
 * <p>每个方法都兜住 {@code Throwable}：经济插件的实现五花八门，
 * 一个玩家的余额读不出来不该让整次玩家列表采集失败。
 */
final class VaultBridge {

    private final Economy economy;

    private VaultBridge(Economy economy) {
        this.economy = economy;
    }

    /**
     * 建立连接。
     *
     * @return Vault 装了、但没有任何经济插件向它注册时返回 null
     */
    static VaultBridge create() {
        RegisteredServiceProvider<Economy> registration =
                Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || registration.getProvider() == null) {
            return null;
        }
        return new VaultBridge(registration.getProvider());
    }

    /** 经济插件的名字，用于日志 */
    String name() {
        try {
            return economy.getName();
        } catch (Throwable e) {
            return "未知";
        }
    }

    double balance(OfflinePlayer player) {
        try {
            return economy.getBalance(player);
        } catch (Throwable e) {
            return 0D;
        }
    }

    /**
     * 格式化金额。
     *
     * <p>交给经济插件自己做：是「$100」还是「100 金币」只有它知道。
     */
    String format(double amount) {
        try {
            return economy.format(amount);
        } catch (Throwable e) {
            return String.valueOf(amount);
        }
    }

    EconomyService.Result deposit(OfflinePlayer player, double amount) {
        try {
            return toResult(economy.depositPlayer(player, amount));
        } catch (Throwable e) {
            MessagesManager.logThrowable("存入余额失败", e);
            return EconomyService.Result.failure(describe(e, "存入余额失败"));
        }
    }

    EconomyService.Result withdraw(OfflinePlayer player, double amount) {
        try {
            return toResult(economy.withdrawPlayer(player, amount));
        } catch (Throwable e) {
            MessagesManager.logThrowable("扣除余额失败", e);
            return EconomyService.Result.failure(describe(e, "扣除余额失败"));
        }
    }

    /**
     * 把 Vault 的返回转成我们自己的结果类型。
     *
     * <p>不直接把 {@code EconomyResponse} 往外传，是为了让
     * {@link EconomyService} 的字节码里不出现 Vault 的类型 ——
     * 一旦出现，它就得跟着 {@code VaultBridge} 一起被隔离掉。
     */
    private EconomyService.Result toResult(EconomyResponse response) {
        if (response == null) {
            return EconomyService.Result.failure("经济插件没有返回结果");
        }
        if (response.transactionSuccess()) {
            return new EconomyService.Result(true, response.balance, null);
        }
        return EconomyService.Result.failure(
                response.errorMessage == null ? "操作被经济插件拒绝" : response.errorMessage);
    }

    private String describe(Throwable e, String fallback) {
        return e.getMessage() == null ? fallback : e.getMessage();
    }
}
