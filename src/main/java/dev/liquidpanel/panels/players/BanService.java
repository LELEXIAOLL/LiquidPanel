package dev.liquidpanel.panels.players;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.database.BanRecord;
import dev.liquidpanel.panels.database.BanStore;
import dev.liquidpanel.panels.settings.PanelConfigManager;
import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 封禁的执行与到期解除。
 *
 * <p>按设置页选定的 {@link BanMethod} 决定把动作交给谁：
 * 服务端自带的封禁名单、LiteBans / AdvancedBan，或者管理员自己写的命令。
 * 三种方式都会往 {@link BanStore} 里落一条记录，临时封禁的到期解除靠它。
 *
 * <p><b>三种方式的临时封禁都由这里的定时任务负责到期解除。</b>
 * 原版方式也只写一条永久封禁进服务端名单，到期时间只记在面板自己的存储里 ——
 * 这样到期逻辑只有一处，不会出现「原版封禁等服务端解、插件封禁等面板解」两套行为。
 *
 * <p>代价是临时封禁离不开存储：没有到期时间，硬执行就等于把人永久封了，
 * 所以存储不可用时临时封禁会被直接拒绝，宁可封不出去。
 */
public final class BanService {

    /** 写进封禁名单的签发者名字，会在某些插件的提示里显示出来 */
    private static final String SOURCE = "LiquidPanel";

    /** 管理员没填原因时的兜底，玩家能直接看到 */
    private static final String DEFAULT_REASON_DISPLAY = "你已被管理员封禁";

    /** 管理员没填原因时塞进命令的占位文本 */
    private static final String DEFAULT_REASON_PLAIN = "未填写原因";

    /** 切主线程执行命令的等待上限，与 PlayerService 保持一致 */
    private static final long COMMAND_TIMEOUT_MILLIS = 3000L;

    private final BanStore store;
    private final PanelConfigManager config;

    /** 封禁状态一变就加一，前端据此刷新列表上的「已封禁」标记与解封按钮 */
    private final DataRevision revision;

    public BanService(BanStore store, PanelConfigManager config, DataRevision revision) {
        this.store = store;
        this.config = config;
        this.revision = revision;
    }

    // ------------------------------------------------------------------
    // 封禁
    // ------------------------------------------------------------------

    /**
     * 封禁一名在线玩家。
     *
     * @param temporary 是否为临时封禁。为 false 时忽略 {@code duration}
     * @param duration  封禁时长，临时封禁时必须有值
     * @param operator  操作者，写进记录
     */
    public void ban(String name, String reason, boolean temporary, BanDuration duration, String operator) {
        BanMethod method = currentMethod();
        String trimmedReason = reason == null ? "" : reason.trim();

        if (temporary) {
            if (duration == null || duration.isZero()) {
                throw new PlayerService.PlayerActionException("临时封禁需要填写时长");
            }
            // 三种方式都是「写一条永久封禁 + 面板记下到期时间」，
            // 没有存储就没有到期时间，硬执行等于把临时封禁变成永久封禁
            if (store == null) {
                throw new PlayerService.PlayerActionException(
                        "封禁记录存储不可用，无法登记到期时间，临时封禁已取消");
            }
        } else {
            duration = BanDuration.ZERO;
        }

        long now = System.currentTimeMillis();
        long expiresAt = temporary ? now + duration.millis() : 0L;

        removeRecords(name, false);

        switch (method) {
            case VANILLA -> applyVanilla(name, displayReason(trimmedReason));
            case PLUGIN -> dispatchAsConsole(pluginCommand(name, trimmedReason, temporary, duration));
            case CUSTOM -> dispatchAsConsole(customCommand(name, trimmedReason, temporary, duration));
        }

        record(name, trimmedReason, operator, method, now, expiresAt, temporary);
        revision.bump();
    }

    // ------------------------------------------------------------------
    // 解封
    // ------------------------------------------------------------------

    /**
     * 手动解除封禁。
     *
     * <p>按<b>当前设置</b>的方式解，这点和到期解除不同 ——
     * 那边按记录当时用的方式解（管理员中途换过方式的话，用新方式解旧封禁是解不掉的），
     * 而手动解封是管理员当面做的决定，用的自然是他现在选的这一套。
     *
     * <p>解封动作成功之后才清记录。反过来先清记录的话，
     * 一旦解封失败，这条封禁就彻底没人管了。
     */
    public void unban(String name) {
        try {
            applyUnban(name, currentMethod());
        } catch (PlayerService.PlayerActionException e) {
            throw e;
        } catch (Exception e) {
            throw new PlayerService.PlayerActionException("解除封禁超时，服务端可能正在卡顿");
        }
        removeRecords(name, true);
        revision.bump();
    }

