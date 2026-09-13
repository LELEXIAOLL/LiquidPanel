package dev.liquidpanel.panels.players;

import java.util.ArrayList;
import java.util.List;

/**
 * 封禁的执行方式，对应设置页「兼容性设置」里的那个下拉框。
 *
 * <p>面板不自己实现封禁逻辑，而是把动作交给谁去执行的选择：
 * 服务端原生名单，还是服务器上已经装着的封禁插件。
 */
public enum BanMethod {

    /**
     * 用 Bukkit 自带的封禁名单，写进服务端的 {@code banned-players.json}。
     *
     * <p>临时封禁直接带上到期时间交给服务端，到点由服务端自己解除，
     * 所以这种方式即使面板的存储坏了也不会把人封成永久。
     */
    VANILLA("Vanilla"),

    /**
     * 交给 LiteBans / AdvancedBan 这类封禁插件执行。
     *
     * <p>临时封禁发 {@code tempban <玩家> <时长> <原因>}，
     * 永久封禁发 {@code ban <玩家> <原因>} —— 两家插件的这两个命令语法一致，
     * 所以共用一个选项。解封统一发 {@code unban <玩家>}。
     */
    PLUGIN("LiteBans/AdvancedBan"),

    /**
     * 管理员自己在设置页写的命令，支持 {@code %player%} {@code %reason%} {@code %time%}
     * 三个占位符，以控制台身份执行。适用于上面两种覆盖不到的封禁插件。
     */
    CUSTOM("CustomCommand");

    private final String id;

    BanMethod(String id) {
        this.id = id;
    }

    /** 存进配置文件、发给前端的值 */
    public String id() {
        return id;
    }

    /**
     * 解析配置或请求里的值，认不出来返回 null。
     *
     * <p>枚举名也接受（{@code VANILLA}），方便管理员手写 panelconfig.json。
     */
    public static BanMethod parse(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        for (BanMethod method : values()) {
            if (method.id.equalsIgnoreCase(value) || method.name().equalsIgnoreCase(value)) {
                return method;
            }
        }
        return null;
    }

    /**
     * 全部可选值，供前端渲染下拉框 —— 以后加新方式不用同步改前端。
     */
    public static List<String> ids() {
        List<String> list = new ArrayList<>(values().length);
        for (BanMethod method : values()) {
            list.add(method.id);
        }
        return list;
    }
}
