package dev.liquidpanel.panels.database;

import dev.liquidpanel.utils.FileUtil;
import dev.liquidpanel.utils.JsonUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把封禁记录存成一个 JSON 文件。
 *
 * <p>记录数量很小（几条到几百条），所以整份读进内存，每次变更整体重写。
 * 写入走 {@link FileUtil#write} 的「先写临时文件再原子替换」，
 * 掉电或进程被杀最多丢掉最后一次变更，不会留下半截文件。
 */
public final class JsonBanStore implements BanStore {

    /** 存文件格式版本，日后结构有变动时用来判断要不要迁移 */
    private static final int FORMAT_VERSION = 1;

    /** 存储类型名，对应 config.yml 里 panel.database.type 的可选值 */
    public static final String TYPE = "json";

    private final File file;

    /** 全量缓存，读操作返回它的副本 */
    private final List<BanRecord> cache = new ArrayList<>();

    public JsonBanStore(File file) {
        this.file = file;
    }

    @Override
    public synchronized void open() throws Exception {
        cache.clear();

        if (!file.isFile()) {
            // 首次启动写一份空的出来，管理员一眼能看出东西在哪、长什么样
            persist();
            return;
        }

        String text = FileUtil.read(file);
        if (text.isBlank()) {
            return;
        }

        Wrapper wrapper = JsonUtil.fromJson(text, Wrapper.class);
        if (wrapper != null && wrapper.bans != null) {
            // 手改过的文件可能塞进 null 元素，过滤掉
            for (BanRecord record : wrapper.bans) {
                if (record != null && record.id != null) {
                    cache.add(record);
                }
            }
        }
    }

    @Override
    public synchronized void put(BanRecord record) throws Exception {
        cache.removeIf(existing -> existing.id.equals(record.id));
        cache.add(record);
        persist();
    }

    @Override
    public synchronized void remove(String id) throws Exception {
        if (id != null && cache.removeIf(record -> id.equals(record.id))) {
            persist();
        }
    }

    @Override
    public synchronized List<BanRecord> all() {
        return new ArrayList<>(cache);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public synchronized void close() {
        // 每次变更都已经落盘，没有需要收尾的资源
    }

    private void persist() throws Exception {
        Wrapper wrapper = new Wrapper();
        wrapper.version = FORMAT_VERSION;
        wrapper.bans = new ArrayList<>(cache);
        FileUtil.write(file, JsonUtil.toPrettyJson(wrapper));
    }

    /**
     * 文件的顶层结构。
     *
     * <p>刻意不写成裸数组：留一层对象，日后要加 version 之外的字段
     * 不会把已有文件顶成不兼容格式。
     */
    private static final class Wrapper {

        int version = FORMAT_VERSION;

        List<BanRecord> bans = new ArrayList<>();
    }
}
