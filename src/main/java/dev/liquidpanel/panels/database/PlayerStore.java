package dev.liquidpanel.panels.database;

import java.util.Collection;
import java.util.Map;

/**
 * 玩家名册的存储。
 *
 * <p>刻意与 {@link BanStore} 分成两个接口，而不是合成一个「数据中心」：
 * 两者的写入时机完全不同 —— 封禁是改一次写一次，名册是攒一批写一次。
 * 混在一起，调用方就看不出某次写盘的代价有多大了。
 *
 * <p>实现必须自己保证线程安全：调用方来自 Bukkit 事件线程与调度线程。
 */
public interface PlayerStore extends AutoCloseable {

    /** 打开存储。建文件、建表都在这一步做 */
    void open() throws Exception;

    /**
     * 全量读出，key 是玩家 UUID。
     */
    Map<String, PlayerProfile> load() throws Exception;

    /**
     * 让存储里的内容与给定集合一致。
     *
     * <p>名册是一份整体快照，所以这里是<b>全量覆盖</b>语义。
     * 各实现按自己的特点优化：json 整份重写，sqlite 在事务里逐条 upsert。
     */
    void saveAll(Collection<PlayerProfile> profiles) throws Exception;

    /** 存储类型名，与 {@link BanStore#type()} 同源 */
    String type();

    @Override
    void close();
}
