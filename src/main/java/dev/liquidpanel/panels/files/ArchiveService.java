package dev.liquidpanel.panels.files;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 压缩与解压。
 *
 * <p>zip 用 JDK 自带的 {@code java.util.zip}，7z 用 commons-compress ——
 * JDK 完全没有 7z 支持。
 *
 * <h2>解压必须防目录穿越（zip-slip）</h2>
 * <p>压缩包里的条目名是攻击者可控的，{@code ../../server.properties} 这种条目
 * 一旦被老老实实拼到目标目录后面，就会写到根目录外面去。
 * 所以每个条目都要过 {@link #safeTarget}：归一化后必须仍在目标目录内，
 * 且父目录解析出的<b>真实路径</b>也必须在里面（防止目录里预先埋好符号链接）。</p>
 */
public final class ArchiveService {

    private static final int BUFFER = 8192;

    /** 压缩格式 */
    public enum Format {
        ZIP, SEVEN_ZIP;

        /**
         * 按「显式指定 > 扩展名推断 > 默认 zip」的顺序判定，认不出来就是 zip。
         */
        public static Format parse(String text, String fileNameHint) {
            Format fromText = byName(text);
            if (fromText != null) {
                return fromText;
            }
            Format fromFile = byName(fileNameHint);
            return fromFile == null ? ZIP : fromFile;
        }

        private static Format byName(String value) {
            if (value == null) {
                return null;
            }
            String name = value.toLowerCase(Locale.ROOT);
            if (name.endsWith(".7z") || name.contains("7z") || name.contains("seven")) {
                return SEVEN_ZIP;
            }
            if (name.endsWith(".zip") || name.contains("zip")) {
                return ZIP;
            }
            return null;
        }

        public String extension() {
            return this == SEVEN_ZIP ? ".7z" : ".zip";
        }
    }

    // ------------------------------------------------------------------
    // 压缩
    // ------------------------------------------------------------------

    /**
     * 把若干文件/目录压成一个压缩包。
     */
    public void compress(List<Path> sources, Path destination, Format format) throws IOException {
        if (format == Format.SEVEN_ZIP) {
            compressSevenZip(sources, destination);
        } else {
            compressZip(sources, destination);
        }
    }

    private void compressZip(List<Path> sources, Path destination) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(destination)))) {
            out.setLevel(Deflater.DEFAULT_COMPRESSION);
            for (Path source : sources) {
                addToZip(out, source, source.getFileName().toString());
            }
        }
    }

    private void addToZip(ZipOutputStream out, Path source, String entryName) throws IOException {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            out.putNextEntry(new ZipEntry(entryName + "/"));
            out.closeEntry();

            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    addToZip(out, child, entryName + "/" + child.getFileName());
                }
            }
            return;
        }

        out.putNextEntry(new ZipEntry(entryName));
        Files.copy(source, out);
        out.closeEntry();
    }

    private void compressSevenZip(List<Path> sources, Path destination) throws IOException {
        try (SevenZOutputFile out = new SevenZOutputFile(destination.toFile())) {
            for (Path source : sources) {
                addToSevenZip(out, source, source.getFileName().toString());
            }
            // 关闭时会写入头部，不需要再手动 finish
        }
    }

    private void addToSevenZip(SevenZOutputFile out, Path source, String entryName) throws IOException {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(entryName);

        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            entry.setDirectory(true);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();

            try (DirectoryStream<Path> children = Files.newDirectoryStream(source)) {
                for (Path child : children) {
                    addToSevenZip(out, child, entryName + "/" + child.getFileName());
                }
            }
            return;
        }

        entry.setDirectory(false);
        entry.setSize(Files.size(source));
        out.putArchiveEntry(entry);
        try (InputStream in = Files.newInputStream(source)) {
            out.write(in);
        }
        out.closeArchiveEntry();
    }

    // ------------------------------------------------------------------
    // 解压
    // ------------------------------------------------------------------

    /**
     * 解压到目标目录（目标目录必须已存在且在根目录内，由调用方保证）。
     */
    public void extract(Path archive, Path destinationDir, Format format) throws IOException {
        if (format == Format.SEVEN_ZIP) {
            extractSevenZip(archive, destinationDir);
        } else {
            extractZip(archive, destinationDir);
        }
    }

    private void extractZip(Path archive, Path destinationDir) throws IOException {
        Path destReal = destinationDir.toRealPath();

        try (ZipInputStream in = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = safeTarget(destReal, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                in.closeEntry();
            }
        }
    }

    private void extractSevenZip(Path archive, Path destinationDir) throws IOException {
        Path destReal = destinationDir.toRealPath();
        byte[] buffer = new byte[BUFFER];

        try (SevenZFile in = new SevenZFile(archive.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path target = safeTarget(destReal, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }

                Path parent = target.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /**
     * 把压缩包里的条目名解析成安全的目标路径，越界一律抛异常中止。
     */
    private Path safeTarget(Path destinationReal, String entryName) throws IOException {
        if (entryName == null || entryName.indexOf('\0') >= 0) {
            throw new IOException("压缩包内含有非法条目名");
        }

        // 压缩包内可能用反斜杠分隔，统一成正斜杠再解析
        String normalized = entryName.replace('\\', '/');
        Path target = destinationReal.resolve(normalized).normalize();

        // 绝对路径（/etc/... 或 C:\...）归一化后不会在目标目录内，这里就被挡下
        if (!target.startsWith(destinationReal)) {
            throw new IOException("压缩包条目越界，已中止解压: " + entryName);
        }

        // 父目录若已存在，还要确认真实路径没被预先埋好的符号链接带出去
        Path parent = target.getParent();
        if (parent != null && Files.exists(parent)) {
            if (!parent.toRealPath().startsWith(destinationReal)) {
                throw new IOException("压缩包条目越界，已中止解压: " + entryName);
            }
        }
        return target;
    }
}
