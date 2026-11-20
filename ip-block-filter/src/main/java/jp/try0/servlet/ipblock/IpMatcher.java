package jp.try0.servlet.ipblock;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 単体 IP / CIDR の集合に対する、不変（immutable）なマッチャー。
 *
 * <ul>
 *   <li>単体 IP は「プレフィックス長 32（IPv6 は 128）の CIDR」として扱う。</li>
 *   <li>IPv4 射影アドレス（{@code ::ffff:192.0.2.1}）は IPv4 アドレスとして扱う。CIDR で書く場合の
 *       プレフィックス長は IPv6 のビット数（96～128）で数える（{@code ::ffff:192.0.2.0/120} は
 *       {@code 192.0.2.0/24} と同じ）。</li>
 *   <li>プレフィックス長ごとにハッシュ表を持つので、判定コストは登録件数ではなく
 *       「登録されているプレフィックス長の種類数」に比例する。</li>
 *   <li>文字列の解析で DNS 問い合わせは一切行わない（ホスト名は不正な値として扱う）。</li>
 * </ul>
 *
 * <p>不変なので、複数スレッドから同時に {@link #matches(String)} を呼んでも安全。
 */
public final class IpMatcher {

    private static final Pattern IPV4 =
            Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    /** 先頭が 16 進数字か ':' の文字列なら、JDK は DNS を引かずに IPv6 リテラルとして解析する。 */
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*");
    private static final Pattern PREFIX = Pattern.compile("\\d{1,3}");

    private static final IpMatcher EMPTY = builder().build();

    private final Table v4;
    private final Table v6;
    private final List<String> entries;

    private IpMatcher(Table v4, Table v6, List<String> entries) {
        this.v4 = v4;
        this.v6 = v6;
        this.entries = entries;
    }

    /** 何にもマッチしないマッチャー。 */
    public static IpMatcher empty() {
        return EMPTY;
    }

    /**
     * 単体 IP / CIDR の一覧からマッチャーを作る。
     *
     * @throws IllegalArgumentException 不正なエントリが含まれている場合
     */
    public static IpMatcher of(Collection<String> entries) {
        Builder builder = builder();
        for (String entry : entries) {
            builder.add(entry);
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 単体 IP または CIDR として正しい形式かを検証する。
     *
     * @throws IllegalArgumentException 不正な形式の場合
     */
    public static void validate(String entry) {
        parseEntry(entry);
    }

    /**
     * アドレスがいずれかのエントリにマッチするか。
     * IP リテラルとして解釈できない値（null、ホスト名など）は常に false。
     */
    public boolean matches(String address) {
        if (address == null) {
            return false;
        }
        byte[] addr = parseAddress(address.trim());
        if (addr == null) {
            return false;
        }
        return (addr.length == 4 ? v4 : v6).contains(addr);
    }

    /** 登録されているエントリ数（同じ文字列は 1 件と数える）。 */
    public int size() {
        return entries.size();
    }

    /** 登録されているエントリ（変更不可）。 */
    public List<String> entries() {
        return entries;
    }

    // ------------------------------------------------------------------

    private static Range parseEntry(String entry) {
        if (entry == null) {
            throw new IllegalArgumentException("エントリが null です");
        }
        String s = entry.trim();
        int slash = s.indexOf('/');
        String address = slash < 0 ? s : s.substring(0, slash);
        byte[] addr = parseAddress(address);
        if (addr == null) {
            throw new IllegalArgumentException("IP アドレスとして解釈できません: \"" + entry + "\"");
        }
        // IPv6 表記の IPv4 射影アドレス（::ffff:192.0.2.0）は 4 バイトになるが、プレフィックス長は
        // 書かれた表記どおり IPv6 の 128 ビットで数える（/120 → IPv4 の /24）。
        // 96 未満は IPv4 と IPv6 にまたがる範囲になり表せないので不正とする。
        int offset = addr.length == 4 && address.indexOf(':') >= 0 ? 96 : 0;
        int minPrefix = offset;
        int maxPrefix = offset + addr.length * 8;
        int prefix = maxPrefix;
        if (slash >= 0) {
            String p = s.substring(slash + 1);
            if (!PREFIX.matcher(p).matches()
                    || Integer.parseInt(p) < minPrefix || Integer.parseInt(p) > maxPrefix) {
                throw new IllegalArgumentException("プレフィックス長が不正です（" + minPrefix + "～" + maxPrefix
                        + (offset > 0 ? "。IPv4 射影アドレスは IPv6 のビット数で数える" : "") + "）: \"" + entry + "\"");
            }
            prefix = Integer.parseInt(p);
        }
        prefix -= offset;
        return new Range(mask(addr, prefix), prefix);
    }

    /**
     * エントリを正規化した表記にする。表記が違っても同じ範囲を表すエントリは同じ文字列になる
     * （{@code 2001:DB8::1} と {@code 2001:db8:0:0:0:0:0:1}、{@code 192.0.2.10} と {@code 192.0.2.10/32}、
     * {@code ::ffff:192.0.2.0/120} と {@code 192.0.2.0/24} など）。
     *
     * @throws IllegalArgumentException 不正な形式の場合
     */
    static String normalize(String entry) {
        Range range = parseEntry(entry);
        try {
            return InetAddress.getByAddress(range.addr).getHostAddress() + "/" + range.prefix;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // 4 / 16 バイト以外のときだけなので起こらない
        }
    }

    /** IP リテラルをバイト列にする。解釈できなければ null。DNS は引かない。 */
    static byte[] parseAddress(String s) {
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = IPV4.matcher(s);
        if (m.matches()) {
            byte[] addr = new byte[4];
            for (int i = 0; i < 4; i++) {
                int octet = Integer.parseInt(m.group(i + 1));
                if (octet > 255) {
                    return null;
                }
                addr[i] = (byte) octet;
            }
            return addr;
        }
        String v6 = s;
        if (v6.length() > 2 && v6.charAt(0) == '[' && v6.charAt(v6.length() - 1) == ']') {
            v6 = v6.substring(1, v6.length() - 1);
        }
        int zone = v6.indexOf('%');
        if (zone >= 0) {
            v6 = v6.substring(0, zone); // スコープ ID（fe80::1%eth0 の %eth0）は無視する
        }
        if (v6.indexOf(':') < 0 || !IPV6_CHARS.matcher(v6).matches()) {
            return null;
        }
        try {
            // IPv4 射影アドレス（::ffff:192.0.2.1）は Inet4Address になり、4 バイトで返る
            return InetAddress.getByName(v6).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] mask(byte[] addr, int prefix) {
        byte[] out = addr.clone();
        int i = prefix / 8;
        int rem = prefix % 8;
        if (rem != 0) {
            out[i] = (byte) (out[i] & (0xFF << (8 - rem)));
            i++;
        }
        for (; i < out.length; i++) {
            out[i] = 0;
        }
        return out;
    }

    /** {@link IpMatcher} のビルダー（スレッドセーフではない）。 */
    public static final class Builder {

        private final Map<Integer, Set<Key>> v4 = new TreeMap<>();
        private final Map<Integer, Set<Key>> v6 = new TreeMap<>();
        private final Set<String> entries = new LinkedHashSet<>();

        private Builder() {
        }

        /**
         * エントリを追加する。
         *
         * @throws IllegalArgumentException 不正な形式の場合（ビルダーの状態は変わらない）
         */
        public Builder add(String entry) {
            Range range = parseEntry(entry);
            (range.addr.length == 4 ? v4 : v6)
                    .computeIfAbsent(range.prefix, k -> new HashSet<>())
                    .add(new Key(range.addr));
            entries.add(entry.trim());
            return this;
        }

        public IpMatcher build() {
            return new IpMatcher(Table.of(v4), Table.of(v6), List.copyOf(entries));
        }
    }

    /** プレフィックス長ごとの「マスク済みネットワークアドレス」の集合。 */
    private static final class Table {

        private final int[] prefixes;
        private final List<Set<Key>> networks;

        private Table(int[] prefixes, List<Set<Key>> networks) {
            this.prefixes = prefixes;
            this.networks = networks;
        }

        static Table of(Map<Integer, Set<Key>> byPrefix) {
            int[] prefixes = new int[byPrefix.size()];
            List<Set<Key>> networks = new ArrayList<>(byPrefix.size());
            int i = 0;
            for (Map.Entry<Integer, Set<Key>> e : byPrefix.entrySet()) {
                prefixes[i++] = e.getKey();
                networks.add(Set.copyOf(e.getValue()));
            }
            return new Table(prefixes, List.copyOf(networks));
        }

        boolean contains(byte[] addr) {
            for (int i = 0; i < prefixes.length; i++) {
                if (networks.get(i).contains(new Key(mask(addr, prefixes[i])))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Range {
        final byte[] addr;
        final int prefix;

        Range(byte[] addr, int prefix) {
            this.addr = addr;
            this.prefix = prefix;
        }
    }

    /** byte[] を内容で比較するためのキー。 */
    private static final class Key {
        private final byte[] bytes;
        private final int hash;

        Key(byte[] bytes) {
            this.bytes = bytes;
            this.hash = Arrays.hashCode(bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && Arrays.equals(bytes, ((Key) o).bytes);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
