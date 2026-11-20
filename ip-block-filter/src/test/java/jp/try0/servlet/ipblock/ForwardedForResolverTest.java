package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ForwardedForResolverTest {

    /** 10.0.0.0/8 と fd00::/8 を信頼するプロキシとする。 */
    private final ForwardedForResolver resolver = ForwardedForResolver.builder()
            .trustedProxies("10.0.0.0/8", "fd00::/8")
            .build();

    @Test
    void headerFromUntrustedPeerIsIgnored() {
        // プロキシを通さずに直接来たクライアントが偽装したヘッダーは見ない
        assertEquals("203.0.113.9", resolver.resolve("203.0.113.9", List.of("192.0.2.1")));
    }

    @Test
    void singleHopBehindTrustedProxy() {
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("203.0.113.9")));
    }

    @Test
    void spoofedLeftmostValuesAreNotUsed() {
        // クライアントが "1.1.1.1" と偽装して送り、プロキシが本当の接続元を右に追記した
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("1.1.1.1, 203.0.113.9")));
    }

    @Test
    void trustedHopsAreSkippedFromTheRight() {
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("1.1.1.1, 203.0.113.9, 10.0.0.3, 10.0.0.2")));
    }

    @Test
    void multipleHeaderLinesAreConcatenatedInOrder() {
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("1.1.1.1", "203.0.113.9, 10.0.0.2")));
    }

    @Test
    void allTrustedHopsFallBackToLeftmost() {
        assertEquals("10.0.0.3", resolver.resolve("10.0.0.1", List.of("10.0.0.3, 10.0.0.2")));
    }

    @Test
    void missingOrEmptyHeaderFallsBackToRemoteAddr() {
        assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", null));
        assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", List.of()));
        assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", List.of("", " , ,")));
        assertEquals("10.0.0.1", resolver.resolve("10.0.0.1", Arrays.asList((String) null)));
    }

    @Test
    void emptyItemsAreSkipped() {
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("203.0.113.9,, ,10.0.0.2,")));
    }

    @Test
    void unparseableHopStopsTheWalk() {
        // プロキシが "unknown" と書いた場合はそこで止まる（ブロックリストにはマッチしない値になる）
        assertEquals("unknown", resolver.resolve("10.0.0.1", List.of("203.0.113.9, unknown, 10.0.0.2")));
    }

    @Test
    void ipv6Proxies() {
        assertEquals("2001:db8::1", resolver.resolve("fd00::1", List.of("2001:db8::1, fd00::2")));
        // 非省略表記の接続元でも信頼するプロキシとして判定できる
        assertEquals("203.0.113.9", resolver.resolve("fd00:0:0:0:0:0:0:1", List.of("203.0.113.9")));
    }

    @Test
    void ipv4MappedRemoteAddrIsRecognizedAsTrusted() {
        assertEquals("203.0.113.9", resolver.resolve("::ffff:10.0.0.1", List.of("203.0.113.9")));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "203.0.113.9:4711       | 203.0.113.9",
            "[2001:db8::1]:443      | 2001:db8::1",
            "[2001:db8::1]          | 2001:db8::1",
            "2001:db8::1            | 2001:db8::1",
            "203.0.113.9            | 203.0.113.9",
            "unknown                | unknown",
            "[broken                | [broken",
    })
    void portsAndBracketsAreStripped(String hop, String expected) {
        assertEquals(expected, resolver.resolve("10.0.0.1", List.of(hop)));
    }

    @Test
    void trustedHopWithPortIsStillSkipped() {
        assertEquals("203.0.113.9", resolver.resolve("10.0.0.1", List.of("203.0.113.9, 10.0.0.2:8080")));
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalStateException.class, () -> ForwardedForResolver.builder().build());
        assertThrows(IllegalArgumentException.class, () -> ForwardedForResolver.builder().trustedProxies("proxy.local"));
        assertThrows(IllegalArgumentException.class, () -> ForwardedForResolver.builder().trustedProxies("10.0.0.0/33"));
        assertThrows(IllegalArgumentException.class, () -> ForwardedForResolver.builder().headerName(" "));
    }

    @Test
    void headerNameAndTrustedProxiesAreExposed() {
        ForwardedForResolver custom = ForwardedForResolver.builder()
                .trustedProxies(List.of("192.168.0.0/16"))
                .headerName(" X-Real-Client-IP ")
                .build();
        assertEquals("X-Real-Client-IP", custom.headerName());
        assertEquals(List.of("192.168.0.0/16"), custom.trustedProxies());
        assertEquals(ForwardedForResolver.DEFAULT_HEADER_NAME, resolver.headerName());
    }
}
