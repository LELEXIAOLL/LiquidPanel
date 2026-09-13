package dev.liquidpanel.panels.players;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家列表的筛选条件，对应玩家管理页上那个下拉框。
 *
 * <p>可选值由后端给前端，加新筛选项不用同步改前端。
 */
public enum PlayerFilter {

    /** 名册里的所有人，含从没上线过的被封禁玩家 */
    ALL("All"),

    /** 当前在线 */
    ONLINE("Online"),

    /** 不在线（含已被封禁的） */
    OFFLINE("Offline"),

    /** 处于封禁状态，不论在不在线 */
    BANNED("Banned");

    private final String id;

    PlayerFilter(String id) {
        this.id = id;
    }

    /** 发给前端的值 */
    public String id() {
        return id;
    }

    /** 这条记录是否符合本筛选 */
    public boolean matches(PlayerService.Entry entry) {
        return switch (this) {
            case ALL -> true;
            case ONLINE -> entry.online;
            case OFFLINE -> !entry.online;
            case BANNED -> entry.banned;
        };
    }

    /**
     * 解析请求参数。认不出来一律按「全部」处理 ——
     * 筛选条件写错时给一份完整列表，比报错更不容易让人以为玩家数据丢了。
     */
    public static PlayerFilter parse(String text) {
        if (text != null) {
            String value = text.trim();
            for (PlayerFilter filter : values()) {
                if (filter.id.equalsIgnoreCase(value) || filter.name().equalsIgnoreCase(value)) {
                    return filter;
                }
            }
        }
        return ALL;
    }

    /** 全部可选值，供前端渲染下拉框 */
    public static List<String> ids() {
        List<String> list = new ArrayList<>(values().length);
        for (PlayerFilter filter : values()) {
            list.add(filter.id);
        }
        return list;
    }
}
