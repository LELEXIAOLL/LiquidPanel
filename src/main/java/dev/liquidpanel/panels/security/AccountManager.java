package dev.liquidpanel.panels.security;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.utils.FileUtil;
import dev.liquidpanel.utils.JsonUtil;
import dev.liquidpanel.utils.RandomUtil;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/**
 * 面板账户管理器。
 *
 * <p>负责在插件目录下维护 {@code account.json}：
 * <ul>
 *     <li>文件不存在时生成默认账户（admin + 随机 16 位大小写数字混合密码），
 *         并把账号密码打印到控制台；</li>
 *     <li>读取时做完整性校验，文件损坏就备份成 .bak 后重新生成；</li>
 *     <li>对外提供密码校验与修改。</li>
 * </ul>
 */
public final class AccountManager {

    /** 账户文件名 */
    private static final String FILE_NAME = "account.json";

    /** 默认用户名 */
    private static final String DEFAULT_USERNAME = "admin";

    /** 默认密码长度 */
    private static final int DEFAULT_PASSWORD_LENGTH = 16;

    private final JavaPlugin plugin;
    private final File file;

    /** 当前账户，volatile 保证面板线程能看到最新引用 */
    private volatile PanelAccount account;

    public AccountManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
    }

    /**
     * 读取账户文件，不存在或损坏则生成默认账户。
     */
    public synchronized void init() {
        if (file.isFile()) {
            PanelAccount loaded = read();
            if (loaded != null && loaded.isUsable()) {
                this.account = loaded;
                return;
            }
            // 文件损坏：备份后重建，避免管理员卡在无法登录的状态
            File backup = FileUtil.backup(file);
            if (backup != null) {
                console("&e" + FILE_NAME + " 内容异常，已备份为 " + backup.getName());
            }
        }
        createDefault();
    }

    /**
     * 生成默认账户并写盘，同时把账号密码打印到控制台。
     */
    private void createDefault() {
        String password = RandomUtil.password(DEFAULT_PASSWORD_LENGTH);
        PanelAccount created = new PanelAccount(DEFAULT_USERNAME, password);

        if (!save(created)) {
            MessagesManager.log("panel.account.save_failed", "file", FILE_NAME);
            return;
        }
        this.account = created;

        // 明文只在这一次输出。account.json 里存的是摘要，之后无法找回，
        // 忘了就删掉 account.json 让插件重新生成一个。
        MessagesManager.log("panel.account.created", "file", FILE_NAME);
        MessagesManager.log("panel.account.username", "username", created.getUsername());
        MessagesManager.log("panel.account.password", "password", password);
        MessagesManager.log("panel.account.warning");
    }

    /**
     * 校验账号密码。
     */
    public boolean verify(String username, String password) {
        PanelAccount current = account;
        return current != null && current.verify(username, password);
    }

    /**
     * 修改密码，成功后清除 account.json 里的明文初始密码。
     *
     * @return 是否修改成功
     */
    public synchronized boolean changePassword(String oldPassword, String newPassword) {
        PanelAccount current = account;
        if (current == null || !current.verify(current.getUsername(), oldPassword)) {
            return false;
        }
        current.changePassword(newPassword);
        return save(current);
    }

    /**
     * 密码是否仍是初始生成的那个，用于要求首次登录后修改。
     */
    public boolean isPasswordChangeRequired() {
        PanelAccount current = account;
        return current != null && current.isPasswordChangeRequired();
    }

    /**
     * 同时修改用户名与密码。
     */
    public synchronized boolean changeCredentials(String username, String newPassword) {
        PanelAccount current = account;
        if (current == null) {
            return false;
        }
        current.rename(username);
        current.changePassword(newPassword);
        return save(current);
    }

    public String getUsername() {
        PanelAccount current = account;
        return current == null ? DEFAULT_USERNAME : current.getUsername();
    }

    public File getFile() {
        return file;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private PanelAccount read() {
        try {
            return JsonUtil.fromJson(FileUtil.read(file), PanelAccount.class);
        } catch (Exception e) {
            console("&c读取 " + FILE_NAME + " 失败: " + e.getMessage());
            return null;
        }
    }

    private boolean save(PanelAccount target) {
        try {
            FileUtil.write(file, JsonUtil.toPrettyJson(target));
            return true;
        } catch (Exception e) {
            console("&c写入 " + FILE_NAME + " 失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 控制台输出，统一带插件前缀。
     */
    private void console(String message) {
        Bukkit.getConsoleSender().sendMessage(MessagesManager.getPrefix() + MessagesManager.color(message));
    }
}
