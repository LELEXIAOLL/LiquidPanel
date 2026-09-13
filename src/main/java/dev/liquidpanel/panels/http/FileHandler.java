package dev.liquidpanel.panels.http;

import com.google.gson.JsonObject;
import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.files.ArchiveService;
import dev.liquidpanel.panels.files.FileService;
import dev.liquidpanel.panels.files.ServerPaths;
import dev.liquidpanel.panels.files.TextFiles;
import dev.liquidpanel.panels.security.HostValidator;
import dev.liquidpanel.panels.security.PanelSession;
import dev.liquidpanel.panels.security.SessionManager;
import dev.liquidpanel.utils.JsonUtil;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件管理接口。
 *
 * <p>所有路径都先交给 {@link ServerPaths} 解析，解析不过就直接拒绝，
 * 本类里不再出现任何路径拼接。
 *
 * <p>下载与上传都是流式的，不会把整个文件读进内存。
 */
public final class FileHandler {

    /** 单次上传上限 */
    public static final long MAX_UPLOAD_BYTES = 256L * 1024 * 1024;

    /** 文本编辑器上限，与 TextFiles 共用同一个值 */
    private static final long MAX_TEXT_BYTES = TextFiles.MAX_BYTES;

    private final SessionManager sessionManager;
    private final HostValidator hostValidator;
    private final ServerPaths paths;
    private final FileService files;
    private final ArchiveService archives;

    public FileHandler(SessionManager sessionManager,
                       HostValidator hostValidator,
                       ServerPaths paths,
                       FileService files,
                       ArchiveService archives) {
        this.sessionManager = sessionManager;
        this.hostValidator = hostValidator;
        this.paths = paths;
        this.files = files;
        this.archives = archives;
    }

    // ------------------------------------------------------------------
    // GET /api/files —— 列目录
    // ------------------------------------------------------------------

