package dev.liquidpanel.panels;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 前端资源管理器。
 *
 * <p>统一负责网页资源的定位与读取，是整个网页面板唯一的静态文件出口。
 *
 * <p>查找顺序：
 * <ol>
 *     <li>{@code plugins/LiquidPanel/webassets/} —— 放在这里可以覆盖插件内置资源，方便二次开发；</li>
 *     <li>jar 内的 {@code webassets/} —— 插件自带的默认前端。</li>
 * </ol>
 *
 * <p>安全性：请求路径先做规范化，再用 {@code getCanonicalPath} 校验最终文件确实落在 webassets
 * 目录内。这样 {@code ../} 之类的路径穿越、以及指向目录外的符号链接都会被挡掉。
 */
public final class WebAssetManager {

    /** jar 内资源根目录 */
    private static final String CLASSPATH_ROOT = "webassets";

    /** 默认首页 */
    private static final String INDEX_FILE = "index.html";

    /** 404 页面 */
    private static final String NOT_FOUND_FILE = "404.html";

    /** 后缀 -> Content-Type */
    private static final Map<String, String> CONTENT_TYPES = new LinkedHashMap<>();

    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("htm", "text/html; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=utf-8");
        CONTENT_TYPES.put("mjs", "application/javascript; charset=utf-8");
        CONTENT_TYPES.put("json", "application/json; charset=utf-8");
        CONTENT_TYPES.put("map", "application/json; charset=utf-8");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("gif", "image/gif");
        CONTENT_TYPES.put("webp", "image/webp");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("woff", "font/woff");
        CONTENT_TYPES.put("woff2", "font/woff2");
        CONTENT_TYPES.put("ttf", "font/ttf");
        CONTENT_TYPES.put("txt", "text/plain; charset=utf-8");
    }

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final JavaPlugin plugin;
    private final File externalRoot;

    public WebAssetManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.externalRoot = new File(plugin.getDataFolder(), CLASSPATH_ROOT);
    }

    /**
     * 按请求路径精确取资源，<b>不做任何回退</b>。
     *
     * <p>访问控制必须用这个方法：回退会让「任意不存在的路径」都变成「成功读到首页」，
     * 于是本该 404 的请求反而把受保护的页面内容交了出去。
     *
     * @param requestPath 已去掉查询串的请求路径
     * @return 该路径真实对应的资源，不存在返回 null
     */
    public Asset resolve(String requestPath) {
        String path = sanitize(requestPath);
        return path == null ? null : read(path);
    }

    /**
     * 前端路由兜底：返回首页资源。
     *
     * <p>只应在调用方确认过「已登录」且「请求路径没有扩展名」之后使用，
     * 这样 /dashboard 这类前端路由刷新能正常拿到 index.html，
     * 而 /x.js 这种显式请求了扩展名的路径老老实实 404，
     * 不会出现 HTML 内容配 JS/SVG 的 Content-Type 错配。
     */
    public Asset fallbackToIndex() {
        return read(INDEX_FILE);
    }

    /**
     * 路径最后一段是否带扩展名。
     */
    public boolean hasExtension(String path) {
        if (path == null) {
            return false;
        }
        int slash = path.lastIndexOf('/');
        return path.indexOf('.', slash + 1) > slash;
    }

    /**
     * 取 404 页面。取不到返回 null，由调用方退回纯文本响应。
     */
    public Asset loadNotFound() {
        return read(NOT_FOUND_FILE);
    }

    /**
     * 规范化并校验路径。
     *
     * @return 合法的相对路径；包含穿越、空字节等非法内容时返回 null
     */
    private String sanitize(String requestPath) {
        if (requestPath == null) {
            return null;
        }

        String path = requestPath;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }

        path = path.replace('\\', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }

        // 空字节、以及任何形式的上级目录引用，一律拒绝
        if (path.indexOf('\0') >= 0 || path.contains("..")) {
            return null;
        }

        // 连续斜杠等价于目录穿越的常见写法，一并拒绝
        while (path.contains("//")) {
            path = path.replace("//", "/");
        }

        if (path.isEmpty() || path.endsWith("/")) {
            path = path + INDEX_FILE;
        }
        return path;
    }

    /**
     * 依次从外部目录与 jar 内读取资源。
     */
    private Asset read(String path) {
        byte[] content = readExternal(path);
        if (content != null) {
            return new Asset(content, contentType(path));
        }
        content = readClasspath(path);
        return content == null ? null : new Asset(content, contentType(path));
    }

    private byte[] readExternal(String path) {
        if (!externalRoot.isDirectory()) {
            return null;
        }
        File target = new File(externalRoot, path);
        if (!target.isFile() || !isInsideRoot(target)) {
            return null;
        }
        try {
            return Files.readAllBytes(target.toPath());
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] readClasspath(String path) {
        try (InputStream in = plugin.getResource(CLASSPATH_ROOT + "/" + path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 用规范路径确认目标文件确实在 webassets 目录内。
     * 这一步能同时挡住 {@code ../} 穿越和指向目录外的符号链接。
     */
    private boolean isInsideRoot(File target) {
        try {
            String root = externalRoot.getCanonicalPath();
            String resolved = target.getCanonicalPath();
            return resolved.equals(root) || resolved.startsWith(root + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 按后缀推断 Content-Type。
     */
    public String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return DEFAULT_CONTENT_TYPE;
        }
        String extension = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
    }

    /**
     * 静态资源的缓存策略：HTML 不缓存（保证前端更新后立刻生效），
     * 其余资源短缓存。资源文件名带版本号时可以再放宽。
     */
    public String cacheControl(String path) {
        return path.endsWith(".html") ? "no-cache" : "public, max-age=300";
    }

    /**
     * 外部可覆盖目录，方便管理员自定义前端。
     */
    public File getExternalRoot() {
        return externalRoot;
    }

    /**
     * 一份前端资源。
     */
    public static final class Asset {

        private final byte[] content;
        private final String contentType;

        public Asset(byte[] content, String contentType) {
            this.content = content;
            this.contentType = contentType;
        }

        public byte[] getContent() {
            return content;
        }

        public String getContentType() {
            return contentType;
        }
    }
}
