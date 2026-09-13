package dev.liquidpanel.panels.database;

import com.google.gson.annotations.SerializedName;

/**
 * 一个玩家的档案。
 *
 * <p>面板要能列出「所有玩家」「离线玩家」，就不可能只靠 {@code getOnlinePlayers()}。
 * 这里记的是见过谁、什么时候最后见过 —— 数据量极小（一条一百来字节），
 * 一个几千人的服也就几百 KB。
 *
 * <p>和 {@link BanRecord} 一样是给 Gson 直接读写的纯数据类，字段全 public。
 */
public final class PlayerProfile {

    @SerializedName("uuid")
    public String uuid;

    /** 最近一次见到的名字。正版改名后以服务端给的为准 */
    @SerializedName("name")
    public String name;

    /** 第一次见到这个玩家（epoch 毫秒） */
    @SerializedName("first_seen")
    public long firstSeen;

    /**
     * 最后一次在线（epoch 毫秒）。
     *
     * <p>玩家正在线上的话，这个值是这次上线的时间 ——
     * 下线时才更新成下线时间。
     */
    @SerializedName("last_seen")
    public long lastSeen;

    /**
     * 最后下线时所在的世界。为 null 表示没记过位置。
     *
     * <p>刻意不在上线时更新：这个字段的含义是「他最后是在哪儿下线的」，
     * 刚上线的玩家还没下线过，位置应该是上一次的值。
     */
    @SerializedName("world")
    public String world;

    @SerializedName("x")
    public double x;

    @SerializedName("y")
    public double y;

    @SerializedName("z")
    public double z;

    public static PlayerProfile of(String uuid, String name, long now) {
        PlayerProfile profile = new PlayerProfile();
        profile.uuid = uuid;
        profile.name = name;
        profile.firstSeen = now;
        profile.lastSeen = now;
        return profile;
    }

    /**
     * 玩家上线或下线，刷新最后在线时间。
     *
     * <p>名字跟着最新的走，{@code firstSeen} 不动 —— 那个是「第一次见」，
     * 被后来的事件改掉就没有意义了。
     */
    public PlayerProfile touch(String currentName, long now) {
        if (currentName != null && !currentName.isBlank()) {
            this.name = currentName;
        }
        this.lastSeen = now;
        return this;
    }

    /**
     * 记下最后一次下线时的位置。{@code world} 为 null 时不动已有数据 ——
     * 世界都拿不到的位置没有记录价值，不该把上一次的有效值抹掉。
     */
    public PlayerProfile withLocation(String world, double x, double y, double z) {
        if (world == null) {
            return this;
        }
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** 有没有记过位置 */
    public boolean hasLocation() {
        return world != null;
    }
}
