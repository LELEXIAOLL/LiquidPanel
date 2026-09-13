package dev.liquidpanel.panels.database;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 把封禁记录存进一个 SQLite 库文件。
 *
 * <p>驱动（{@code org.xerial:sqlite-jdbc}）随插件一起打包，服务器上不需要额外装任何东西。
 * 它自带的本地库会在首次连接时释放到系统临时目录，这一步是驱动自己做的。
 */
public final class SqliteBanStore implements BanStore {

    /** 存储类型名，对应 config.yml 里 panel.database.type 的可选值 */
    public static final String TYPE = "sqlite";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS bans (
                id         TEXT PRIMARY KEY,
                player     TEXT NOT NULL,
                reason     TEXT,
                operator   TEXT,
                method     TEXT,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """;

    /** 临时封禁的到期扫描按 expires_at 过滤，给它建个索引 */
    private static final String CREATE_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_bans_expires ON bans (expires_at)";

    private static final String SELECT_ALL =
            "SELECT id, player, reason, operator, method, created_at, expires_at FROM bans";

    private static final String UPSERT = """
            INSERT INTO bans (id, player, reason, operator, method, created_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                player     = excluded.player,
                reason     = excluded.reason,
                operator   = excluded.operator,
                method     = excluded.method,
                created_at = excluded.created_at,
                expires_at = excluded.expires_at
            """;

    private static final String DELETE = "DELETE FROM bans WHERE id = ?";

    private final File file;

    public SqliteBanStore(File file) {
        this.file = file;
    }

    @Override
    public void open() throws SQLException {
        try (Connection connection = SqliteSupport.connect(file);
             Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
        }
    }

    @Override
    public void put(BanRecord record) throws SQLException {
        try (Connection connection = SqliteSupport.connect(file);
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            statement.setString(1, record.id);
            statement.setString(2, record.player);
            statement.setString(3, record.reason);
            statement.setString(4, record.operator);
            statement.setString(5, record.method);
            statement.setLong(6, record.createdAt);
            statement.setLong(7, record.expiresAt);
            statement.executeUpdate();
        }
    }

    @Override
    public void remove(String id) throws SQLException {
        if (id == null) {
            return;
        }
        try (Connection connection = SqliteSupport.connect(file);
             PreparedStatement statement = connection.prepareStatement(DELETE)) {
            statement.setString(1, id);
            statement.executeUpdate();
        }
    }

    @Override
    public List<BanRecord> all() throws SQLException {
        List<BanRecord> records = new ArrayList<>();
        try (Connection connection = SqliteSupport.connect(file);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(SELECT_ALL)) {

            while (rows.next()) {
                BanRecord record = new BanRecord();
                record.id = rows.getString("id");
                record.player = rows.getString("player");
                record.reason = SqliteSupport.orEmpty(rows.getString("reason"));
                record.operator = rows.getString("operator");
                record.method = rows.getString("method");
                record.createdAt = rows.getLong("created_at");
                record.expiresAt = rows.getLong("expires_at");
                records.add(record);
            }
        }
        return records;
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
