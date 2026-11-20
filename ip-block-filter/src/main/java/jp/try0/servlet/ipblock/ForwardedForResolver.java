package jp.try0.servlet.ipblock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * X-Forwarded-For ヘッダーから本来のクライアント IP を求める。Servlet API には依存しない。
 *
 * <p>X-Forwarded-For はクライアントが自由に書けるので、そのまま信じると、ブロック対象の IP からでも
 * 偽の値を書くだけで通り抜けられてしまう。そこで Tomcat の RemoteIpFilter と同じく、
 * <b>信頼するプロキシ</b>が書いた部分だけを見る。
 *
 * <ol>
 *   <li>直接の接続元（{@code getRemoteAddr()}）が信頼するプロキシでなければ、ヘッダーは見ずに接続元をそのまま使う。</li>
 *   <li>信頼するプロキシからの接続なら、ヘッダーの値を右（接続元に近い側）から順に見ていき、
 *       信頼するプロキシでない最初のアドレスをクライアント IP とする。それより左はクライアントが
 *       書いた値かもしれないので見ない。</li>
 *   <li>すべて信頼するプロキシだった場合はいちばん左のアドレス、ヘッダーがない場合は接続元を使う。</li>
 * </ol>
 *
 * <p>例: 信頼するプロキシが {@code 10.0.0.0/8}、接続元が {@code 10.0.0.1}、ヘッダーが
 * {@code "1.1.1.1, 203.0.113.9, 10.0.0.2"} なら、クライアント IP は {@code 203.0.113.9}。
 * {@code 1.1.1.1} はクライアントが偽装して書いた値かもしれないので使わない。
 *
 * <p>ヘッダーが複数行ある場合は、上から順につなげて 1 つのリストとして扱う。各値に付いているポート
 * （{@code 192.0.2.1:4711}、{@code [2001:db8::1]:443}）と IPv6 の角括弧は取り除く。
 * IP として解釈できない値（{@code unknown} など）に行き当たった場合は、そこで止めてその値を返す
 * （ブロックリストにはマッチしない）。
 *
 * <p>不変でスレッドセーフ。
 */
public final class ForwardedForResolver {

    public static final String DEFAULT_HEADER_NAME = "X-Forwarded-For";

    private final IpMatcher trustedProxies;
    private final String headerName;

    private ForwardedForResolver(Builder builder) {
        this.trustedProxies = builder.trustedProxies.build();
        this.headerName = builder.headerName;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 読み取るヘッダー名。 */
    public String headerName() {
        return headerName;
    }

    /** 信頼するプロキシとして登録されている IP / CIDR。 */
    public List<String> trustedProxies() {
        return trustedProxies.entries();
    }

    /**
     * クライアント IP を求める。
     *
     * @param remoteAddr   直接の接続元（{@code request.getRemoteAddr()}）
     * @param headerValues ヘッダーの値（複数行ある場合は受信順）。null や空でもよい
     * @return クライアント IP。ポートと角括弧は取り除いた形
     */
    public String resolve(String remoteAddr, Iterable<String> headerValues) {
        if (headerValues == null || !trustedProxies.matches(remoteAddr)) {
            return remoteAddr;
        }
        List<String> hops = new ArrayList<>();
        for (String value : headerValues) {
            if (value == null) {
                continue;
            }
            for (String item : value.split(",")) {
                String hop = stripPort(item.trim());
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        if (hops.isEmpty()) {
            return remoteAddr;
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (IpMatcher.parseAddress(hop) == null || !trustedProxies.matches(hop)) {
                return hop;
            }
        }
        return hops.get(0);
    }

    /** "192.0.2.1:4711" → "192.0.2.1"、"[2001:db8::1]:443" / "[2001:db8::1]" → "2001:db8::1"。 */
    static String stripPort(String hop) {
        if (hop.startsWith("[")) {
            int close = hop.indexOf(']');
            return close > 0 ? hop.substring(1, close) : hop;
        }
        int colon = hop.indexOf(':');
        if (colon >= 0 && hop.indexOf(':', colon + 1) < 0) {
            return hop.substring(0, colon); // コロンが 1 つだけ = IPv4（またはホスト名）＋ポート
        }
        return hop;
    }

    @Override
    public String toString() {
        return "ForwardedForResolver[header=" + headerName + ", trustedProxies=" + trustedProxies.entries() + "]";
    }

    public static final class Builder {

        private final IpMatcher.Builder trustedProxies = IpMatcher.builder();
        private int trustedProxyCount;
        private String headerName = DEFAULT_HEADER_NAME;

        private Builder() {
        }

        /**
         * 信頼するプロキシ（ロードバランサーやリバースプロキシ）の IP / CIDR を追加する。1 つ以上必要。
         *
         * <p>{@code 0.0.0.0/0} や {@code ::/0} を指定すると全員を信頼することになり、
         * ヘッダーのいちばん左（クライアントが自由に書ける値）が使われる。
         * アプリにプロキシ経由でしか届かないことがネットワーク上保証されている場合以外は指定しないこと。
         *
         * @throws IllegalArgumentException IP / CIDR として解釈できない値がある場合
         */
        public Builder trustedProxies(String... entries) {
            return trustedProxies(Arrays.asList(entries));
        }

        /** {@link #trustedProxies(String...)} のコレクション版。 */
        public Builder trustedProxies(Collection<String> entries) {
            for (String entry : entries) {
                trustedProxies.add(entry);
                trustedProxyCount++;
            }
            return this;
        }

        /** 読み取るヘッダー名。既定は {@value ForwardedForResolver#DEFAULT_HEADER_NAME}。 */
        public Builder headerName(String headerName) {
            Objects.requireNonNull(headerName, "headerName");
            if (headerName.isBlank()) {
                throw new IllegalArgumentException("ヘッダー名が空です");
            }
            this.headerName = headerName.trim();
            return this;
        }

        /** @throws IllegalStateException 信頼するプロキシが 1 つも指定されていない場合 */
        public ForwardedForResolver build() {
            if (trustedProxyCount == 0) {
                throw new IllegalStateException("信頼するプロキシを 1 つ以上指定してください");
            }
            return new ForwardedForResolver(this);
        }
    }
}
