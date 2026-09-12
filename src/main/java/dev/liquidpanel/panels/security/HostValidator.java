package dev.liquidpanel.panels.security;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.PanelSettings;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Host 头白名单校验，用于修复 DNS Rebinding 绕过同源校验的问题。
 *
 * <h2>为什么必须有这一层</h2>
 * <p>同源校验比较的是「Origin 的 authority == Host 头」，而在 rebinding 场景下
 * <b>这两个值都由攻击者控制、且天然相等</b>：
 * 攻击者把自己的域名解析到面板 IP，浏览器发出的请求就同时带着
 * {@code Origin: http://evil.com} 与 {@code Host: evil.com}，两道值一比就通过了。
 *
 * <p>比较两个攻击者都能控制的值等于没有比较。所以这里引入一个攻击者控制不了的参照物：
 * <b>Host 头必须本来就是面板该被访问到的名字</b>。
 * 攻击者的域名不在白名单里，请求在第一步就被拒。
 *
 * <h2>白名单来源</h2>
 * <ul>
 *     <li>回环写法：{@code 127.0.0.1} / {@code localhost} / {@code ::1}</li>
 *     <li>{@code panel.host} 配置的具体值（{@code 0.0.0.0} 这类通配地址不算，它不是可以出现在 Host 里的名字）</li>
 *     <li>本机所有网卡的 IP，保证用内网地址访问面板能正常登录</li>
 *     <li>{@code panel.allowed_hosts} 里管理员显式配置的域名</li>
 * </ul>
 *
 * <p>用域名访问面板时必须在 {@code panel.allowed_hosts} 里登记，
 * 否则会被拒绝并在控制台收到一条带域名的提醒，不至于让人摸不着头脑。
 */
public final class HostValidator {

    /** 永远允许的回环写法。IPv6 在部分 JVM 上会展开成全 0 形式，两种都收 */
    private static final Set<String> LOOPBACK = Set.of(
            "127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1");

    /** 通配监听地址，它们不是合法的 Host 取值 */
    private static final Set<String> WILDCARD = Set.of("0.0.0.0", "::", "*", "");

    /** 只提醒一次的已拒绝 Host，防止被刷日志 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final int MAX_WARNED = 64;

    /** 当前允许的 Host 集合（已归一化，不含端口） */
    private final Set<String> allowed = ConcurrentHashMap.newKeySet();

    /**
     * 重新生成白名单。配置重载、网卡变化后调用。
     */
    public void refresh(PanelSettings settings) {
        Set<String> hosts = new HashSet<>(LOOPBACK);

        // panel.host 是具体地址时才纳入；0.0.0.0 / :: 只是监听通配符
        String bound = extractHost(settings.getHost());
        if (bound != null && !WILDCARD.contains(bound)) {
            hosts.add(bound);
        }

        hosts.addAll(localAddresses());

        List<String> extra = settings.getAllowedHosts();
        if (extra != null) {
            for (String entry : extra) {
                String host = extractHost(entry);
                if (host != null && !WILDCARD.contains(host)) {
                    hosts.add(host);
                }
            }
        }

        allowed.clear();
        allowed.addAll(hosts);
        WARNED.clear();
    }

    /**
     * 判断 Host 头是否被允许。
     */
    public boolean isAllowed(String hostHeader) {
        String host = extractHost(hostHeader);
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (allowed.contains(host)) {
            return true;
        }
        warnOnce(host);
        return false;
    }

    /**
     * 从 {@code Host} 取值里抽出主机名：去掉端口、去掉 IPv6 的方括号、转小写。
     *
     * @return 无法解析时返回 null（调用方按不允许处理，失败方向是拒绝）
     */
    public static String extractHost(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }

        // IPv6 字面量形如 [::1]:1357
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            return end < 0 ? null : text.substring(1, end);
        }

        int colon = text.indexOf(':');
        return colon < 0 ? text : text.substring(0, colon);
    }

    /**
     * 采集本机所有网卡地址，让「用内网 IP 访问」也能通过校验。
     */
    private Set<String> localAddresses() {
        Set<String> addresses = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : nic.getInterfaceAddresses()) {
                    InetAddress address = interfaceAddress.getAddress();
                    if (address == null) {
                        continue;
                    }
                    String raw = address.getHostAddress();
                    // IPv6 链路本地地址带 %scope 后缀，Host 里不会出现
                    int scope = raw.indexOf('%');
                    if (scope >= 0) {
                        raw = raw.substring(0, scope);
                    }
                    addresses.add(raw.toLowerCase(Locale.ROOT));
                }
            }
        } catch (Exception ignored) {
            // 拿不到网卡信息时只保留回环与绑定地址，不影响本机访问
        }
        addresses.removeIf(WILDCARD::contains);
        return addresses;
    }

    /**
     * 拒绝一个陌生 Host 时提醒一次，并把该怎么修一起说出来。
     */
    private void warnOnce(String host) {
        if (WARNED.size() >= MAX_WARNED || !WARNED.add(host)) {
            return;
        }
        MessagesManager.log("panel.host.rejected", "host", host);
    }
}
