package dev.liquidpanel.panels.files;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文件与目录的基础操作。
 *
 * <p>本类只做「已经解析好的路径」上的操作，<b>不负责安全判断</b> ——
 * 所有入口都必须先用 {@link ServerPaths} 把客户端传来的相对路径解析成绝对路径，
 * 再交给这里。这样职责单一，安全边界只有一处。
 */
public final class FileService {

    /** 目录列表上限，避免有人打开一个几十万文件的目录把面板拖死 */
    private static final int MAX_ENTRIES = 2000;

    private final ServerPaths paths;

    public FileService(ServerPaths paths) {
        this.paths = paths;
    }

    /**
     * 列出目录内容。目录在前、文件在后，各自按名称排序。
     */
    public List<Entry> list(Path directory) throws IOException {
        List<Entry> result = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                if (result.size() >= MAX_ENTRIES) {
                    break;
                }
                result.add(describe(child));
            }
        }

        result.sort(Comparator
                .comparing((Entry entry) -> !entry.directory)
                .thenComparing(entry -> entry.name.toLowerCase()));

        return result;
    }

    private Entry describe(Path path) {
        Entry entry = new Entry();
        entry.name = path.getFileName().toString();
        entry.path = paths.relative(path);

        // 符号链接一律按「不是目录」处理，避免点进去被带到根目录外
        entry.directory = Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try {
            entry.modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            entry.modified = 0L;
        }
        if (!entry.directory) {
            try {
                entry.size = Files.size(path);
            } catch (IOException ignored) {
                entry.size = 0L;
            }
            entry.editable = TextFiles.looksEditable(entry.name, entry.size);
        }
        return entry;
    }

    /**
     * 递归取目录大小，用于删除前提示。取不到的部分跳过。
     */
    public long size(Path path) throws IOException {
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Files.size(path);
        }

        long[] total = {0L};
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                total[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception) {
                return FileVisitResult.CONTINUE;
            }
        });
        return total[0];
    }

    /**
     * 新建目录。
     */
    public void createDirectory(Path path) throws IOException {
        Files.createDirectories(path);
    }

    /**
     * 新建空文件。
     */
    public void createFile(Path path) throws IOException {
        Files.createFile(path);
    }

    /**
     * 重命名（同目录内改名）。
     */
    public void rename(Path source, String newName) throws IOException {
        Path parent = source.getParent();
        if (parent == null) {
            throw new SecurityException("路径不合法");
        }
        // requireSimpleName 已经挡掉了分隔符，名字不可能跳出父目录
        Path target = parent.resolve(ServerPaths.requireSimpleName(newName));

        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("同名文件已存在");
        }
        Files.move(source, target);
    }

    /**
     * 删除，目录会递归删除。
     */
    public void delete(Path path) throws IOException {
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.delete(path);
            return;
        }

        // 目录本身可能是符号链接，delete 只删链接不删内容
        if (Files.isSymbolicLink(path)) {
            Files.delete(path);
            return;
        }

        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 复制到目标目录。目录会递归复制。
     */
    public void copy(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName().toString());
        if (target.equals(source)) {
            throw new IOException("源与目标相同");
        }
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(source.getFileName() + " 在目标目录中已存在");
        }
        copyRecursive(source, target);
    }

    /**
     * 移动到目标目录。
     */
    public void move(Path source, Path targetDir) throws IOException {
        Path target = targetDir.resolve(source.getFileName().toString());
        if (target.equals(source)) {
            throw new IOException("源与目标相同");
        }
        // 不能把目录移进它自己里面
        if (source.toRealPath().startsWith(targetDir.toRealPath())) {
            throw new IOException("不能把目录移动到它自己内部");
        }
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(source.getFileName() + " 在目标目录中已存在");
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private void copyRecursive(Path source, Path target) throws IOException {
        if (Files.isDirectory(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(target);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(source)) {
                for (Path child : stream) {
                    copyRecursive(child, target.resolve(child.getFileName().toString()));
                }
            }
            return;
        }
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    /**
     * 一条目录项。
     */
    public static final class Entry {

        /** 显示名 */
        public String name;

        /** 相对服务端根目录的路径，正斜杠分隔 */
        public String path;

        /** 是否是目录 */
        public boolean directory;

        /** 字节数，目录为 0 */
        public long size;

        /** 最后修改时间戳 */
        public long modified;

        /**
         * 能否用文本编辑器打开。仅凭扩展名与大小判断（不读内容），
         * 真正打开时服务端还会再查一遍内容，这里只是让前端提前知道。
         */
        public boolean editable;
    }
}
