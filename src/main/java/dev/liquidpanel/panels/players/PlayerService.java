package dev.liquidpanel.panels.players;

import dev.liquidpanel.configs.MessagesManager;
import dev.liquidpanel.panels.database.BanRecord;
import dev.liquidpanel.panels.database.BanStore;
import dev.liquidpanel.panels.database.PlayerProfile;
import dev.liquidpanel.panels.economy.EconomyService;
import dev.liquidpanel.utils.TaskUtil;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 在线玩家数据与操作。
 *
 * <p>Bukkit API 全部只能在主线程调用，所以这里每个方法都通过
 * {@link TaskUtil#awaitSync} 切过去执行；调用方是 Undertow 的工作线程，
 * 短暂阻塞它不会影响服务端，而且带超时兜底。
 *
 * <p>操作失败一律抛 {@link PlayerActionException}，由接口层转成带原因的 400。
 */
public final class PlayerService {

    /** 主线程操作的等待上限 */
    private static final long OPERATION_TIMEOUT_MILLIS = 3000L;

    /** 皮肤链接缓存上限 */
    private static final int MAX_SKIN_CACHE = 512;

    /** 玩家 UUID -> 皮肤链接，空串表示确认过没有皮肤 */
    private final java.util.Map<String, String> skinCache = new java.util.concurrent.ConcurrentHashMap<>();

    /** 名册：列出「所有玩家」「离线玩家」要靠它 */
    private final PlayerRegistry registry;

    /**
     * 封禁记录。只在<b>低频路径</b>读 —— 它是磁盘 IO，
     * 而在线列表每 3 刻就要采集一次，绝不能带进去。
     */
    private final BanStore banStore;

    /** Vault 经济。没开开关或服务端没装时它自己会返回「不可用」 */
    private final EconomyService economy;

    public PlayerService(PlayerRegistry registry, BanStore banStore, EconomyService economy) {
        this.registry = registry;
        this.banStore = banStore;
        this.economy = economy;
    }

    /**
     * 在线玩家，<b>必须在主线程调用</b>。
     *
     * <p>状态推送本来就在主线程采集，直接调这个，省掉一次线程往返。
     * 这是高频路径（每 3 刻一次），所以只碰在线那部分数据。
     */
    public List<Entry> snapshotOnline() {
        return collectOnline();
    }

    /**
     * 在线玩家列表。任意线程可调，内部会切回主线程。
     */
    public List<Entry> listOnline() {
        List<Entry> entries = TaskUtil.awaitSync(this::collectOnline, null);
        return entries == null ? List.of() : entries;
    }

    /**
     * 按筛选条件取玩家。任意线程可调。
     *
     * <p>这是<b>低频路径</b>：只在切筛选时走一次，不像在线列表那样持续推送。
     * 「全部」要遍历整份名册，几千人的服不适合每 3 刻跑一遍。
     */
    public List<Entry> list(PlayerFilter filter) {
        // 封禁记录是磁盘 IO，必须在切主线程之前读完 —— 带进去就是把主线程堵在文件读写上
        Set<String> fromStore = bannedFromStore();

        List<Entry> entries = TaskUtil.awaitSync(() -> collect(filter, fromStore), null);
        return entries == null ? List.of() : entries;
    }

    /**
     * 踢出。
     */
    public void kick(String name, String reason) {
        // 原因允许写 & 颜色代码，翻译成 § 之后玩家在断开界面上能看到颜色
        String message = reason.isEmpty() ? "你已被管理员踢出" : MessagesManager.color(reason);
        run(name, player -> player.kickPlayer(message));
    }

    /**
     * 封禁并踢出。封禁记录写在服务端自己的名单里，重启依然生效。
     */
    public void ban(String name, String reason) {
        String finalReason = reason.isEmpty() ? "你已被管理员封禁" : MessagesManager.color(reason);
        run(name, player -> {
            Bukkit.getBanList(BanList.Type.NAME).addBan(player.getName(), finalReason, null, "LiquidPanel");
            player.kickPlayer(finalReason);
        });
    }

    /**
     * 传送。目标是另一个玩家名，或者「x y z」（用玩家当前所在世界）。
     */
    public void teleport(String name, String target) {
        String trimmed = target == null ? "" : target.trim();
        if (trimmed.isEmpty()) {
            throw new PlayerActionException("请填写传送目标");
        }

        run(name, player -> {
            // 解析必须在这里做：~ 要基于玩家「此刻」的位置，
            // 前端那份坐标是上一次推送的快照，玩家一直在动，拿它算会偏。
            Location origin = player.getLocation();
            double[] coords = parseCoordinates(trimmed, origin);

            if (coords != null) {
                player.teleport(new Location(origin.getWorld(), coords[0], coords[1], coords[2],
                        origin.getYaw(), origin.getPitch()));
                return;
            }

            Player destination = Bukkit.getPlayerExact(trimmed);
            if (destination == null) {
                throw new PlayerActionException("玩家 " + trimmed + " 不在线");
            }
            player.teleport(destination.getLocation());
        });
    }

    /**
     * 以该玩家的身份执行命令。
     *
     * <p>相当于 {@code execute at <玩家> run <命令>}：命令以玩家的身份、权限与位置执行，
     * 所以 {@code ~ ~ ~} 这类相对坐标解析的就是他脚下。
     */
    public void runAs(String name, String command) {
        String clean = command == null ? "" : command.trim();
        if (clean.startsWith("/")) {
            clean = clean.substring(1).trim();
        }
        if (clean.isEmpty()) {
            throw new PlayerActionException("请输入要执行的命令");
        }
        if (clean.length() > 256) {
            throw new PlayerActionException("命令过长");
        }

        String finalCommand = clean;
        run(name, player -> {
            // 外层发送者是控制台，所以拥有完整权限；as 把执行者换成该玩家，
            // at @s 把执行位置也设成他 —— 于是 @s、~ ~ ~ 全指向玩家，
            // 而权限用的是控制台的。玩家本身不会因此拿到任何权限，
            // 只是这一条命令被提权执行了。
            String wrapped = "execute as " + player.getName() + " at @s run " + finalCommand;
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), wrapped)) {
                throw new PlayerActionException("服务端无法识别这条命令：" + finalCommand);
            }
        });
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 解析玩家的贴图链接（Paper 的 PlayerProfile API）。
     *
     * <p>拿不到就返回 null —— 离线模式、没设置皮肤、或者不是 Paper 都会走到这里，
     * 前端收到 null 就退回面板自带的 Steve 头像。
     */
    private String resolveSkinUrl(Player player) {
        // 推送频率是 3 刻一次，每次都反射解析太浪费；
        // 皮肤在一次在线期间不会变，解析一次就够了
        String key = player.getUniqueId().toString();
        String cached = skinCache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String resolved = reflectSkinUrl(player);
        if (skinCache.size() >= MAX_SKIN_CACHE) {
            skinCache.clear();
        }
        // 空串表示「确认过没皮肤」，避免每次都重新反射一遍
        skinCache.put(key, resolved == null ? "" : resolved);
        return resolved;
    }

    private String reflectSkinUrl(Player player) {
        try {
            Object profile = player.getClass().getMethod("getPlayerProfile").invoke(player);
            Object textures = profile.getClass().getMethod("getTextures").invoke(profile);
            if (textures == null) {
                return null;
            }
            Object skin = textures.getClass().getMethod("getSkin").invoke(textures);
            if (skin == null) {
                return null;
            }
            // 官方给的是 http，升到 https —— 否则面板将来上了 HTTPS 会被当成混合内容拦掉
            String url = skin.toString();
            return url.startsWith("http://")
                    ? "https://" + url.substring("http://".length())
                    : url;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 在线玩家。<b>必须在主线程调用。</b>
     *
     * <p>封禁状态只看服务端的封禁名单 —— 那份数据在内存里，随便读。
     * 面板自己的封禁记录要读磁盘，这里不能碰；不过被封的人会被立刻踢下线，
     * 「在线且被封」本来就只是个转瞬即逝的状态，留给低频路径去算就够了。
     */
    private List<Entry> collectOnline() {
        Set<String> banned = lowerIndex(serverBannedNames());
        List<Entry> entries = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Entry entry = new Entry();
            entry.uuid = player.getUniqueId().toString();
            entry.name = player.getName();
            entry.online = true;
            entry.banned = isBanned(banned, entry.name);
            fillPosition(entry, player);
            fillBalance(entry, player);
            entries.add(entry);
        }

        sortEntries(entries);
        return entries;
    }

    /**
     * 按筛选条件收集玩家。<b>必须在主线程调用。</b>
     *
     * <p>数据来自三处，合并成一张表：
     * <ol>
     *     <li><b>名册</b> —— 离线玩家的主要来源；</li>
     *     <li><b>在线列表</b> —— 补上名册里还没有的（名册是异步载入的，
     *         刚装上面板时会有一段空窗期）；</li>
     *     <li><b>封禁名单</b> —— 补上服主手动封的、从没在本服上线过的玩家。</li>
     * </ol>
     *
     * @param fromStore 面板自己记的封禁名单，已在工作线程上读好
     */
    private List<Entry> collect(PlayerFilter filter, Set<String> fromStore) {
        // 封禁名单分两份：一份保留原始大小写（要显示出来），
        // 一份小写索引（用来比对）—— 玩家名是大小写敏感的，
        // 拿小写去显示，管理员会认不出那是谁
        Set<String> banned = serverBannedNames();
        banned.addAll(fromStore);
        Set<String> bannedLower = lowerIndex(banned);

        Map<String, Player> online = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            online.put(player.getUniqueId().toString(), player);
        }

        Map<String, Entry> merged = new LinkedHashMap<>();

        for (PlayerProfile profile : registry.all()) {
            if (profile.uuid == null) {
                continue;
            }

            Entry entry = new Entry();
            entry.uuid = profile.uuid;
            entry.name = profile.name;
            entry.banned = isBanned(bannedLower, profile.name);

            // 名册里有的人正好在线，就补上实时数据，并从 online 里摘掉，
            // 免得下面又被当成「名册里没有的在线玩家」加一遍
            Player player = online.remove(profile.uuid);
            if (player != null) {
                entry.online = true;
                entry.name = player.getName();
                fillPosition(entry, player);
                fillBalance(entry, player);
            } else {
                entry.lastSeen = profile.lastSeen;
                fillLastPosition(entry, profile);
                // 经济插件按 UUID 查，离线玩家照样能读出余额
                fillBalance(entry, offlinePlayerOf(profile));
            }

            merged.put(profile.uuid, entry);
        }

        // 在线但名册里没有的
        for (Player player : online.values()) {
            Entry entry = new Entry();
            entry.uuid = player.getUniqueId().toString();
            entry.name = player.getName();
            entry.online = true;
            entry.banned = isBanned(bannedLower, entry.name);
            fillPosition(entry, player);
            fillBalance(entry, player);
            merged.put(entry.uuid, entry);
        }

        // 封禁名单里但名册里没有的：服主手动封的、从没在本服上线过的玩家。
        // 这些拿不到 UUID，键用名字，不会和上面那些撞车
        Set<String> known = new HashSet<>();
        for (Entry entry : merged.values()) {
            if (entry.name != null) {
                known.add(entry.name.toLowerCase(Locale.ROOT));
            }
        }
        for (String name : banned) {
            if (known.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Entry entry = new Entry();
            entry.uuid = "";
            entry.name = name;
            entry.banned = true;
            merged.put("banned:" + name, entry);
        }

        List<Entry> entries = new ArrayList<>();
        for (Entry entry : merged.values()) {
            if (filter.matches(entry)) {
                entries.add(entry);
            }
        }

        sortEntries(entries);
        return entries;
    }

    /** 在线的排前面，各自再按名字 */
    private void sortEntries(List<Entry> entries) {
        entries.sort(Comparator
                .comparing((Entry entry) -> !entry.online)
                .thenComparing(entry -> entry.name == null ? "" : entry.name.toLowerCase(Locale.ROOT)));
    }

    private void fillPosition(Entry entry, Player player) {
        Location location = player.getLocation();
        entry.world = location.getWorld() == null ? "?" : location.getWorld().getName();
        entry.x = round(location.getX());
        entry.y = round(location.getY());
        entry.z = round(location.getZ());
        entry.skin = resolveSkinUrl(player);
        fillVitals(entry, player);
    }

    /**
     * 生存状态。全是主线程接口，跟着位置一起采集，不额外切线程。
     */
    private void fillVitals(Entry entry, Player player) {
        entry.health = round(player.getHealth());
        entry.maxHealth = round(maxHealthOf(player));
        // 伤害吸收是额外的一层血，和 health 分开算：吃金苹果时血是满的，
        // 但真正扛揍的是外面那圈金心，只看 health 会以为他快死了
        entry.absorption = round(player.getAbsorptionAmount());
        entry.food = player.getFoodLevel();
        entry.armor = armorOf(player);
        entry.level = player.getLevel();
        entry.expProgress = player.getExp();
        entry.totalExp = player.getTotalExperience();
    }

    /**
     * 生命上限。
     *
     * <p>不用 {@code player.getMaxHealth()}：那个已经过时了，而且属性接口
     * 拿到的才是被插件改过之后的真实上限（比如装了增加血上限的插件）。
     */
    private double maxHealthOf(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        return attribute == null ? 20D : attribute.getValue();
    }

    /**
     * 护甲值。
     *
     * <p>Bukkit 没有「当前护甲点数」这样的接口，只能读 ARMOR 属性 ——
     * 它已经把身上四件装备的护甲值加好了，比自己去翻装备算一遍可靠。
     */
    private int armorOf(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ARMOR);
        return attribute == null ? 0 : (int) Math.round(attribute.getValue());
    }

    /**
     * 给离线玩家填上最后下线时的位置。
     *
     * <p>没记过位置就整个留空，让前端显示「位置未知」——
     * 填个 (0, 0, 0) 上去，管理员会真以为他在世界原点。
     */
    private void fillLastPosition(Entry entry, PlayerProfile profile) {
        if (profile.world == null) {
            return;
        }
        entry.world = profile.world;
        entry.x = round(profile.x);
        entry.y = round(profile.y);
        entry.z = round(profile.z);
    }

    /**
     * 填余额。
     *
     * <p>没接经济系统就不填，{@code balanceText} 保持空串，前端据此不显示这一项 ——
     * 这和「余额是 0」是两回事，不能混为一谈。
     */
    private void fillBalance(Entry entry, OfflinePlayer player) {
        if (player == null || !economy.isAvailable()) {
            return;
        }

        double balance = economy.balance(player);
        entry.balance = round(balance);
        entry.balanceText = economy.format(balance);
    }

    /**
     * 名册里的玩家转成 OfflinePlayer。
     *
     * <p>优先按 UUID 找：那是缓存命中，不必去查名字。
     * UUID 缺失时（比如封禁名单里那些没有名册记录的玩家）才退回按名字。
     */
    private OfflinePlayer offlinePlayerOf(PlayerProfile profile) {
        if (profile.uuid != null && !profile.uuid.isBlank()) {
            try {
                return Bukkit.getOfflinePlayer(UUID.fromString(profile.uuid));
            } catch (IllegalArgumentException ignored) {
                // uuid 字段被手改坏了，退回按名字找
            }
        }
        return profile.name == null ? null : Bukkit.getOfflinePlayer(profile.name);
    }

    /** 忽略大小写地判断某个名字是否在名单里。传进来的必须是小写索引 */
    private boolean isBanned(Set<String> bannedLower, String name) {
        return name != null && bannedLower.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 把名字集合转成小写索引，用于忽略大小写的比对 */
    private Set<String> lowerIndex(Set<String> names) {
        Set<String> lower = new HashSet<>();
        for (String name : names) {
            if (name != null) {
                lower.add(name.toLowerCase(Locale.ROOT));
            }
        }
        return lower;
    }

    /**
     * 服务端封禁名单里的玩家名（统一小写）。
     *
     * <p>必须在主线程调用 —— 服务端的封禁名单内部就是个普通 HashMap。
     * 名单通常只有几十条，每次采集重建一份 Set 的开销可以忽略。
     *
     * <p>只覆盖服务端自己的 {@code banned-players.json}：LiteBans 那类插件
     * 用各自的存储，它们的封禁要靠 {@link #bannedFromStore()} 补。
     */
    private Set<String> serverBannedNames() {
        Set<String> names = new HashSet<>();
        try {
            // 先赋给显式类型的变量：Bukkit.getBanList 的泛型参数不在入参里，
            // 直接链式调用时 T 会被推断成 Object，拿不到 Set<BanEntry<String>>
            BanList<String> banList = Bukkit.getBanList(BanList.Type.NAME);
            for (BanEntry<String> entry : banList.getEntries()) {
                String target = entry.getTarget();
                if (target != null && !target.isBlank()) {
                    // 保留原始大小写，显示要用
                    names.add(target);
                }
            }
        } catch (Throwable ignored) {
            // 拿不到就当成没有封禁，不影响列表本身
        }
        return names;
    }

    /**
     * 面板自己记的、还没到期的封禁名单。任意线程可调，会读磁盘。
     *
     * <p>过期的临时封禁不算 —— 它们只是还没被到期任务扫掉而已，
     * 在上层看来那个人已经不该是「被封禁」状态了。
     */
    private Set<String> bannedFromStore() {
        Set<String> names = new HashSet<>();
        if (banStore == null) {
            return names;
        }

        try {
            long now = System.currentTimeMillis();
            for (BanRecord record : banStore.all()) {
                if (record.player != null && !record.isExpired(now)) {
                    names.add(record.player);
                }
            }
        } catch (Exception e) {
            MessagesManager.logRaw("§c读取封禁记录失败，封禁状态可能显示不全: " + e.getMessage());
        }
        return names;
    }

    /**
     * 取玩家实体并在主线程执行操作。玩家中途下线会抛出可读的错误。
     *
     * <p>包内可见：{@link BanService} 要借它把封禁动作切到主线程，
     * 而那段「异常与超时要分开报」的处理没必要重复写一遍。
     */
    void run(String name, PlayerAction action) {
        // 这里刻意不用 awaitSync：它在异常与超时之间分不清，
        // 会把「玩家不在线」「服务端不认这条命令」这类真实原因
        // 一律吞成「操作超时」。直接拿 Future，把 cause 解出来。
        CompletableFuture<Void> future = TaskUtil.supplySync(() -> {
            Player player = Bukkit.getPlayerExact(name);
            if (player == null) {
                throw new PlayerActionException("玩家 " + name + " 不在线");
            }
            action.accept(player);
            return null;
        });

        try {
            future.get(OPERATION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof PlayerActionException actionError) {
                throw actionError;
            }
            throw new PlayerActionException(cause == null || cause.getMessage() == null
                    ? "操作失败"
                    : cause.getMessage());
        } catch (Exception e) {
            throw new PlayerActionException("操作超时，服务端可能正在卡顿");
        }
    }

    /**
     * 解析「x y z」，支持 {@code ~} 相对坐标（与游戏内写法一致）。
     *
     * <p>{@code ~} 表示「不加偏移」，{@code ~10} 表示「当前位置 +10」，
     * 三种坐标可以混着写，例如 {@code 100 ~ ~-5}。
     *
     * @param origin 相对坐标的基准位置，必须是玩家实时的位置
     * @return 解析出的绝对坐标；不是坐标写法（比如填的是玩家名）返回 null
     */
    private double[] parseCoordinates(String text, Location origin) {
        String[] parts = text.split("\\s+");
        if (parts.length != 3) {
            return null;
        }

        double[] values = new double[3];
        for (int i = 0; i < 3; i++) {
            String part = parts[i];
            double base = switch (i) {
                case 0 -> origin.getX();
                case 1 -> origin.getY();
                default -> origin.getZ();
            };

            try {
                if (part.startsWith("~")) {
                    String offset = part.substring(1).trim();
                    values[i] = offset.isEmpty() ? base : base + Double.parseDouble(offset);
                } else {
                    values[i] = Double.parseDouble(part);
                }
            } catch (NumberFormatException e) {
                // 不是坐标（多半填的是玩家名），交给调用方走玩家名那条路
                return null;
            }
        }
        return values;
    }

    private double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    /**
     * 在主线程上针对某个玩家做的动作。
     */
    interface PlayerAction {
        void accept(Player player);
    }

    /**
     * 操作失败。带上可以直接展示给用户的原因。
     */
    public static final class PlayerActionException extends RuntimeException {

        public PlayerActionException(String message) {
            super(message);
        }
    }

    /**
     * 一条在线玩家记录。
     */
    public static final class Entry {

        /** 玩家 UUID。服主手动封的、从没在本服上线过的玩家拿不到，是空串 */
        public String uuid;

        public String name;

        /** 此刻是否在线 */
        public boolean online;

        /**
         * 是否处于封禁状态。
         *
         * <p>在线列表那条高频路径上只看服务端的封禁名单，面板自己记的封禁记录
         * 要读磁盘、带不进来 —— 所以在线玩家的这个字段可能偏保守。
         * 想准确判断就用「已封禁」筛选，那条是低频路径，两边都查了。
         */
        public boolean banned;

        /** 最后在线时间（epoch 毫秒）。在线玩家填 0，前端不显示 */
        public long lastSeen;

        /** 以下各项只对在线玩家有意义，离线玩家身上是默认值，前端也不显示 */
        public String world;

        public double x;

        public double y;

        public double z;

        /** 当前生命值 */
        public double health;

        /** 生命上限。走属性接口取，插件改过也拿得到真实值 */
        public double maxHealth;

        /** 伤害吸收量（金心）。0 表示没有，不计入上面的 health */
        public double absorption;

        /** 饥饿度，0-20 */
        public int food;

        /** 护甲值，0-20 */
        public int armor;

        /** 经验等级 */
        public int level;

        /** 当前等级内的经验进度，0.0-1.0 */
        public float expProgress;

        /** 累计总经验 */
        public int totalExp;

        /** 经济余额。没接经济系统时是 0，看 balanceText 才准 */
        public double balance;

        /** 格式化好的余额文本（带货币符号或单位）。空串表示经济系统不可用 */
        public String balanceText = "";

        /**
         * 皮肤贴图链接。为 null 表示没有皮肤，前端回退到面板自带的默认头像。
         */
        public String skin;
    }
}
