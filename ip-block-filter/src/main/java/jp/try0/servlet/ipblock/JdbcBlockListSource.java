package jp.try0.servlet.ipblock;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * DB からブロック対象を読み込むソース。クエリ結果の 1 列目を単体 IP / CIDR として使う。
 *
 * <pre>{@code
 * new JdbcBlockListSource(dataSource, "SELECT ip FROM blocked_ip WHERE enabled = TRUE")
 * }</pre>
 *
 * <p>DB が落ちている間は例外になり、{@link IpBlockList} は前回の内容を使い続ける。
 * リロード用スレッドが止まらないよう、クエリにはタイムアウト（既定 10 秒）を設定する。
 */
public final class JdbcBlockListSource implements BlockListSource {

    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 10;

    private final DataSource dataSource;
    private final String query;
    private final int queryTimeoutSeconds;

    public JdbcBlockListSource(DataSource dataSource, String query) {
        this(dataSource, query, DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    /** @param queryTimeoutSeconds 0 ならタイムアウトなし */
    public JdbcBlockListSource(DataSource dataSource, String query, int queryTimeoutSeconds) {
        if (queryTimeoutSeconds < 0) {
            throw new IllegalArgumentException("queryTimeoutSeconds は 0 以上: " + queryTimeoutSeconds);
        }
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.query = Objects.requireNonNull(query, "query");
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @Override
    public List<String> load() throws SQLException {
        List<String> entries = new ArrayList<>();
        try (Connection con = dataSource.getConnection();
             PreparedStatement ps = con.prepareStatement(query)) {
            ps.setQueryTimeout(queryTimeoutSeconds);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String value = rs.getString(1);
                    if (value != null && !value.isBlank()) {
                        entries.add(value.trim());
                    }
                }
            }
        }
        return entries;
    }

    @Override
    public String name() {
        return "db";
    }
}
