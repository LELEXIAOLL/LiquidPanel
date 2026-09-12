package dev.liquidpanel.utils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * CPU 型号读取工具。
 *
 * <p>JDK 没有任何标准接口能拿到 CPU 的友好型号（比如
 * {@code Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz}），只能按平台各显神通：
 *
 * <ul>
 *     <li><b>Linux</b>：读 {@code /proc/cpuinfo} 的 {@code model name}</li>
 *     <li><b>Windows</b>：先查注册表 {@code ProcessorNameString}，
 *         再退回 {@code wmic cpu get name}</li>
 *     <li><b>兜底</b>：环境变量 {@code PROCESSOR_IDENTIFIER}，最后用架构名</li>
 * </ul>
 *
 * <p>Windows 分支要起外部进程，<b>必须在非主线程调用</b>。
 *
 * <p>实测（Win10，Intel i5-3470）：
 * <pre>
 *   reg   第 1 次 3987ms / 第 2 次 1372ms / 第 3 次 49ms
 *   wmic  第 1 次  162ms / 第 2 次  158ms
 * </pre>
 * 第一次之所以要几秒，不是命令本身慢，而是 Windows 首次执行某个 exe 时
 * 会被杀毒软件完整扫描一遍。所以超时给了 {@value #COMMAND_TIMEOUT_SECONDS} 秒 ——
 * 按「注册表 100ms」定超时会在这台机器上直接误判成失败。
 *
 * <p>预热之后注册表比 wmic 快 3 倍，且 wmic 从 Windows 11 24H2 起已被移除，
 * 因此注册表优先、wmic 备选。
 */
public final class CpuModelUtil {

    /**
     * 外部命令的超时。
     *
     * <p>定这么宽是为了覆盖「首次执行被杀软扫描」的几秒钟；
     * 命令正常返回时不受影响，超时只会在真的卡住时才触发。
     */
    private static final long COMMAND_TIMEOUT_SECONDS = 10L;

    /** 友好型号所在的注册表位置 */
    private static final String REG_KEY = "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0";

    private CpuModelUtil() {
    }

    /**
     * 取 CPU 友好型号。任何一步失败都会自动退回下一个方案，不会返回 null 或空串。
     *
     * <p>注意：Windows 上会启动外部进程，冷启动可能耗时数秒（杀软扫描），
     * 所以只应在启动时调用一次并把结果缓存住。
     */
    public static String resolve() {
        String fromProc = readProcCpuInfo();
        if (isUsable(fromProc)) {
            return fromProc;
        }

        if (isWindows()) {
            String fromRegistry = parseRegistryOutput(
                    runCommand("reg", "query", REG_KEY, "/v", "ProcessorNameString"));
            if (isUsable(fromRegistry)) {
                return fromRegistry;
            }

            String fromWmic = parseWmicOutput(
                    runCommand("wmic", "cpu", "get", "name"));
            if (isUsable(fromWmic)) {
                return fromWmic;
            }
        }

        return fallback();
    }

    /**
     * 不启动任何进程的兜底值，可以安全地在主线程取。
     */
    public static String fallback() {
        String identifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (isUsable(identifier)) {
            return identifier.trim();
        }
        String arch = System.getProperty("os.arch");
        return isUsable(arch) ? arch.trim() : "未知";
    }

    // ------------------------------------------------------------------
    // 各平台读取
    // ------------------------------------------------------------------

    /**
     * Linux：/proc/cpuinfo 里取第一个 model name。非 Linux 上该文件不存在，直接跳过。
     */
    private static String readProcCpuInfo() {
        Path path = Paths.get("/proc/cpuinfo");
        if (!Files.isReadable(path)) {
            return null;
        }
        try (Stream<String> lines = Files.lines(path)) {
            return lines
                    .filter(line -> line.startsWith("model name") || line.startsWith("Model"))
                    .map(CpuModelUtil::valueAfterColon)
                    .filter(CpuModelUtil::isUsable)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String valueAfterColon(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1).trim();
    }

    /**
     * reg query 的输出形如：
     * <pre>
     * HKEY_LOCAL_MACHINE\HARDWARE\DESCRIPTION\System\CentralProcessor\0
     *     ProcessorNameString    REG_SZ    Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz
     * </pre>
     */
    static String parseRegistryOutput(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            int marker = line.indexOf("ProcessorNameString");
            if (marker < 0) {
                continue;
            }
            int valueStart = line.indexOf("REG_SZ", marker);
            if (valueStart < 0) {
                continue;
            }
            String value = line.substring(valueStart + "REG_SZ".length()).trim();
            if (isUsable(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * wmic 的输出是「表头 + 数据」，形如：
     * <pre>
     * Name
     * Intel(R) Core(TM) i5-3470 CPU @ 3.20GHz
     * </pre>
     */
    static String parseWmicOutput(String output) {
        if (output == null) {
            return null;
        }
        for (String line : output.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty() || "Name".equalsIgnoreCase(value)) {
                continue;
            }
            return value;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 外部命令
    // ------------------------------------------------------------------

    /**
     * 跑一条外部命令并返回标准输出。失败、超时一律返回 null。
     */
    private static String runCommand(String... command) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            process = builder.start();

            // 这两条命令的输出只有一两百字节，远小于管道缓冲，
            // 因此「先等退出再读」不会触发经典的管道写满死锁。
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return null;
            }

            try (InputStream in = process.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            // 命令不存在、权限不足等，都属于预期内的失败
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}
