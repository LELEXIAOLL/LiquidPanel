package dev.liquidpanel.panels.security;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录爆破防护。
 *
 * <p>三层防护：
 * <ol>
 *     <li><b>按来源计数</b>：滑动窗口内失败太多次就暂时封禁。IPv6 地址会被归一化到 /64，
 *         否则攻击者手握一个 /64 就有 2^64 个地址，逐次轮换即可完全绕过。</li>
 *     <li><b>并发闸门</b>：密码校验一次要跑约 400ms 的 PBKDF2，
 *         不限并发的话大量登录请求就能把 CPU 打满。闸门限制同时在跑的校验数量，
 *         超出直接拒绝 —— 不排队，因此也不会积压线程。</li>
 *     <li><b>不信任代理头</b>：IP 一律取自 TCP 连接本身，
 *         否则伪造 {@code X-Forwarded-For} 就能随意换身份绕过限制。</li>
 * </ol>
 *
 * <p>各阈值由 config.yml 的 {@code panel.security} 段配置，{@link #configure} 可在运行中热更新。
 */
public final class LoginGuard {

    /** 默认：窗口内允许的失败次数 */
    public static final int DEFAULT_MAX_FAILURES = 5;

    /** 默认：统计窗口（分钟） */
    public static final int DEFAULT_FAIL_WINDOW_MINUTES = 5;

    /** 默认：触发后封禁时长（分钟） */
    public static final int DEFAULT_BLOCK_MINUTES = 10;

    /** 默认：同时进行的密码校验上限 */
    public static final int DEFAULT_MAX_CONCURRENT_VERIFICATIONS = 4;

    /** 记录上限，防止被大量不同来源撑爆内存 */
    private static final int MAX_ENTRIES = 4096;

    /** IPv6 归一化保留的字节数，即 /64 */
    private static final int IPV6_PREFIX_BYTES = 8;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 当前正在跑的密码校验数。
     *
     * <p>用计数而不是 {@link java.util.concurrent.Semaphore}：许可数没法在运行中安全改动，
     * 而配置是热更新的。计数器在任何时刻改上限都是安全的，
     * 已经在跑的校验照常跑完，计数依然准确。
     */
    private final AtomicInteger activeVerifications = new AtomicInteger();

    private volatile int maxFailures = DEFAULT_MAX_FAILURES;
    private volatile long windowMillis = DEFAULT_FAIL_WINDOW_MINUTES * 60_000L;
    private volatile long blockMillis = DEFAULT_BLOCK_MINUTES * 60_000L;
    private volatile int maxConcurrentVerifications = DEFAULT_MAX_CONCURRENT_VERIFICATIONS;

    /**
     * 应用配置。可以在运行中调用，立即生效。
     *
     * @param maxFailures    窗口内允许的失败次数
     * @param windowMinutes  统计窗口（分钟）
     * @param blockMinutes   封禁时长（分钟）
     * @param maxConcurrent  同时进行的密码校验上限
     */
    public void configure(int maxFailures, int windowMinutes, int blockMinutes, int maxConcurrent) {
        // 非法值退回默认，而不是夹到 1 ——
        // max_failures 夹到 1 会变成「输错一次就封禁」，比不设防还糟。
        this.maxFailures = maxFailures < 1 ? DEFAULT_MAX_FAILURES : maxFailures;
        this.windowMillis = (windowMinutes < 1 ? DEFAULT_FAIL_WINDOW_MINUTES : windowMinutes) * 60_000L;
        this.blockMillis = (blockMinutes < 1 ? DEFAULT_BLOCK_MINUTES : blockMinutes) * 60_000L;
        this.maxConcurrentVerifications =
                maxConcurrent < 1 ? DEFAULT_MAX_CONCURRENT_VERIFICATIONS : maxConcurrent;
    }

    // ------------------------------------------------------------------
    // 并发闸门
    // ------------------------------------------------------------------

    /**
     * 尝试占用一个密码校验名额。
     *
     * <p>非阻塞：拿不到就立刻返回 false，调用方回 429。绝不排队等待。
     *
     * @return 是否拿到名额。拿到后<b>必须</b>在 finally 里调用 {@link #releaseVerification()}
     */
    public boolean tryAcquireVerification() {
        while (true) {
            int current = activeVerifications.get();
            if (current >= maxConcurrentVerifications) {
                return false;
            }
            if (activeVerifications.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void releaseVerification() {
        activeVerifications.decrementAndGet();
    }

    // ------------------------------------------------------------------
    // 按来源计数
    // ------------------------------------------------------------------

    /**
     * 该来源当前是否处于封禁状态。
     */
    public boolean isBlocked(String ip) {
        Attempt attempt = attempts.get(key(ip));
        return attempt != null && attempt.isBlocked();
    }

    /**
     * 距离解封还剩多少秒，未封禁返回 0。
     */
    public long blockedSeconds(String ip) {
        Attempt attempt = attempts.get(key(ip));
        return attempt == null ? 0L : attempt.remainingSeconds();
    }

    /**
     * 记录一次密码校验失败。
     */
    public void recordFailure(String ip) {
        evictIfNeeded();
        attempts.computeIfAbsent(key(ip), k -> new Attempt())
                .recordFailure(maxFailures, windowMillis, blockMillis);
    }

    /**
     * 校验成功，清空该来源的失败记录。
     */
    public void recordSuccess(String ip) {
        attempts.remove(key(ip));
    }

    /**
     * 清理彻底过期的记录，由定时任务调用。
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        long window = windowMillis;
        attempts.entrySet().removeIf(entry -> entry.getValue().isStale(now, window));
    }

    // ------------------------------------------------------------------
    // 来源归一化
    // ------------------------------------------------------------------

    /**
     * 把来源地址归一化成计数用的键。
     *
     * <p>IPv4 用完整地址；IPv6 只保留前 64 位。
     * IPv6 下攻击者一个 /64 就握有 2^64 个可用地址，
     * 不做归一化的话按 IP 限流形同虚设。
     */
    private String key(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "unknown";
        }
        byte[] bytes = parse(ip);
        if (bytes == null) {
            // 解析不了就退回原始字符串，至少保持行为一致
            return ip;
        }

        int keep = bytes.length == 4 ? 4 : Math.min(IPV6_PREFIX_BYTES, bytes.length);
        StringBuilder key = new StringBuilder(keep * 2 + 4);
        for (int i = 0; i < keep; i++) {
            key.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
            key.append(Character.forDigit(bytes[i] & 0xF, 16));
        }
        return key.append('/').append(keep * 8).toString();
    }

    /**
     * 解析字面量 IP。这里的输入来自 TCP 连接地址，不是用户可控的任意字符串，
     * 因此不会触发 DNS 查询。
     */
    private byte[] parse(String ip) {
        try {
            byte[] bytes = InetAddress.getByName(ip).getAddress();
            return bytes != null && (bytes.length == 4 || bytes.length == 16) ? bytes : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void evictIfNeeded() {
        if (attempts.size() >= MAX_ENTRIES) {
            cleanup();
        }
    }

    /**
     * 单个来源的失败计数。
     */
    private static final class Attempt {

        private long windowStart = System.currentTimeMillis();
        private long blockedUntil;
        private int failures;

        synchronized void recordFailure(int maxFailures, long windowMillis, long blockMillis) {
            long now = System.currentTimeMillis();

            if (now - windowStart > windowMillis) {
                windowStart = now;
                failures = 0;
            }

            failures++;
            if (failures >= maxFailures) {
                blockedUntil = now + blockMillis;
                // 重新计数，避免解封后立刻又因为旧记录被封
                failures = 0;
                windowStart = now;
            }
        }

        synchronized boolean isBlocked() {
            return System.currentTimeMillis() < blockedUntil;
        }

        synchronized long remainingSeconds() {
            long remaining = blockedUntil - System.currentTimeMillis();
            return remaining <= 0 ? 0L : (remaining + 999L) / 1000L;
        }

        synchronized boolean isStale(long now, long windowMillis) {
            return now >= blockedUntil && now - windowStart > windowMillis;
        }
    }
}
