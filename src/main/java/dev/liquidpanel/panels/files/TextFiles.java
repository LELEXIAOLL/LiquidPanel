package dev.liquidpanel.panels.files;

import java.util.Locale;
import java.util.Set;

/**
 * 判断一个文件能不能用文本编辑器打开。
 *
 * <p>这里是<b>唯一</b>的事实来源：列表接口用它给每个条目打 {@code editable} 标记，
 * 编辑接口用它做快速拒绝，两边不会各自维护一份扩展名表。
 *
 * <h2>两道判断</h2>
 * <ol>
 *     <li><b>扩展名</b>：{@code .exe} / {@code .jar} / {@code .png} 这类一眼就知道不是文本的，
 *         直接判掉，<b>不读内容</b> —— 否则点一下几百 MB 的 jar 就要先把整个文件读进内存。</li>
 *     <li><b>内容</b>：扩展名不在黑名单里也不代表就是文本（比如无扩展名的二进制），
 *         真正打开时还会再查一遍 NUL 字节与 UTF-8 合法性。那一步在 {@code FileHandler} 里。</li>
 * </ol>
 */
public final class TextFiles {

    /** 文本编辑器大小上限 */
    public static final long MAX_BYTES = 1024L * 1024;

    /**
     * 一定不是文本的扩展名。
     *
     * <p>只收「确定是二进制」的，拿不准的一律不收 —— 宁可让它走到内容检测那一步被拦下，
     * 也不要误杀一个其实能编辑的文件（比如 {@code .conf}、没有扩展名的配置）。
     */
    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            // 可执行与库
            "exe", "dll", "so", "dylib", "msi", "bin", "class", "jar", "war", "apk", "dex",
            "o", "a", "lib", "obj", "pyc", "pyo", "wasm",
            // 压缩包
            "zip", "7z", "rar", "gz", "tgz", "bz2", "xz", "tar", "zst", "lz4",
            // 图片与字体
            "png", "jpg", "jpeg", "gif", "bmp", "webp", "ico", "tif", "tiff", "psd",
            "woff", "woff2", "ttf", "otf", "eot",
            // 音视频
            "mp3", "wav", "ogg", "flac", "aac", "m4a", "mp4", "avi", "mkv", "mov", "webm", "flv",
            // 文档与镜像
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "iso", "img", "dmg",
            // Minecraft 与数据库
            "mca", "mcr", "nbt", "dat_old", "db", "sqlite", "sqlite3", "mdb", "realm"
    );

    private TextFiles() {
    }

    /**
     * 仅凭文件名与大小判断「大概率能编辑」，<b>不读内容</b>。
     *
     * <p>用于列表里的 {@code editable} 标记。
     */
    public static boolean looksEditable(String fileName, long size) {
        if (size > MAX_BYTES) {
            return false;
        }
        return !BINARY_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * 扩展名是否在黑名单里。编辑接口用它做快速拒绝。
     */
    public static boolean hasBlockedExtension(String fileName) {
        return BINARY_EXTENSIONS.contains(extensionOf(fileName));
    }

    /**
     * 取小写扩展名，没有扩展名时返回空串。
     */
    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 拒绝时给用户看的原因。
     */
    public static String describeReason(String fileName) {
        String extension = extensionOf(fileName);
        return extension.isEmpty()
                ? "该文件不支持文本编辑"
                : "不支持编辑 ." + extension + " 文件";
    }
}
