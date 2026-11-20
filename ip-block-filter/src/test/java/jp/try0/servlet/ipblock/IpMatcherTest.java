package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IpMatcherTest {

    @Test
    void exactIpv4() {
        IpMatcher m = IpMatcher.of(List.of("192.0.2.10"));
        assertTrue(m.matches("192.0.2.10"));
        assertTrue(m.matches(" 192.0.2.10 "));
        assertFalse(m.matches("192.0.2.11"));
    }

    @Test
    void cidrIpv4() {
        IpMatcher m = IpMatcher.of(List.of("198.51.100.0/24", "10.1.2.3/8"));
        assertTrue(m.matches("198.51.100.0"));
        assertTrue(m.matches("198.51.100.255"));
        assertFalse(m.matches("198.51.101.0"));
        // ホスト部が 0 でない CIDR はネットワークアドレスに丸める
        assertTrue(m.matches("10.255.255.255"));
        assertFalse(m.matches("11.0.0.0"));
    }

    @Test
    void zeroPrefixMatchesWholeFamilyOnly() {
        IpMatcher m = IpMatcher.of(List.of("0.0.0.0/0"));
        assertTrue(m.matches("203.0.113.1"));
        assertFalse(m.matches("2001:db8::1"));
    }

    @Test
    void ipv6ExactMatchesRegardlessOfNotation() {
        IpMatcher m = IpMatcher.of(List.of("::1", "2001:db8::1"));
        // Tomcat の getRemoteAddr() は省略なし表記を返すことがある
        assertTrue(m.matches("0:0:0:0:0:0:0:1"));
        assertTrue(m.matches("2001:0db8:0000:0000:0000:0000:0000:0001"));
        assertTrue(m.matches("[2001:db8::1]"));
        assertFalse(m.matches("2001:db8::2"));
    }

    @Test
    void ipv6Cidr() {
        IpMatcher m = IpMatcher.of(List.of("2001:db8::/32"));
        assertTrue(m.matches("2001:db8:ffff::1"));
        assertFalse(m.matches("2001:db9::1"));
        assertFalse(m.matches("192.0.2.1"));
    }

    @Test
    void ipv4MappedIpv6IsTreatedAsIpv4() {
        IpMatcher m = IpMatcher.of(List.of("192.0.2.0/24"));
        assertTrue(m.matches("::ffff:192.0.2.10"));
    }

    @Test
    void ipv4MappedCidrCountsPrefixInIpv6Bits() {
        // ::ffff:192.0.2.0/120 は 192.0.2.0/24 と同じ範囲
        IpMatcher m = IpMatcher.of(List.of("::ffff:192.0.2.0/120"));
        assertTrue(m.matches("192.0.2.255"));
        assertTrue(m.matches("::ffff:192.0.2.1"));
        assertFalse(m.matches("192.0.3.0"));
    }

    @Test
    void ipv4MappedSlash96CoversAllIpv4() {
        IpMatcher m = IpMatcher.of(List.of("::ffff:0:0/96"));
        assertTrue(m.matches("203.0.113.1"));
        assertFalse(m.matches("2001:db8::1"));
    }

    @Test
    void zoneIdIsIgnored() {
        IpMatcher m = IpMatcher.of(List.of("fe80::1"));
        assertTrue(m.matches("fe80::1%eth0"));
        assertTrue(m.matches("fe80:0:0:0:0:0:0:1%2"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "localhost", "example.com", "example.com:80", "256.1.1.1", "1.2.3", "1.2.3.4.5",
        "192.0.2.0/33", "2001:db8::/129", "1.2.3.4/", "1.2.3.4/abc", "1.2.3.4/-1",
        "dead:beef", "::g", "abc::xyz", "[::1",
        // IPv4 射影アドレスのプレフィックス長は IPv6 のビット数で 96～128
        "::ffff:192.0.2.0/24", "::ffff:192.0.2.0/95", "::ffff:192.0.2.0/129"
    })
    void invalidEntriesAreRejected(String entry) {
        assertThrows(IllegalArgumentException.class, () -> IpMatcher.validate(entry));
    }

    @Test
    void nullEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IpMatcher.validate(null));
    }

    @Test
    void nonLiteralAddressesNeverMatch() {
        IpMatcher m = IpMatcher.of(List.of("0.0.0.0/0", "::/0"));
        assertFalse(m.matches(null));
        assertFalse(m.matches(""));
        assertFalse(m.matches("unknown"));
        assertFalse(m.matches("localhost"));
    }

    @Test
    void entriesAreDeduplicatedAndExposed() {
        IpMatcher m = IpMatcher.of(List.of("192.0.2.10", " 192.0.2.10 ", "198.51.100.0/24"));
        assertEquals(2, m.size());
        assertEquals(List.of("192.0.2.10", "198.51.100.0/24"), m.entries());
        assertThrows(UnsupportedOperationException.class, () -> m.entries().add("x"));
    }

    @Test
    void emptyMatcherMatchesNothing() {
        assertFalse(IpMatcher.empty().matches("192.0.2.10"));
        assertEquals(0, IpMatcher.empty().size());
    }
}
