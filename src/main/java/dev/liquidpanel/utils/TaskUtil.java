package dev.liquidpanel.utils;

import dev.liquidpanel.LiquidPanel;
import org.bukkit.Bukkit;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 线程调度工具类。
 *
 * <p>网页面板的请求全部跑在 Undertow 的工作线程上，与 Minecraft 主线程彻底隔离，
 * 所以面板本身不会卡服。反过来说，只要涉及 Bukkit API 的读写，就必须通过本类切回主线程，
 * 否则会出现线程安全问题。
 *
 * <p>本类除 {@link #awaitSync} 外全部非阻塞：需要返回值时统一返回 {@link CompletableFuture}，
 * 由调用方在自己的线程里异步消费。
 */
public final class TaskUtil {

    /** 主线程等待的默认上限，超过就放弃，避免拖住网络线程 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 3000L;

    private TaskUtil() {
    }

    /**
     * 当前是否处于 Minecraft 主线程。
     */
    public static boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    /**
     * 在主线程执行一段逻辑并返回结果。调用线程不会被阻塞。
     */
    public static <T> CompletableFuture<T> supplySync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };

        if (Bukkit.isPrimaryThread()) {
            // 已经在主线程，直接执行，省掉一次调度
            task.run();
        } else if (!schedule(task, 0L)) {
            future.completeExceptionally(new IllegalStateException("插件未启用，无法在主线程执行任务"));
        }
        return future;
    }

    /**
     * 在主线程执行一段逻辑，不关心返回值。调用线程不会被阻塞。
     */
    public static CompletableFuture<Void> runSync(Runnable runnable) {
        return supplySync(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 延迟若干个游戏刻后在主线程执行。
     */
    public static <T> CompletableFuture<T> supplySyncLater(Supplier<T> supplier, long delayTicks) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
        if (!schedule(task, Math.max(0L, delayTicks))) {
            future.completeExceptionally(new IllegalStateException("插件未启用，无法在主线程执行任务"));
        }
        return future;
    }

    /**
     * 在 Bukkit 异步线程池执行。
     */
    public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        LiquidPanel plugin = LiquidPanel.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            future.completeExceptionally(new IllegalStateException("插件未启用"));
            return future;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /**
     * 在 Bukkit 异步线程池执行，不关心返回值。
     */
    public static CompletableFuture<Void> runAsync(Runnable runnable) {
        return supplyAsync(() -> {
            runnable.run();
            return null;
        });
    }

    /**
     * 在主线程取值，并在<b>当前线程</b>上等待结果，超时或出错返回 {@code defaultValue}。
     *
     * <p>这是给 Undertow 工作线程用的同步桥接方法：Undertow 的工作线程是它自己的线程池，
     * 短暂阻塞它不会影响 Minecraft 服务端，而且有超时兜底，不会无限等下去。
     *
     * <p>如果调用方本身就是主线程，则直接执行、绝不等待，因此本方法不可能卡服。
     */
    public static <T> T awaitSync(Supplier<T> supplier, long timeoutMillis, T defaultValue) {
        if (Bukkit.isPrimaryThread()) {
            try {
                return supplier.get();
            } catch (Throwable t) {
                return defaultValue;
            }
        }
        try {
            return supplySync(supplier).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /**
     * 同 {@link #awaitSync(Supplier, long, Object)}，使用默认超时。
     */
    public static <T> T awaitSync(Supplier<T> supplier, T defaultValue) {
        return awaitSync(supplier, DEFAULT_TIMEOUT_MILLIS, defaultValue);
    }

    private static boolean schedule(Runnable task, long delayTicks) {
        LiquidPanel plugin = LiquidPanel.getInstance();
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        try {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
