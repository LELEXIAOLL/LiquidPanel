package dev.liquidpanel.panels.database;

import com.google.gson.annotations.SerializedName;

import java.util.UUID;

/**
 * 一条封禁记录。
 *
 * <p>面板发出的每一次封禁都会落一条，永久与临时都记 —— 临时的到点由面板负责解除，
 * 永久的只是留个底，方便日后查「谁在什么时候封的、因为什么」。
 *
 * <p>字段全部 public 且没有 getter/setter：这是给 Gson 直接读写的纯数据类，
 * 包一层访问器只会让序列化配置变复杂，没有任何收益。
 */
public final class BanRecord {

    /** 记录 ID，解除封禁时用它定位 */
    @SerializedName("id")
    public String id;

    /** 被封禁的玩家名 */
    @SerializedName("player")
    public String player;

    /** 封禁原因，与执行时传给服务端 / 封禁插件的原文一致 */
    @SerializedName("reason")
    public String reason;

    /** 操作者，面板用户名 */
    @SerializedName("operator")
    public String operator;

    /** 用哪种方式执行的封禁，取值见 {@link dev.liquidpanel.panels.players.BanMethod} */
    @SerializedName("method")
    public String method;

    /** 封禁时间（epoch 毫秒） */
    @SerializedName("created_at")
    public long createdAt;

    /**
     * 到期时间（epoch 毫秒）。
     *
     * <p><b>0 表示永久</b>，不会有到期任务来动它。
     */
    @SerializedName("expires_at")
    public long expiresAt;

    /**
     * 建一条新记录，ID 自动生成。
     *
     * @param expiresAt 到期时间戳，传 0 表示永久
     */
    public static BanRecord create(String player, String reason, String operator,
                                   String method, long createdAt, long expiresAt) {
        BanRecord record = new BanRecord();
        record.id = UUID.randomUUID().toString();
        record.player = player;
        record.reason = reason == null ? "" : reason;
        record.operator = operator;
        record.method = method;
        record.createdAt = createdAt;
        record.expiresAt = expiresAt;
        return record;
    }

    /** 永久封禁 */
    public boolean isPermanent() {
        return expiresAt <= 0L;
    }

    /**
     * 是否已经到期。
     *
     * <p>永久封禁永远返回 false —— 用 0 表示永久，不能让它恰好落进「早就过期了」那一侧。
     */
    public boolean isExpired(long now) {
        return !isPermanent() && expiresAt <= now;
    }
}
