package dev.liquidpanel.panels.database;

import dev.liquidpanel.utils.FileUtil;
import dev.liquidpanel.utils.JsonUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把玩家名册存成一个 JSON 文件。
 *
 * <p>整份读进内存、全量重写。写入走 {@link FileUtil#write} 的
 * 「先写临时文件再原子替换」，掉电或进程被杀最多丢掉最后一次变更，
 * 不会留下半截文件。
 */
public final class JsonPlayerStore implements PlayerStore {

    private static final int FORMAT_VERSION = 1;

    public static final String TYPE = "json";

    private final File file;

    public JsonPlayerStore(File file) {
        this.file = file;
    }

    @Override
    public void open() throws Exception {
        if (!file.isFile()) {
            // 首次启动写一份空的出来，管理员一眼能看出东西在哪、长什么样
            FileUtil.write(file, JsonUtil.toPrettyJson(new Wrapper()));
        }
    }

    @Override
    public Map<String, PlayerProfile> load() throws Exception {
        Map<String, PlayerProfile> profiles = new LinkedHashMap<>();
        if (!file.isFile()) {
            return profiles;
        }

        String text = FileUtil.read(file);
        if (text.isBlank()) {
            return profiles;
        }

        Wrapper wrapper = JsonUtil.fromJson(text, Wrapper.class);
        if (wrapper != null && wrapper.players != null) {
            for (PlayerProfile profile : wrapper.players) {
                // 手改过的文件可能塞进 null 元素、漏了 uuid，或者名字是空的。
                // 名字为 null 的条目放进去，后面按名字比对的地方会直接 NPE
                if (profile != null && profile.uuid != null && profile.name != null) {
                    profiles.put(profile.uuid, profile);
                }
            }
        }
        return profiles;
    }

    @Override
    public void saveAll(Collection<PlayerProfile> profiles) throws Exception {
        Wrapper wrapper = new Wrapper();
        wrapper.version = FORMAT_VERSION;
        wrapper.players = new ArrayList<>(profiles);
        FileUtil.write(file, JsonUtil.toPrettyJson(wrapper));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void close() {
        // 每次变更都已经落盘，没有需要收尾的资源
    }

    /**
     * 文件的顶层结构。留一层对象，日后加字段不会把已有文件顶成不兼容格式。
     */
    private static final class Wrapper {

        int version = FORMAT_VERSION;

        List<PlayerProfile> players = new ArrayList<>();
    }
}
