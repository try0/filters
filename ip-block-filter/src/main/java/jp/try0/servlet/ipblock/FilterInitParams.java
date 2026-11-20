package jp.try0.servlet.ipblock;

import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * web.xml の init-param から {@link IpBlockList} を組み立てる。
 *
 * <table border="1">
 *   <caption>init-param 一覧</caption>
 *   <tr><th>名前</th><th>必須</th><th>内容</th></tr>
 *   <tr><td>mode</td><td>○</td><td>db / xml / json をカンマ区切りで（例: {@code db,json}）。複数指定すると和集合</td></tr>
 *   <tr><td>reloadIntervalSeconds</td><td></td><td>リロード間隔（秒）。既定 30、0 で定期リロードなし</td></tr>
 *   <tr><td>failOnStartupError</td><td></td><td>true なら初回ロード失敗でフィルター初期化を失敗させる。既定 false</td></tr>
 *   <tr><td>denyStatus</td><td></td><td>拒否時の HTTP ステータス（400～599）。既定 403</td></tr>
 *   <tr><td>db.jndiName</td><td>db 時</td><td>DataSource の JNDI 名（{@code jdbc/appDb} または {@code java:comp/env/jdbc/appDb}）</td></tr>
 *   <tr><td>db.query</td><td>db 時</td><td>1 列目に IP / CIDR を返す SELECT 文</td></tr>
 *   <tr><td>db.queryTimeoutSeconds</td><td></td><td>クエリタイムアウト（秒）。既定 10</td></tr>
 *   <tr><td>xml.location</td><td>xml 時</td><td>XML ファイルの場所（下記）</td></tr>
 *   <tr><td>json.location</td><td>json 時</td><td>JSON ファイルの場所（下記）</td></tr>
 *   <tr><td>forwardedFor.trustedProxies</td><td></td><td>信頼するプロキシの IP / CIDR（カンマ・空白・改行区切り）。
 *       指定すると、そのプロキシから来たリクエストでは X-Forwarded-For からクライアント IP を求める
 *       （{@link ForwardedForResolver}）。未指定なら getRemoteAddr() だけで判定する</td></tr>
 *   <tr><td>forwardedFor.header</td><td></td><td>読み取るヘッダー名。既定 X-Forwarded-For</td></tr>
 * </table>
 *
 * <p>location の書き方:
 * <ul>
 *   <li>{@code /WEB-INF/ip-block-list.xml} … Web アプリ内のパス（"/" で始める）</li>
 *   <li>{@code classpath:ip-block-list.xml} … クラスパス</li>
 *   <li>{@code file:/etc/myapp/ip-block-list.xml}、{@code file:C:/conf/ip-block-list.json} … ファイルシステム</li>
 * </ul>
 */
final class FilterInitParams {

    static final String MODE = "mode";
    static final String RELOAD_INTERVAL_SECONDS = "reloadIntervalSeconds";
    static final String FAIL_ON_STARTUP_ERROR = "failOnStartupError";
    static final String DENY_STATUS = "denyStatus";
    static final String DB_JNDI_NAME = "db.jndiName";
    static final String DB_QUERY = "db.query";
    static final String DB_QUERY_TIMEOUT_SECONDS = "db.queryTimeoutSeconds";
    static final String XML_LOCATION = "xml.location";
    static final String JSON_LOCATION = "json.location";
    static final String FORWARDED_FOR_TRUSTED_PROXIES = "forwardedFor.trustedProxies";
    static final String FORWARDED_FOR_HEADER = "forwardedFor.header";

    private static final String CLASSPATH_PREFIX = "classpath:";
    private static final String FILE_PREFIX = "file:";

    private final FilterConfig config;

    FilterInitParams(FilterConfig config) {
        this.config = config;
    }

    IpBlockList buildBlockList() throws ServletException {
        IpBlockList.Builder builder = IpBlockList.builder();
        for (String mode : required(MODE).split(",")) {
            switch (mode.trim().toLowerCase(Locale.ROOT)) {
                case "db":
                    builder.addSource(dbSource());
                    break;
                case "xml": {
                    String location = required(XML_LOCATION);
                    builder.addSource(new XmlBlockListSource("xml:" + location, opener(XML_LOCATION, location)));
                    break;
                }
                case "json": {
                    String location = required(JSON_LOCATION);
                    builder.addSource(new JsonBlockListSource("json:" + location, opener(JSON_LOCATION, location)));
                    break;
                }
                case "":
                    break;
                default:
                    throw new ServletException("init-param \"" + MODE + "\" に未対応の値があります: \""
                            + mode.trim() + "\"（db / xml / json をカンマ区切りで指定）");
            }
        }
        builder.reloadInterval(Duration.ofSeconds(intParam(RELOAD_INTERVAL_SECONDS,
                (int) IpBlockList.DEFAULT_RELOAD_INTERVAL.getSeconds(), 0, Integer.MAX_VALUE)));
        builder.failOnStartupError(booleanParam(FAIL_ON_STARTUP_ERROR, false));
        try {
            return builder.build();
        } catch (IllegalStateException e) {
            throw new ServletException("init-param \"" + MODE + "\": " + e.getMessage(), e);
        }
    }

