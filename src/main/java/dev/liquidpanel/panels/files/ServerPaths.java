package dev.liquidpanel.panels.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 文件管理的安全边界。
 *
 * <p>这是整个文件管理功能里唯一负责判断「这个路径到底能不能碰」的地方，
 * 所有对外接口都必须先经过它。漏一处就是任意文件读取或覆盖。
 *
 * <h2>两道防线</h2>
 * <ol>
 *     <li><b>文本层</b>：拒绝空字节、盘符绝对路径；把反斜杠统一成正斜杠，
 *         去掉开头的斜杠，让 {@code /a/b} 与 {@code a/b} 等价。</li>
 *     <li><b>文件系统层</b>：一律用 {@code toRealPath()} 解析出真实路径（会跟随符号链接），
 *         再确认它确实落在服务端根目录内。
 *         只做 {@code normalize()} 是不够的 —— 一个指向 {@code C:\Windows} 的符号链接
 *         在文本上完全「合法」，只有解析出真实路径才能识破。</li>
 * </ol>
 *
 * <p>要创建的新文件不存在、没法解析真实路径，就改为校验<b>父目录</b>的真实路径，
 * 叶子名单独做合法性检查。
 */
public final class ServerPaths {

    /** 叶子名里不允许出现的字符（Windows 与通用文件系统都不接受） */
    private static final String ILLEGAL_NAME_CHARS = "\\/:*?\"<>|";

    private final Path root;
    private final Path rootReal;

    public ServerPaths(File serverRoot) throws IOException {
        this.root = serverRoot.toPath().toAbsolutePath();
        this.rootReal = root.toRealPath();
    }

    public Path getRoot() {
        return rootReal;
    }

    /**
     * 解析一个客户端传来的相对路径。
     *
     * <p>已存在（含符号链接）就走真实路径校验；不存在则校验父目录。
     *
     * @throws SecurityException 路径越界或格式非法
     */
    public Path resolve(String relative) throws IOException {
        Path target = draft(relative);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return existing(relative);
        }
        return forCreate(relative);
    }

    /**
     * 解析一个必须已存在的路径。
     */
    public Path existing(String relative) throws IOException {
        Path real = draft(relative).toRealPath();
        requireInside(real);
        return real;
    }

    /**
     * 解析一个将要创建的路径。
     */
    public Path forCreate(String relative) throws IOException {
        Path target = draft(relative);
        if (isRoot(target)) {
            throw new SecurityException("不能对根目录执行该操作");
        }

        Path parent = target.getParent();
        if (parent == null) {
            throw new SecurityException("路径不合法");
        }
        Path parentReal = parent.toRealPath();
        requireInside(parentReal);

        return parentReal.resolve(target.getFileName().toString());
    }

    /**
     * 校验一个真实路径确实在根目录内。
     */
    public void requireInside(Path real) {
        if (!isRoot(real) && !real.startsWith(rootReal)) {
            throw new SecurityException("路径越界，已拒绝");
        }
    }

    public boolean isRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.equals(rootReal) || normalized.equals(root);
    }

    /**
     * 转成给前端的相对路径，统一用正斜杠；根目录返回空串。
     */
    public String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (isRoot(normalized)) {
            return "";
        }
        return rootReal.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * 校验单个文件名（新建、重命名用）。名称里不能带路径分隔符，
     * 否则 {@code rename("a", "../../x")} 就能把文件挪出根目录。
     */
    public static String requireSimpleName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new SecurityException("名称不能为空");
        }
        if (trimmed.equals(".") || trimmed.equals("..")) {
            throw new SecurityException("名称不合法");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (ILLEGAL_NAME_CHARS.indexOf(c) >= 0 || c < 0x20) {
                throw new SecurityException("名称含有不允许的字符");
            }
        }
        return trimmed;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private Path draft(String relative) {
        String cleaned = clean(relative);
        Path target = cleaned.isEmpty() ? rootReal : rootReal.resolve(cleaned);
        return target.normalize();
    }

    /**
     * 文本层的清理与拒绝。
     */
    private String clean(String relative) {
        String text = relative == null ? "" : relative.replace('\\', '/').trim();

        if (text.indexOf('\0') >= 0) {
            throw new SecurityException("路径含有非法字符");
        }

        // 开头斜杠一律当「相对根目录」处理，不做绝对路径解读
        while (text.startsWith("/")) {
            text = text.substring(1);
        }

        // 盘符路径（C:...）与 UNC 一律拒绝
        if (text.length() >= 2 && text.charAt(1) == ':') {
            throw new SecurityException("不接受绝对路径");
        }

        return text;
    }
}
