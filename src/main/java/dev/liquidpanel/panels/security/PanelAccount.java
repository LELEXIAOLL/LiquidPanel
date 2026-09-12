package dev.liquidpanel.panels.security;

import com.google.gson.annotations.SerializedName;
import dev.liquidpanel.utils.HashUtil;

/**
 * 面板账户模型，对应磁盘上的 account.json。
 *
 * <p>密码一律只存 PBKDF2-HMAC-SHA256 摘要 + 随机盐，文件里不存在任何形式的明文。
 * 默认密码只在 account.json 生成的那一刻于控制台显示一次，之后无法从文件里找回；
 * 忘记了就删掉 account.json 让插件重新生成一个。
 */
public final class PanelAccount {

    @SerializedName("username")
    private String username;

    @SerializedName("password_hash")
    private String passwordHash;

    @SerializedName("salt")
    private String salt;

    @SerializedName("iterations")
    private int iterations = HashUtil.DEFAULT_ITERATIONS;

    /**
     * 密码是否仍是账户生成时的那一个。
     *
     * <p>刻意不靠用户名判断「是不是默认账户」—— 用户名随时可以被改掉，
     * 而这个标志位描述的是「密码有没有被改过」这件事本身，与用户名无关。
     */
    @SerializedName("must_change_password")
    private boolean mustChangePassword;

    @SerializedName("updated_at")
    private long updatedAt;

    /** Gson 反序列化需要 */
    @SuppressWarnings("unused")
    private PanelAccount() {
    }

    public PanelAccount(String username, String plainPassword) {
        this.username = username;
        this.iterations = HashUtil.DEFAULT_ITERATIONS;
        // 刚生成的账户用的是随机初始密码，要求首次登录后自行修改
        this.mustChangePassword = true;
        applyPassword(plainPassword);
    }

    /**
     * 用新密码重新生成盐与摘要。每次改密码都换新盐，避免彩虹表与跨账户比对。
     */
    public void applyPassword(String plainPassword) {
        this.salt = HashUtil.newSalt();
        this.passwordHash = HashUtil.hash(plainPassword, this.salt, this.iterations);
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 修改密码。改完就不再是初始密码了。
     */
    public void changePassword(String newPassword) {
        applyPassword(newPassword);
        this.mustChangePassword = false;
    }

    /**
     * 修改用户名。与密码相互独立，改名字不影响「是否仍用初始密码」的判断。
     */
    public void rename(String newUsername) {
        this.username = newUsername;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 密码是否仍是初始生成的那个。
     */
    public boolean isPasswordChangeRequired() {
        return mustChangePassword;
    }

    /**
     * 校验密码。
     *
     * <p>无论账号名对不对都会走一次摘要计算，并且用恒定时间比较，
     * 避免通过响应时间探测「账号是否存在」。
     */
    public boolean verify(String inputUsername, String inputPassword) {
        boolean passwordMatches = HashUtil.verify(inputPassword, salt, iterations, passwordHash);
        return HashUtil.constantTimeEquals(username, inputUsername) && passwordMatches;
    }

    /**
     * 文件内容是否完整可用。缺字段或被人手改坏了都会返回 false，由上层重建。
     */
    public boolean isUsable() {
        return username != null && !username.isBlank()
                && passwordHash != null && !passwordHash.isBlank()
                && salt != null && !salt.isBlank()
                && iterations > 0;
    }

    public String getUsername() {
        return username;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }
}
