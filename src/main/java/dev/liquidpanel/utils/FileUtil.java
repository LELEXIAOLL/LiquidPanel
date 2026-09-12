package dev.liquidpanel.utils;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * 文件读写工具类。
 *
 * <p>把「读 UTF-8 文本 / 原子写入 / 释放 jar 内资源 / 备份」这几件重复的事收在一处，
 * 其他类不要再自己拼 File + FileWriter。
 */
public final class FileUtil {

    /** 备份文件后缀 */
    public static final String BACKUP_SUFFIX = ".bak";

    private FileUtil() {
    }

    /**
     * 确保目录存在。
     */
    public static File ensureDirectory(File directory) {
        if (directory != null && !directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new IllegalStateException("无法创建目录: " + directory.getAbsolutePath());
        }
        return directory;
    }

    /**
     * 读取 UTF-8 文本。
     */
    public static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * 以 UTF-8 原子写入：先写临时文件再替换，避免写到一半崩溃导致文件损坏。
     */
    public static void write(File file, String content) throws IOException {
        ensureDirectory(file.getParentFile());

        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temp.toPath(), content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持原子移动，退化成普通替换
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把 jar 内的资源释放到磁盘。
     *
     * @param replace true 表示覆盖已存在的文件
     * @return 是否释放成功
     */
    public static boolean release(JavaPlugin plugin, String resource, File target, boolean replace) {
        if (target.exists() && !replace) {
            return true;
        }
        ensureDirectory(target.getParentFile());

        try (InputStream in = plugin.getResource(resource)) {
            if (in == null) {
                return false;
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 把文件备份为「原名 + .bak」，已存在的旧备份会被覆盖。
     *
     * @return 备份文件，失败返回 null
     */
    public static File backup(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        File target = new File(file.getParentFile(), file.getName() + BACKUP_SUFFIX);
        try {
            Files.copy(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return target;
        } catch (IOException e) {
            return null;
        }
    }
}
