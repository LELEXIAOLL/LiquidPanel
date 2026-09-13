package dev.liquidpanel.panels.players;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家数据的版本号。
 *
 * <p>玩家列表里除了坐标，还有在线状态、封禁状态、最后下线的位置 ——
 * 这些变了之后界面必须跟着变，但它们不是每刻都在动，
 * 没法像坐标那样靠高频推送解决。
 *
 * <p>这里用一个单调递增的计数代替「把整份名册推过去」：
 * 名册也好，封禁状态也好，只要变了就把这个数加一；
 * 它跟着每 2 秒一次的状态推送捎过去，前端发现数变了才去重新拉列表。
 * 推一个数字的代价，和推几千条名册的代价，差着好几个数量级。
 */
public final class DataRevision {

    private final AtomicLong value = new AtomicLong();

    /**
     * 数据有变动时调一次。多调几次没关系 ——
     * 前端只关心「变没变」，不关心变了几次。
     */
    public void bump() {
        value.incrementAndGet();
    }

    public long get() {
        return value.get();
    }
}