    public void list(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.GET)) {
            return;
        }

        try {
            Path directory = paths.existing(query(exchange, "path"));
            if (!Files.isDirectory(directory)) {
                HttpUtil.sendError(exchange, 400, "不是目录");
                return;
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", paths.relative(directory));
            data.put("root", paths.getRoot().toString());
            data.put("entries", files.list(directory));
            HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 404, "目录不存在或无法读取");
        }
    }

    // ------------------------------------------------------------------
    // GET /api/files/download
    // ------------------------------------------------------------------

    public void download(HttpServerExchange exchange) {
        // 允许 HEAD：前端先用它确认文件可下，再真正触发下载，
        // 这样出错时不会把整个面板导航到一个 JSON 错误页上
        if (!guard(exchange, Methods.GET, Methods.HEAD)) {
            return;
        }

        Path file;
        try {
            file = paths.existing(query(exchange, "path"));
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
            return;
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 404, "文件不存在");
            return;
        }

        if (!Files.isRegularFile(file)) {
            HttpUtil.sendError(exchange, 400, "不是文件");
            return;
        }

        try {
            long size = Files.size(file);
            exchange.setStatusCode(200);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
            // RFC 5987，中文文件名也不会乱码
            exchange.getResponseHeaders().put(Headers.CONTENT_DISPOSITION,
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(
                            file.getFileName().toString(), StandardCharsets.UTF_8).replace("+", "%20"));
            exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, String.valueOf(size));

            if (Methods.HEAD.equals(exchange.getRequestMethod())) {
                exchange.endExchange();
                return;
            }

            exchange.startBlocking();
            try (InputStream in = Files.newInputStream(file);
                 OutputStream out = exchange.getOutputStream()) {
                in.transferTo(out);
            }
        } catch (IOException e) {
            // 响应可能已经开始写了，只能中断连接
            exchange.endExchange();
        }
    }

    // ------------------------------------------------------------------
    // POST /api/files/action —— 各类操作
    // ------------------------------------------------------------------

    public void action(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.POST)) {
            return;
        }

        JsonObject body;
        try {
            body = JsonUtil.parseObject(HttpUtil.readBody(exchange));
        } catch (HttpUtil.BodyTooLargeException e) {
            HttpUtil.sendError(exchange, 413, "请求体过大");
            return;
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "请求内容不合法");
            return;
        }

        String action = JsonUtil.optString(body, "action", "");
        try {
            switch (action) {
                case "mkdir" -> createDirectory(exchange, body);
                case "newfile" -> createFile(exchange, body);
                case "rename" -> rename(exchange, body);
                case "delete" -> delete(exchange, body);
                case "copy" -> transfer(exchange, body, true);
                case "move" -> transfer(exchange, body, false);
                case "compress" -> compress(exchange, body);
                case "extract" -> extract(exchange, body);
                default -> HttpUtil.sendError(exchange, 400, "未知操作: " + action);
            }
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 400, e.getMessage() == null ? "操作失败" : e.getMessage());
        }
    }

    private void createDirectory(HttpServerExchange exchange, JsonObject body) throws IOException {
        Path target = paths.forCreate(JsonUtil.optString(body, "path", ""));
        files.createDirectory(target);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已新建文件夹", null));
    }

    private void createFile(HttpServerExchange exchange, JsonObject body) throws IOException {
        Path target = paths.forCreate(JsonUtil.optString(body, "path", ""));
        files.createFile(target);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已新建文件", null));
    }

    private void rename(HttpServerExchange exchange, JsonObject body) throws IOException {
        Path source = paths.existing(JsonUtil.optString(body, "path", ""));
        String name = JsonUtil.optString(body, "name", "");
        files.rename(source, name);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已重命名", null));
    }

    private void delete(HttpServerExchange exchange, JsonObject body) throws IOException {
        for (Path path : resolveAll(body)) {
            files.delete(path);
        }
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已删除", null));
    }

    private void transfer(HttpServerExchange exchange, JsonObject body, boolean copy) throws IOException {
        Path targetDir = paths.existing(JsonUtil.optString(body, "target", ""));
        if (!Files.isDirectory(targetDir)) {
            HttpUtil.sendError(exchange, 400, "目标不是目录");
            return;
        }

        for (Path source : resolveAll(body)) {
            if (copy) {
                files.copy(source, targetDir);
            } else {
                files.move(source, targetDir);
            }
        }
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok(copy ? "已复制" : "已剪切", null));
    }

    private void compress(HttpServerExchange exchange, JsonObject body) throws IOException {
        List<Path> sources = resolveAll(body);
        if (sources.isEmpty()) {
            HttpUtil.sendError(exchange, 400, "请先选择要压缩的内容");
            return;
        }

        String targetDirText = JsonUtil.optString(body, "target", "");
        Path targetDir = targetDirText.isEmpty() ? sources.get(0).getParent() : paths.existing(targetDirText);

        ArchiveService.Format format = ArchiveService.Format.parse(
                JsonUtil.optString(body, "format", ""), null);

        String baseName = sources.size() == 1
                ? sources.get(0).getFileName().toString()
                : targetDir.getFileName() == null ? "archive" : targetDir.getFileName().toString();

        Path destination = paths.forCreate(
                paths.relative(targetDir) + "/" + baseName + format.extension());

        archives.compress(sources, destination, format);
        HttpUtil.sendJson(exchange, 200,
                ApiResponse.ok("已压缩为 " + destination.getFileName(), null));
    }

    private void extract(HttpServerExchange exchange, JsonObject body) throws IOException {
        Path archive = paths.existing(JsonUtil.optString(body, "path", ""));
        if (!Files.isRegularFile(archive)) {
            HttpUtil.sendError(exchange, 400, "不是文件");
            return;
        }

        String targetText = JsonUtil.optString(body, "target", "");
        // resolve 对已存在的路径返回真实路径，对不存在的返回待创建路径
        Path targetDir = targetText.isEmpty() ? archive.getParent() : paths.resolve(targetText);
        if (targetDir == null) {
            HttpUtil.sendError(exchange, 400, "目标目录不合法");
            return;
        }
        Files.createDirectories(targetDir);
        // 建完再确认真实路径仍在根目录内，防止中途被符号链接带走
        paths.requireInside(targetDir.toRealPath());

        ArchiveService.Format format = ArchiveService.Format.parse(
                JsonUtil.optString(body, "format", ""), archive.getFileName().toString());

        archives.extract(archive, targetDir, format);
        HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已解压到 " + paths.relative(targetDir), null));
    }

    // ------------------------------------------------------------------
    // POST /api/files/upload?path=<目录>&name=<文件名>
    // ------------------------------------------------------------------

    /**
     * 上传单个文件。请求体就是文件的原始字节。
     *
     * <p>用「先写临时文件再改名」的方式落盘：上传中断不会留下一个半截的文件。
     * 读取走阻塞流，因此本方法运行在 Undertow 的<b>工作线程</b>上；
     * 由于上传同样要求有效会话，未认证者无法借此占用线程。
     */
    public void upload(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.POST)) {
            return;
        }

        String directory = query(exchange, "path");
        String name = query(exchange, "name");

        Path target;
        try {
            // 目录必须已存在，文件名单独做合法性检查
            Path dir = paths.existing(directory);
            if (!Files.isDirectory(dir)) {
                HttpUtil.sendError(exchange, 400, "目标不是目录");
                return;
            }
            target = paths.forCreate(paths.relative(dir) + "/" + ServerPaths.requireSimpleName(name));
            if (Files.isDirectory(target)) {
                HttpUtil.sendError(exchange, 400, "同名文件夹已存在");
                return;
            }
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
            return;
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 404, "目标目录不存在");
            return;
        }

        Path temp = target.resolveSibling(target.getFileName() + ".uploading");
        long written = 0L;

        try {
            if (!exchange.isBlocking()) {
                exchange.startBlocking();
            }
            try (InputStream in = exchange.getInputStream();
                 OutputStream out = Files.newOutputStream(temp,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    written += read;
                    if (written > MAX_UPLOAD_BYTES) {
                        throw new IOException("文件超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MB 上限");
                    }
                    out.write(buffer, 0, read);
                }
            }

            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            HttpUtil.sendJson(exchange, 200,
                    ApiResponse.ok("已上传 " + target.getFileName(), null));
        } catch (Exception e) {
            deleteQuietly(temp);
            if (!exchange.isResponseStarted()) {
                HttpUtil.sendError(exchange, 400, e.getMessage() == null ? "上传失败" : e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // GET / POST /api/files/text —— 文本文件读写
    // ------------------------------------------------------------------

    /**
     * 文本文件读取或写入，按请求方法区分。
     */
    public void text(HttpServerExchange exchange) {
        if (Methods.POST.equals(exchange.getRequestMethod())) {
            writeText(exchange);
            return;
        }
        readText(exchange);
    }

    private void readText(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.GET)) {
            return;
        }

        Path file;
        try {
            file = paths.existing(query(exchange, "path"));
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
            return;
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 404, "文件不存在");
            return;
        }

        if (!Files.isRegularFile(file)) {
            HttpUtil.sendError(exchange, 400, "不是文件");
            return;
        }

        // 扩展名一看就知道不是文本的，直接拒绝，不读内容
        if (TextFiles.hasBlockedExtension(file.getFileName().toString())) {
            HttpUtil.sendError(exchange, 400, TextFiles.describeReason(file.getFileName().toString()));
            return;
        }

        try {
            long size = Files.size(file);
            if (size > MAX_TEXT_BYTES) {
                HttpUtil.sendError(exchange, 400,
                        "文件过大（" + formatSize(size) + "），编辑器上限 " + formatSize(MAX_TEXT_BYTES));
                return;
            }

            byte[] bytes = Files.readAllBytes(file);
            if (containsNul(bytes)) {
                HttpUtil.sendError(exchange, 400, "这看起来是二进制文件，无法用文本编辑器打开");
                return;
            }

            // 不是合法 UTF-8 也放行（可能是 GBK 的老配置），但如实告诉前端
            boolean utf8 = isUtf8(bytes);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", paths.relative(file));
            data.put("content", new String(bytes, StandardCharsets.UTF_8));
            data.put("size", size);
            data.put("utf8", utf8);
            HttpUtil.sendJson(exchange, 200, ApiResponse.ok(data));
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 400, "读取失败");
        }
    }

    private void writeText(HttpServerExchange exchange) {
        if (!guard(exchange, Methods.POST)) {
            return;
        }

        JsonObject body;
        try {
            body = JsonUtil.parseObject(HttpUtil.readBody(exchange));
        } catch (HttpUtil.BodyTooLargeException e) {
            HttpUtil.sendError(exchange, 413, "内容过大");
            return;
        } catch (Exception e) {
            HttpUtil.sendError(exchange, 400, "请求内容不合法");
            return;
        }

        String relative = JsonUtil.optString(body, "path", "");
        String content = JsonUtil.optString(body, "content", "");

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) {
            HttpUtil.sendError(exchange, 400, "内容过大，上限 " + formatSize(MAX_TEXT_BYTES));
            return;
        }

        try {
            Path target = paths.resolve(relative);
            if (Files.isDirectory(target)) {
                HttpUtil.sendError(exchange, 400, "目标是目录");
                return;
            }

            // 先写临时文件再改名，写一半失败不会把原文件毁掉
            Path temp = target.resolveSibling(target.getFileName() + ".saving");
            Files.write(temp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);

            // 文件编辑属于高影响操作，留一条审计记录
            MessagesManager.logRaw("§7[面板] §f" + currentUser(exchange) + " §7编辑了文件: §f/" + relative);
            HttpUtil.sendJson(exchange, 200, ApiResponse.ok("已保存", null));
        } catch (SecurityException e) {
            HttpUtil.sendError(exchange, 403, e.getMessage());
        } catch (IOException e) {
            HttpUtil.sendError(exchange, 400, "保存失败: " + e.getMessage());
        }
    }

    private String currentUser(HttpServerExchange exchange) {
        PanelSession session = HttpUtil.resolveSession(exchange, sessionManager);
        return session == null ? "未知" : session.getUsername();
    }

    private boolean containsNul(byte[] bytes) {
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    private boolean isUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return (bytes / 1024) + " KB";
        }
        return (bytes / 1024 / 1024) + " MB";
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 会话 + 同源 + 方法，三件事一起做 */
    private boolean guard(HttpServerExchange exchange, io.undertow.util.HttpString... methods) {
        if (!HttpUtil.requireMethod(exchange, methods)) {
            return false;
        }
        if (paths == null) {
            // 根目录解析失败时构造器拿不到 ServerPaths，此时一律拒绝而不是放开
            HttpUtil.sendError(exchange, 503, "文件管理不可用：无法解析服务端根目录");
            return false;
        }
        if (!HttpUtil.isSameOrigin(exchange, hostValidator)) {
            HttpUtil.sendError(exchange, 403, "请求来源不合法");
            return false;
        }
        PanelSession session = HttpUtil.requireSession(exchange, sessionManager);
        return session != null;
    }

    private String query(HttpServerExchange exchange, String key) {
        return HttpUtil.parseQuery(exchange.getQueryString()).getOrDefault(key, "");
    }

    /** 逐个解析 paths 数组，任一越界立即中止 */
    private List<Path> resolveAll(JsonObject body) throws IOException {
        List<String> requested = new ArrayList<>();
        if (body != null && body.has("paths") && body.get("paths").isJsonArray()) {
            body.getAsJsonArray("paths").forEach(element -> requested.add(element.getAsString()));
        }

        List<Path> resolved = new ArrayList<>();
        for (String relative : requested) {
            resolved.add(paths.existing(relative));
        }
        return resolved;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败没有补救价值
        }
    }
}