    /**
     * 执行解封动作本身，不碰记录。
     *
     * <p>必须等结果：解封要是没成功却把记录删了，这条封禁就再没人管了。
     */
    private void applyUnban(String player, BanMethod method) throws Exception {
        switch (method) {
            // 直接用名字解，不要求玩家在线
            case VANILLA -> TaskUtil
                    .runSync(() -> Bukkit.getBanList(BanList.Type.NAME).pardon(player))
                    .get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            case PLUGIN -> dispatchAsConsole("unban " + player);
            case CUSTOM -> dispatchAsConsole(
                    applyPlaceholders(config.get().banCommandUnban, player, "", ""));
        }
    }

    // ------------------------------------------------------------------
    // 到期解除
    // ------------------------------------------------------------------

    /**
     * 扫一遍记录，把已经到期的临时封禁解除掉。由定时任务调用。
     *
     * @return 本次解除了几条
     */
    public int liftExpired() {
        if (store == null) {
            return 0;
        }

        List<BanRecord> records;
        try {
            records = store.all();
        } catch (Exception e) {
            MessagesManager.logRaw("§c读取封禁记录失败，本轮到期检查跳过: " + e.getMessage());
            return 0;
        }

        long now = System.currentTimeMillis();
        int lifted = 0;
        for (BanRecord record : records) {
            if (record == null || !record.isExpired(now)) {
                continue;
            }
            try {
                lift(record);
                store.remove(record.id);
                lifted++;
                MessagesManager.logRaw("§7临时封禁到期，已解除：§f" + record.player
                        + " §7（原因：" + record.reason + "）");
            } catch (Exception e) {
                // 解不掉就留着，下一轮再试 —— 记录删了才是真的解不掉了
                MessagesManager.logRaw("§c解除 §f" + record.player + " §c的临时封禁失败: " + e.getMessage());
            }
        }

        if (lifted > 0) {
            revision.bump();
        }
        return lifted;
    }

    /**
     * 解除一条记录对应的封禁。
     *
     * <p>按<b>记录当时用的方式</b>来解，而不是当前设置里的方式 ——
     * 管理员中途换过封禁方式的话，用新方式去解旧封禁是解不掉的。
     */
    private void lift(BanRecord record) throws Exception {
        BanMethod method = BanMethod.parse(record.method);
        applyUnban(record.player, method == null ? currentMethod() : method);
    }

    // ------------------------------------------------------------------
    // 三种方式各自的执行
    // ------------------------------------------------------------------

    /**
     * 原版封禁：往服务端的封禁名单里加一条，写进 {@code banned-players.json}。
     *
     * <p>到期时间一律传 {@code null}，也就是对服务端而言这永远是永久封禁 ——
     * 临时封禁什么时候到期只记在面板的存储里，由 {@link #liftExpired()} 到点解除。
     *
     * <p><b>不要求玩家在线</b>：{@code addBan} 只认名字，不认实体。
     * 「玩家作弊后立刻退出、服主想追封」这种场景就靠它。
     * 人在线的话顺手踢下线，不在线就没有这一步。
     */
    private void applyVanilla(String name, String reason) {
        CompletableFuture<Void> future = TaskUtil.runSync(() -> {
            Bukkit.getBanList(BanList.Type.NAME).addBan(name, reason, null, SOURCE);

            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                player.kickPlayer(reason);
            }
        });

