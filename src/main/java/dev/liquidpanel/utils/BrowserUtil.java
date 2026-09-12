package dev.liquidpanel.utils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 浏览器工具类。
 *
 * <p>跨平台调用系统默认浏览器打开链接。
 * <b>注意：</b>这会启动外部进程并等待，属于阻塞操作，必须在异步线程里调用。
 */
public final class BrowserUtil {

    /** 等待外部进程退出的上限 */
    private static final long WAIT_SECONDS = 10L;

    private BrowserUtil() {
    }

    /**
     * 打开链接。
     *
     * @return 是否成功唤起浏览器（服务端没有桌面环境时会返回 false）
     */
    public static boolean open(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return run(buildCommand(url));
    }

    private static List<String> buildCommand(String url) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return Arrays.asList("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return Arrays.asList("open", url);
        }
        return Arrays.asList("xdg-open", url);
    }

    private static boolean run(List<String> command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();

            if (!process.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // 无桌面环境、命令不存在等，都属于预期内的失败
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }
}
