package dev.liquidpanel.panels.database;

import java.util.List;

/**
 * 封禁记录的存储。
 *
 * <p>两种实现由 config.yml 的 {@code panel.database.type} 选择：
 * {@link JsonBanStore}（纯文本，可以直接打开看）与 {@link SqliteBanStore}。
 *
 * <p>所有方法都可能抛异常 —— 存储故障不该让面板崩溃，由调用方决定降级还是拒绝操作。
 * 唯一的例外是 {@link #close()}：它在关服路径上，不能半路抛出来打断收尾。
 *
 * <p>实现必须自己保证线程安全：调用方来自 Undertow 工作线程与 Bukkit 调度线程。
 */
public interface BanStore extends AutoCloseable {

    /**
     * 打开存储。建文件、建表都在这一步做。
     */
    void open() throws Exception;

    /** 写入一条记录，ID 已存在则覆盖 */
    void put(BanRecord record) throws Exception;

    /** 按 ID 删除，ID 不存在时静默返回 */
    void remove(String id) throws Exception;

    /** 取出全部记录 */
    List<BanRecord> all() throws Exception;

    /** 存储类型名，用于日志与前端展示 */
    String type();

    @Override
    void close();
}
