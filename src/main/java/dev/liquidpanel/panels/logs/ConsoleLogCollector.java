package dev.liquidpanel.panels.logs;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 控制台日志采集器，直接挂在 Log4j 根日志器上。
 *
 * <h2>为什么不追 logs/latest.log</h2>
 * <p>实测（Paper 1.21.6）日志文件里 <b>ESC 与 § 的出现次数都是 0</b> ——
 * 文件 appender 会把颜色代码整个剥掉，控制台 appender 才把它转成 ANSI。
 * 也就是说颜色只存在于 LogEvent 这一层，追文件永远拿不到，
 * 只能挂在 appender 链上、在剥离之前把消息抓下来。
 *
 * <p>顺带还省掉了轮询和文件轮转处理，延迟从「半个轮询周期」降到零。
 *
 * <h2>安全边界</h2>
 * <p>{@code ignoreExceptions = true}，且 {@link #append} 内部再兜一层 try/catch：
 * 日志采集是附属功能，绝不能反过来把服务端自己的日志打挂。
 */
public final class ConsoleLogCollector extends AbstractAppender {

    private static final String APPENDER_NAME = "LiquidPanelConsole";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 与 Paper 控制台一致的四个级别着色 */
    private static final String COLOR_ERROR = "§c";
    private static final String COLOR_WARN = "§e";

    private final ConsoleLogStore store = new ConsoleLogStore();

    private LoggerContext context;
    private Configuration configuration;

    public ConsoleLogCollector() {
        super(APPENDER_NAME, null, null, true, Property.EMPTY_ARRAY);
    }

    /**
     * 挂到根日志器上。重复调用只生效一次。
     */
    public synchronized void install() {
        if (context != null) {
            return;
        }
        try {
            if (!(LogManager.getContext(false) instanceof LoggerContext loggerContext)) {
                // 服务端没在用 log4j2，面板其它功能照常，只是没有日志卡片
                return;
            }
            context = loggerContext;
            configuration = loggerContext.getConfiguration();

            start();
            configuration.addAppender(this);
            rootLogger().addAppender(this, Level.ALL, null);
            loggerContext.updateLoggers();
        } catch (Throwable t) {
            // 任何异常都只是「没有日志卡片」，不影响面板其余部分
            context = null;
            configuration = null;
        }
    }

    /**
     * 从根日志器上摘掉。
     */
    public synchronized void uninstall() {
        try {
            if (context != null && configuration != null) {
                rootLogger().removeAppender(APPENDER_NAME);
                context.updateLoggers();
            }
            stop();
        } catch (Throwable ignored) {
            // 卸载阶段的异常没有补救价值
        } finally {
            context = null;
            configuration = null;
        }
    }

    private LoggerConfig rootLogger() {
        return configuration.getLoggerConfig(LogManager.ROOT_LOGGER_NAME);
    }

    // ------------------------------------------------------------------
    // 采集
    // ------------------------------------------------------------------

    @Override
    public void append(LogEvent event) {
        try {
            store.accept(format(event));
        } catch (Throwable ignored) {
            // 同上：采集失败不能影响服务端日志
        }
    }

    /**
     * 还原成控制台那一行的样子：{@code [时间] [线程/级别]: 消息}。
     * 级别只给 WARN / ERROR 上色，其余保持默认，和原控制台一致。
     */
    private String format(LogEvent event) {
        String message;
        try {
            message = event.getMessage().getFormattedMessage();
        } catch (Throwable t) {
            message = String.valueOf(event.getMessage());
        }

        String level = event.getLevel().name();
        String prefix = "";
        if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
            prefix = COLOR_ERROR;
        } else if (event.getLevel() == Level.WARN) {
            prefix = COLOR_WARN;
        }

        return prefix + "["
                + TIME.format(Instant.ofEpochMilli(event.getTimeMillis())) + "] ["
                + event.getThreadName() + "/" + level + "]: " + message;
    }

    // ------------------------------------------------------------------
    // 对外读取
    // ------------------------------------------------------------------

    public List<ConsoleLogStore.LogLine> snapshot() {
        return store.snapshot();
    }

    public List<ConsoleLogStore.LogLine> drainPending() {
        return store.drainPending();
    }
}
