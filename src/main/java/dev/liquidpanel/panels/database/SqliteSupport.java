package dev.liquidpanel.panels.database;

import org.sqlite.JDBC;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 两个 SQLite store 共用的连接逻辑。
 *
 * <p>包内可见：这是实现细节，不该出现在 {@code panels} 之外的视野里。
 */
final class SqliteSupport {

    private SqliteSupport() {
    }

    /**
     * 建一条连接。
     *
     * <p><b>刻意绕开 {@code DriverManager}</b>：它是按<b>调用方的类加载器</b>去筛可用驱动的，
     * 而插件类加载器里的驱动它看不见，会直接抛 "No suitable driver"。
     * 自己实例化驱动再调 {@code connect} 就没这个问题 —— 驱动已经随插件打进 jar，
     * 编译期直接引用即可，不用反射。
     *
     * <p>不做连接池、也不长期持有连接：这两个 store 的读写都很低频，
     * 开一次连接是毫秒级的事，换来的是库文件被挪走或删掉之后下一次操作能自愈，
     * 省掉一整套连接失效的判断。
     */
    static Connection connect(File file) throws SQLException {
        // 路径统一成正斜杠：JDBC URL 里的反斜杠在部分平台上会被当成转义，
        // 而 Windows 的文件 API 本来也接受正斜杠
        String url = "jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/');
        Connection connection = new JDBC().connect(url, new Properties());
        if (connection == null) {
            throw new SQLException("SQLite 驱动拒绝了连接串: " + url);
        }

        // 库文件被别的进程占用时先等一会儿，而不是立刻抛 SQLITE_BUSY
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    /** 数据库里的列可以是 NULL，读出来统一成空串，免得调用方到处判空 */
    static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