    int denyStatus() throws ServletException {
        return intParam(DENY_STATUS, 403, 400, 599);
    }

    /** X-Forwarded-For の設定。forwardedFor.trustedProxies が未指定なら null（ヘッダーを見ない）。 */
    ForwardedForResolver forwardedFor() throws ServletException {
        String trusted = optional(FORWARDED_FOR_TRUSTED_PROXIES);
        String header = optional(FORWARDED_FOR_HEADER);
        if (trusted == null) {
            if (header != null) {
                throw new ServletException("init-param \"" + FORWARDED_FOR_HEADER + "\" を使う場合は \""
                        + FORWARDED_FOR_TRUSTED_PROXIES + "\" も指定してください");
            }
            return null;
        }
        ForwardedForResolver.Builder builder = ForwardedForResolver.builder();
        try {
            for (String entry : trusted.split("[,\\s]+")) { // カンマ・空白・改行のどれで区切ってもよい
                if (!entry.isEmpty()) {
                    builder.trustedProxies(entry);
                }
            }
            if (header != null) {
                builder.headerName(header);
            }
            return builder.build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ServletException("init-param \"" + FORWARDED_FOR_TRUSTED_PROXIES + "\": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------

    private BlockListSource dbSource() throws ServletException {
        String jndiName = required(DB_JNDI_NAME);
        String query = required(DB_QUERY);
        int timeout = intParam(DB_QUERY_TIMEOUT_SECONDS, JdbcBlockListSource.DEFAULT_QUERY_TIMEOUT_SECONDS, 0, 3600);

        String lookupName = jndiName.startsWith("java:") ? jndiName : "java:comp/env/" + jndiName;
        Object found;
        try {
            found = new InitialContext().lookup(lookupName);
        } catch (NamingException e) {
            throw new ServletException("DataSource を JNDI から取得できません: " + lookupName, e);
        }
        if (!(found instanceof DataSource)) {
            throw new ServletException(lookupName + " は DataSource ではありません: " + found);
        }
        return new JdbcBlockListSource((DataSource) found, query, timeout);
    }

    private ResourceOpener opener(String paramName, String location) throws ServletException {
        if (location.startsWith(CLASSPATH_PREFIX)) {
            return ResourceOpener.classpath(location.substring(CLASSPATH_PREFIX.length()));
        }
        if (location.startsWith(FILE_PREFIX)) {
            return ResourceOpener.file(toPath(paramName, location));
        }
        if (location.startsWith("/")) {
            ServletContext context = config.getServletContext();
            return () -> {
                InputStream in = context.getResourceAsStream(location);
                if (in == null) {
                    throw new FileNotFoundException("Web アプリ内に見つかりません: " + location);
                }
                return in;
            };
        }
        throw new ServletException("init-param \"" + paramName + "\" は classpath: / file: / \"/\"（Web アプリ内のパス）"
                + "のいずれかで始めてください: " + location);
    }

    private static Path toPath(String paramName, String location) throws ServletException {
        try {
            return location.startsWith("file://")
                    ? Paths.get(URI.create(location))
                    : Paths.get(location.substring(FILE_PREFIX.length()));
        } catch (RuntimeException e) {
            throw new ServletException("init-param \"" + paramName + "\" のファイルパスが不正です: " + location, e);
        }
    }

    private String optional(String name) {
        String value = config.getInitParameter(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String required(String name) throws ServletException {
        String value = optional(name);
        if (value == null) {
            throw new ServletException("init-param \"" + name + "\" を指定してください");
        }
        return value;
    }

    private int intParam(String name, int defaultValue, int min, int max) throws ServletException {
        String value = optional(name);
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 下でまとめてエラーにする
        }
        throw new ServletException("init-param \"" + name + "\" は " + min + "～" + max + " の整数で指定してください: " + value);
    }

    private boolean booleanParam(String name, boolean defaultValue) throws ServletException {
        String value = optional(name);
        if (value == null) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new ServletException("init-param \"" + name + "\" は true / false で指定してください: " + value);
    }
}