        try {
            future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new PlayerService.PlayerActionException(cause == null || cause.getMessage() == null
                    ? "封禁失败"
                    : cause.getMessage());
        } catch (Exception e) {
            throw new PlayerService.PlayerActionException("执行封禁超时，服务端可能正在卡顿");
        }
    }

    /**
     * 发给 LiteBans / AdvancedBan。这两家的命令语法一致，所以共用一种方式。
     */
    private String pluginCommand(String name, String reason, boolean temporary, BanDuration duration) {
        String text = reason.isEmpty() ? DEFAULT_REASON_PLAIN : reason;
        // 格式：tempban <玩家> <时长> <原因> / ban <玩家> <原因>
        return temporary
                ? "tempban " + name + " " + duration.format() + " " + text
                : "ban " + name + " " + text;
    }

    /**
     * 管理员自定义的命令，三个占位符分别替换成玩家名、原因、时长。
     *
     * <p>永久封禁时 {@code %time%} 替换成空串 —— 命令模板是两边共用的写法，
     * 用不到的那个占位符留着空着即可。
     */
    private String customCommand(String name, String reason, boolean temporary, BanDuration duration) {
        String template = temporary
                ? config.get().banCommandTemp
                : config.get().banCommandPerm;

        if (template == null || template.isBlank()) {
            throw new PlayerService.PlayerActionException(
                    "还没有配置" + (temporary ? "临时" : "永久") + "封禁命令，请到设置页的兼容性设置里填写");
        }

        String time = temporary ? duration.format() : "";
        return applyPlaceholders(template, name, reason.isEmpty() ? DEFAULT_REASON_PLAIN : reason, time);
    }

    private String applyPlaceholders(String template, String player, String reason, String time) {
        return template
                .replace("%player%", player)
                .replace("%reason%", reason)
                .replace("%time%", time);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 以控制台身份执行一条命令。
     *
     * <p>必须回主线程：Bukkit 的命令派发不是线程安全的。
     *
     * <p>这里刻意不用 {@code TaskUtil.awaitSync}：它分不清「命令不存在（返回 false）」
     * 和「执行时抛异常（返回兜底值）」，会把后者也报成前者。
     * 直接拿 Future 把 cause 解出来，管理员看到的才是真实原因。
     */
    private void dispatchAsConsole(String command) {
        String clean = command.startsWith("/") ? command.substring(1) : command;

        CompletableFuture<Boolean> future = TaskUtil.supplySync(
                () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean));

        boolean handled;
        try {
            handled = Boolean.TRUE.equals(future.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new PlayerService.PlayerActionException(cause == null || cause.getMessage() == null
                    ? "执行封禁命令时出错"
                    : cause.getMessage());
        } catch (Exception e) {
            throw new PlayerService.PlayerActionException("执行封禁命令超时，服务端可能正在卡顿");
        }

        if (!handled) {
            throw new PlayerService.PlayerActionException(
                    "服务端无法识别这条封禁命令，请检查命令名与写法：" + clean);
        }
    }

    /**
     * 清掉某个玩家的封禁记录。
     *
     * @param includeExpired 手动解封传 true，连过期的历史记录一起清；
     *                       重新封禁前传 false，只清还有效的那些 ——
     *                       否则反复封禁同一个人，会在到期扫描里留下多条记录各自去解一遍
     */
    private void removeRecords(String playerName, boolean includeExpired) {
        if (store == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            for (BanRecord record : store.all()) {
                if (record.player == null || !record.player.equalsIgnoreCase(playerName)) {
                    continue;
                }
                if (includeExpired || !record.isExpired(now)) {
                    store.remove(record.id);
                }
            }
        } catch (Exception e) {
            MessagesManager.logRaw("§c清理 " + playerName + " 的封禁记录失败: " + e.getMessage());
        }
    }

    /**
     * 落一条记录。
     *
     * <p>永久封禁写失败只记日志 —— 它本来就不靠记录来解除。
     * 临时封禁写失败必须让调用方知道：封禁已经执行了，但到期时间没留下来，
     * 这个玩家永远不会被自动解封。
     */
    private void record(String name, String reason, String operator, BanMethod method,
                        long now, long expiresAt, boolean temporary) {
        if (store == null) {
            return;
        }
        try {
            store.put(BanRecord.create(name, reason, operator, method.id(), now, expiresAt));
        } catch (Exception e) {
            if (temporary) {
                throw new PlayerService.PlayerActionException(
                        "封禁已执行，但到期时间没能写入存储，该玩家不会被自动解封，请手动解除：" + e.getMessage());
            }
            MessagesManager.logRaw("§c写入封禁记录失败: " + e.getMessage());
        }
    }

    private BanMethod currentMethod() {
        BanMethod method = BanMethod.parse(config.get().banMethod);
        return method == null ? BanMethod.VANILLA : method;
    }

    private String displayReason(String reason) {
        return reason.isEmpty() ? DEFAULT_REASON_DISPLAY : MessagesManager.color(reason);
    }
}
