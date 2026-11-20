package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.Filter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IpBlockFilterTest {

    @TempDir
    Path tmp;

    // ---- Java API ----

    @Test
    void javaApiBlocksListedAddressesWith403() throws Exception {
        InMemoryBlockListSource manual = new InMemoryBlockListSource().add("192.0.2.10", "198.51.100.0/24");
        IpBlockFilter filter = new IpBlockFilter(
                IpBlockList.builder().addSource(manual).reloadInterval(Duration.ZERO).build());
        filter.init(ServletFakes.filterConfig(Map.of("mode", "ignored-in-java-api-mode")));
        try {
            assertBlocked(filter, "192.0.2.10", 403);
            assertBlocked(filter, "198.51.100.77", 403);
            assertPassed(filter, "192.0.2.11");

            // 実行中の追加 → reloadNow ですぐ反映
            manual.add("192.0.2.11");
            filter.getBlockList().reloadNow();
            assertBlocked(filter, "192.0.2.11", 403);
        } finally {
            filter.destroy();
        }
        assertFalse(filter.getBlockList().isRunning(), "destroy で停止する");
    }

    @Test
    void javaApiCustomDenyStatus() throws Exception {
        IpBlockFilter filter = IpBlockFilter.builder(IpBlockList.builder()
                .addSource(new InMemoryBlockListSource().add("192.0.2.10"))
                .reloadInterval(Duration.ZERO).build())
                .denyStatus(404)
                .build();
        filter.init(ServletFakes.filterConfig(Map.of()));
        try {
            assertBlocked(filter, "192.0.2.10", 404);
        } finally {
            filter.destroy();
        }
    }

    @Test
    void javaApiBuilderValidatesDenyStatus() {
        IpBlockList blockList = IpBlockList.builder()
                .addSource(new InMemoryBlockListSource()).reloadInterval(Duration.ZERO).build();
        assertThrows(IllegalArgumentException.class, () -> IpBlockFilter.builder(blockList).denyStatus(200));
        assertThrows(IllegalArgumentException.class, () -> IpBlockFilter.builder(blockList).denyStatus(600));
    }

    // ---- X-Forwarded-For ----

    @Test
    void forwardedForUsesClientIpOnlyBehindTrustedProxy() throws Exception {
        IpBlockFilter filter = IpBlockFilter.builder(IpBlockList.builder()
                        .addSource(new InMemoryBlockListSource().add("192.0.2.10"))
                        .reloadInterval(Duration.ZERO).build())
                .forwardedFor(ForwardedForResolver.builder().trustedProxies("10.0.0.0/8").build())
                .build();
        filter.init(ServletFakes.filterConfig(Map.of()));
        try {
            // プロキシ経由: ヘッダーのクライアント IP で判定する
            assertBlocked(filter, xff("10.0.0.1", "192.0.2.10"), 403);
            assertBlocked(filter, xff("10.0.0.1", "192.0.2.10, 10.0.0.2"), 403);
            assertPassed(filter, xff("10.0.0.1", "192.0.2.11"));
            // ブロック対象のクライアントが左側に偽の値を書いても、プロキシが追記した本当の IP で判定される
            assertBlocked(filter, xff("10.0.0.1", "198.51.100.1, 192.0.2.10"), 403);
            // ヘッダー名の大文字・小文字は区別しない
            assertBlocked(filter, ServletFakes.request("10.0.0.1",
                    Map.of("x-forwarded-for", List.of("192.0.2.10"))), 403);

            // プロキシを通さない直接アクセス: ヘッダーは見ない
            assertBlocked(filter, xff("192.0.2.10", "198.51.100.1"), 403);
            assertPassed(filter, xff("198.51.100.1", "192.0.2.10"));
            assertPassed(filter, ServletFakes.request("10.0.0.1"));
        } finally {
            filter.destroy();
        }
    }

    @Test
    void forwardedForIsIgnoredUnlessConfigured() throws Exception {
        IpBlockFilter filter = new IpBlockFilter(IpBlockList.builder()
                .addSource(new InMemoryBlockListSource().add("192.0.2.10"))
                .reloadInterval(Duration.ZERO).build());
        filter.init(ServletFakes.filterConfig(Map.of()));
        try {
            assertPassed(filter, xff("10.0.0.1", "192.0.2.10"));
            assertNull(filter.getForwardedFor());
        } finally {
            filter.destroy();
        }
    }

    @Test
    void initParamsForwardedForWithCustomHeader() throws Exception {
        IpBlockFilter filter = new IpBlockFilter();
        filter.init(ServletFakes.filterConfig(Map.of(
                "mode", "json",
                "json.location", "classpath:ip-block-list-test.json", // 203.0.113.0/24
                "reloadIntervalSeconds", "0",
                "forwardedFor.trustedProxies", "10.0.0.0/8,\n      192.168.0.0/16  fd00::/8",
                "forwardedFor.header", "X-Real-Client-IP")));
        try {
            assertEquals(List.of("10.0.0.0/8", "192.168.0.0/16", "fd00::/8"),
                    filter.getForwardedFor().trustedProxies());
            assertBlocked(filter, ServletFakes.request("192.168.1.1",
                    Map.of("X-Real-Client-IP", List.of("203.0.113.50"))), 403);
            // 指定したヘッダー以外は見ない
            assertPassed(filter, xff("192.168.1.1", "203.0.113.50"));
        } finally {
            filter.destroy();
        }
    }

    @Test
    void initParamsForwardedForDefaultsToXForwardedFor() throws Exception {
        IpBlockFilter filter = new IpBlockFilter();
        filter.init(ServletFakes.filterConfig(Map.of(
                "mode", "json",
                "json.location", "classpath:ip-block-list-test.json",
                "reloadIntervalSeconds", "0",
                "forwardedFor.trustedProxies", "127.0.0.1, ::1")));
        try {
            assertBlocked(filter, xff("0:0:0:0:0:0:0:1", "203.0.113.50"), 403);
        } finally {
            filter.destroy();
        }
    }

    // ---- web.xml（init-param） ----

    @Test
    void initParamsLoadXmlAndJsonFilesAndPickUpEdits() throws Exception {
        Path xml = Files.writeString(tmp.resolve("list.xml"),
                "<ip-block-list><ip>192.0.2.10</ip></ip-block-list>");
        Path json = Files.writeString(tmp.resolve("list.json"), "{\"ips\": [\"2001:db8::/32\"]}");

        IpBlockFilter filter = new IpBlockFilter();
        filter.init(ServletFakes.filterConfig(Map.of(
                "mode", " XML , json ",
                "xml.location", xml.toUri().toString(), // file:///C:/... 形式
                "json.location", "file:" + json,        // file:C:\... 形式
                "reloadIntervalSeconds", "0",
                "denyStatus", "404")));
        try {
            assertBlocked(filter, "192.0.2.10", 404);
            assertBlocked(filter, "2001:db8:1::1", 404);
            assertPassed(filter, "192.0.2.11");

            Files.writeString(xml, "<ip-block-list><ip>192.0.2.11</ip></ip-block-list>");
            filter.getBlockList().reloadNow();
            assertBlocked(filter, "192.0.2.11", 404);
            assertPassed(filter, "192.0.2.10");
        } finally {
            filter.destroy();
        }
    }

    @Test
    void initParamsClasspathLocation() throws Exception {
        IpBlockFilter filter = new IpBlockFilter();
        filter.init(ServletFakes.filterConfig(Map.of(
                "mode", "json",
                "json.location", "classpath:ip-block-list-test.json",
                "reloadIntervalSeconds", "0")));
        try {
            assertBlocked(filter, "203.0.113.50", 403);
        } finally {
            filter.destroy();
        }
    }

    @Test
    void missingFileStartsEmptyByDefaultButFailsWhenStrict() throws Exception {
        String missing = tmp.resolve("none.xml").toUri().toString();

        IpBlockFilter lenient = new IpBlockFilter();
        lenient.init(ServletFakes.filterConfig(Map.of(
                "mode", "xml", "xml.location", missing, "reloadIntervalSeconds", "0")));
        try {
            assertPassed(lenient, "192.0.2.10");
        } finally {
            lenient.destroy();
        }

        assertThrows(ServletException.class, () -> new IpBlockFilter().init(ServletFakes.filterConfig(Map.of(
                "mode", "xml", "xml.location", missing, "reloadIntervalSeconds", "0",
                "failOnStartupError", "true"))));
    }

    @Test
    void invalidInitParamsAreReportedAsServletException() {
        assertInitFails(Map.of());
        assertInitFails(Map.of("mode", "yaml"));
        assertInitFails(Map.of("mode", ","));
        assertInitFails(Map.of("mode", "xml"));
        assertInitFails(Map.of("mode", "xml", "xml.location", "relative/path.xml"));
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json", "reloadIntervalSeconds", "-1"));
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json", "denyStatus", "200"));
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json", "failOnStartupError", "yes"));
        assertInitFails(Map.of("mode", "db", "db.query", "SELECT ip FROM blocked_ip"));
        // JNDI が使えない環境では DataSource 取得失敗
        assertInitFails(Map.of("mode", "db", "db.jndiName", "jdbc/none", "db.query", "SELECT ip FROM blocked_ip"));
        // X-Forwarded-For
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json",
                "forwardedFor.trustedProxies", "proxy.local"));
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json",
                "forwardedFor.trustedProxies", "10.0.0.0/8, 10.0.0.0/33"));
        assertInitFails(Map.of("mode", "json", "json.location", "classpath:x.json",
                "forwardedFor.header", "X-Real-Client-IP")); // trustedProxies なしでヘッダーだけ
    }

    // ------------------------------------------------------------------

    private static void assertInitFails(Map<String, String> params) {
        assertThrows(ServletException.class, () -> new IpBlockFilter().init(ServletFakes.filterConfig(params)),
                () -> "init-param " + params + " は ServletException になるべき");
    }

    /** X-Forwarded-For 付きのリクエスト。 */
    private static HttpServletRequest xff(String remoteAddr, String forwardedFor) {
        return ServletFakes.request(remoteAddr, Map.of("X-Forwarded-For", List.of(forwardedFor)));
    }

    private static void assertBlocked(Filter filter, String ip, int status) throws Exception {
        assertBlocked(filter, ServletFakes.request(ip), status);
    }

    private static void assertBlocked(Filter filter, HttpServletRequest request, int status) throws Exception {
        ServletFakes.RecordingResponse response = new ServletFakes.RecordingResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response.proxy, (req, res) -> passed.set(true));
        assertFalse(passed.get(), () -> describe(request) + " は遮断されるべき");
        assertEquals(Integer.valueOf(status), response.errorStatus);
    }

    private static void assertPassed(Filter filter, String ip) throws Exception {
        assertPassed(filter, ServletFakes.request(ip));
    }

    private static void assertPassed(Filter filter, HttpServletRequest request) throws Exception {
        ServletFakes.RecordingResponse response = new ServletFakes.RecordingResponse();
        AtomicBoolean passed = new AtomicBoolean();
        filter.doFilter(request, response.proxy, (req, res) -> passed.set(true));
        assertTrue(passed.get(), () -> describe(request) + " は通過するべき");
        assertNull(response.errorStatus);
    }

    private static String describe(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        return request.getRemoteAddr() + (xff == null ? "" : "（X-Forwarded-For: " + xff + "）");
    }
}
