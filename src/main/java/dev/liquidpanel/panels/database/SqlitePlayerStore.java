package dev.liquidpanel.panels.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把玩家名册存进一个 SQLite 库文件。
 *
 * <p>与 {@link SqliteBanStore} 共用同一个库文件里的不同表 ——
 * 开一次连接就能同时服务两边。
 */
public final class SqlitePlayerStore implements PlayerStore {

    public static final String TYPE = "sqlite";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS players (
                uuid       TEXT PRIMARY KEY,
                name       TEXT NOT NULL,
                first_seen INTEGER NOT NULL,
                last_seen  INTEGER NOT NULL,
                world      TEXT,
                x          REAL,
                y          REAL,
                z          REAL
            )
            """;

    /**
     * 位置那几列是后加的，老库需要补上。
     *
     * <p>SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，列已存在时直接报错，
     * 所以只能逐条执行、把错误吞掉 —— 这里不是在掩盖问题，
     * 「列已经在了」正是我们想要的结果。
     */
    private static final String[] LOCATION_COLUMNS = {
            "ALTER TABLE players ADD COLUMN world TEXT",
            "ALTER TABLE players ADD COLUMN x REAL",
            "ALTER TABLE players ADD COLUMN y REAL",
            "ALTER TABLE players ADD COLUMN z REAL"
    };

    private static final String SELECT_ALL =
            "SELECT uuid, name, first_seen, last_seen, world, x, y, z FROM players";

    /**
     * 刻意不更新 {@code first_seen}：那是「第一次见」的时间，
     * 每次上线都刷一遍就没有意义了。
     */
    private static final String UPSERT = """
            INSERT INTO players (uuid, name, first_seen, last_seen, world, x, y, z)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                name      = excluded.name,
                last_seen = excluded.last_seen,
                world     = excluded.world,
                x         = excluded.x,
                y         = excluded.y,
                z         = excluded.z
            """;

    private final File file;

    public SqlitePlayerStore(File file) {
        this.file = file;
    }

    @Override
    public void open() throws SQLException {
        try (Connection connection = SqliteSupport.connect(file);
             Statement statement = connection.createStatement()) {

            statement.execute(CREATE_TABLE);
            for (String migration : LOCATION_COLUMNS) {
                try {
                    statement.execute(migration);
                } catch (SQLException ignored) {
                    // 列已经有了
                }
            }
        }
    }

    @Override
    public Map<String, PlayerProfile> load() throws SQLException {
        Map<String, PlayerProfile> profiles = new LinkedHashMap<>();
        try (Connection connection = SqliteSupport.connect(file);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_ALL)) {

            while (rows.next()) {
                PlayerProfile profile = new PlayerProfile();
                profile.uuid = rows.getString("uuid");
                profile.name = SqliteSupport.orEmpty(rows.getString("name"));
                profile.firstSeen = rows.getLong("first_seen");
                profile.lastSeen = rows.getLong("last_seen");

                // world 为 NULL 表示没记过位置，保持 null
                profile.world = rows.getString("world");
                profile.x = rows.getDouble("x");
                profile.y = rows.getDouble("y");
                profile.z = rows.getDouble("z");

                profiles.put(profile.uuid, profile);
            }
        }
        return profiles;
    }

    /**
     * 整批写进一个事务。
     *
     * <p>几千条逐条提交会慢到离谱（每条都要 fsync），
     * 打包成一个事务则只有最后一次落盘。
     */
    @Override
    public void saveAll(Collection<PlayerProfile> profiles) throws SQLException {
        if (profiles.isEmpty()) {
            return;
        }

        try (Connection connection = SqliteSupport.connect(file)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
                    for (PlayerProfile profile : profiles) {
                        statement.setString(1, profile.uuid);
                        statement.setString(2, profile.name);
                        statement.setLong(3, profile.firstSeen);
                        statement.setLong(4, profile.lastSeen);
                        statement.setString(5, profile.world);
                        statement.setDouble(6, profile.x);
                        statement.setDouble(7, profile.y);
                        statement.setDouble(8, profile.z);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void close() {
        // 没有长期持有的连接，不需要收尾
    }
}
