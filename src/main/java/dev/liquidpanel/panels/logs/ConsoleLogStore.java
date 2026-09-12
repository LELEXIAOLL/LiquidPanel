package dev.liquidpanel.panels.logs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 控制台日志的内存缓冲。
 *
 * <p>两份数据：
 * <ul>
 *     <li>{@code recent} —— 最近若干行，新连接进来时一次性补看；</li>
 *     <li>{@code pending} —— 已产生但还没推给前端的行，由推送任务取走。</li>
 * </ul>
 *
 * <p>两份都封顶：没人连着的时候 {@code pending} 会一直涨，不能不管。
 */
public final class ConsoleLogStore {

    /** 各保留最近多少行 */
    private static final int CAPACITY = 300;

    private final Deque<LogLine> recent = new ArrayDeque<>(CAPACITY);
    private final List<LogLine> pending = new ArrayList<>();

    /**
     * 收一行。由 appender 在日志线程上调用，必须线程安全。
     */
    public synchronized void accept(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        LogLine line = new LogLine(text);

        recent.addLast(line);
        while (recent.size() > CAPACITY) {
            recent.removeFirst();
        }

        pending.add(line);
        while (pending.size() > CAPACITY) {
            pending.remove(0);
        }
    }

    /**
     * 最近若干行，供前端首次加载补看。
     */
    public synchronized List<LogLine> snapshot() {
        return new ArrayList<>(recent);
    }

    /**
     * 取走尚未推送的新行。
     */
    public synchronized List<LogLine> drainPending() {
        if (pending.isEmpty()) {
            return List.of();
        }
        List<LogLine> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    /**
     * 一行日志。{@code text} 原样保留服务端写入的 {@code §} 颜色代码，由前端按控制台配色还原。
     */
    public static final class LogLine {

        /** 产生时间戳 */
        public final long t;

        /** 原始文本（含颜色代码） */
        public final String text;

        public LogLine(String text) {
            this.t = System.currentTimeMillis();
            this.text = text;
        }
    }
}
