package jp.try0.servlet.ipblock;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link IpBlockList} に載っている IP からのリクエストを拒否する Servlet Filter。
 *
 * <h2>使い方 1: web.xml（init-param で設定）</h2>
 * <pre>{@code
 * <filter>
 *   <filter-name>ipBlockFilter</filter-name>
 *   <filter-class>jp.try0.servlet.ipblock.IpBlockFilter</filter-class>
 *   <init-param><param-name>mode</param-name><param-value>db,xml</param-value></init-param>
 *   <init-param><param-name>db.jndiName</param-name><param-value>jdbc/appDb</param-value></init-param>
 *   <init-param><param-name>db.query</param-name><param-value>SELECT ip FROM blocked_ip WHERE enabled = TRUE</param-value></init-param>
 *   <init-param><param-name>xml.location</param-name><param-value>/WEB-INF/ip-block-list.xml</param-value></init-param>
 * </filter>
 * }</pre>
 * 指定できる init-param は {@link FilterInitParams} を参照。
 *
 * <h2>使い方 2: Java API</h2>
 * <pre>{@code
 * IpBlockList blockList = IpBlockList.builder()
 *         .addSource(new JdbcBlockListSource(dataSource, "SELECT ip FROM blocked_ip"))
 *         .build();
 * IpBlockFilter filter = IpBlockFilter.builder(blockList)
 *         .forwardedFor(ForwardedForResolver.builder().trustedProxies("10.0.0.0/8").build()) // 任意
 *         .build();
 * servletContext.addFilter("ipBlockFilter", filter)
 *         .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
 * }</pre>
 * この場合 init-param は読まない。
 *
 * <p>{@link #init} で {@link IpBlockList#start()}、{@link #destroy()} で {@link IpBlockList#stop()} を呼ぶ。
 *
 * <h2>リバースプロキシの後ろで使う場合</h2>
 * 次のどちらかで、本来のクライアント IP を判定に使えるようにする。
 * <ul>
 *   <li>このフィルターの X-Forwarded-For 対応を使う（init-param {@code forwardedFor.trustedProxies}、
 *       または {@link Builder#forwardedFor}）。判定の仕方は {@link ForwardedForResolver} を参照。</li>
 *   <li>このフィルターより前で Tomcat の RemoteIpFilter（または RemoteIpValve）を通し、
 *       {@code getRemoteAddr()} が本来のクライアント IP を返すようにしておく。</li>
 * </ul>
 */
public class IpBlockFilter implements Filter {

    private static final Logger LOG = LoggerFactory.getLogger(IpBlockFilter.class);

    private IpBlockList blockList;
    private int denyStatus = HttpServletResponse.SC_FORBIDDEN;
    /** null なら X-Forwarded-For を見ない。 */
    private ForwardedForResolver forwardedFor;

    /** web.xml から使うためのコンストラクタ。設定は init-param から読む。 */
    public IpBlockFilter() {
    }

    /**
     * Java API 用。拒否時のステータスは 403、X-Forwarded-For は見ない。
     * これらを変えたい場合は {@link #builder(IpBlockList)} を使う。
     */
    public IpBlockFilter(IpBlockList blockList) {
        this(builder(blockList));
    }

    private IpBlockFilter(Builder builder) {
        this.blockList = builder.blockList;
        this.denyStatus = builder.denyStatus;
        this.forwardedFor = builder.forwardedFor;
    }

    /** Java API 用のビルダー。 */
    public static Builder builder(IpBlockList blockList) {
        return new Builder(blockList);
    }

    @Override
    public void init(FilterConfig config) throws ServletException {
        if (blockList == null) {
            FilterInitParams params = new FilterInitParams(config);
            denyStatus = params.denyStatus();
            forwardedFor = params.forwardedFor();
            blockList = createBlockList(config);
        }
        try {
            blockList.start();
        } catch (RuntimeException e) {
            throw new ServletException("IP ブロックリストを開始できませんでした", e);
        }
    }

    /**
     * web.xml から使う場合に {@link IpBlockList} を組み立てる。
     * 独自のソースを足したい場合はサブクラスで上書きできる。
     */
    protected IpBlockList createBlockList(FilterConfig config) throws ServletException {
        return new FilterInitParams(config).buildBlockList();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String remoteAddr = request.getRemoteAddr();
        String clientIp = clientIp(request, remoteAddr);
        if (blockList.isBlocked(clientIp)) {
            if (clientIp.equals(remoteAddr)) {
                LOG.debug("ブロック対象 IP からのリクエストを拒否しました: {}", clientIp);
            } else {
                LOG.debug("ブロック対象 IP からのリクエストを拒否しました: {}（接続元: {}）", clientIp, remoteAddr);
            }
            ((HttpServletResponse) response).sendError(denyStatus);
            return;
        }
        chain.doFilter(request, response);
    }

    private String clientIp(ServletRequest request, String remoteAddr) {
        if (forwardedFor == null || !(request instanceof HttpServletRequest)) {
            return remoteAddr;
        }
        Enumeration<String> values = ((HttpServletRequest) request).getHeaders(forwardedFor.headerName());
        if (values == null) {
            return remoteAddr; // コンテナがヘッダーの参照を許可していない場合（Servlet 仕様上 null があり得る）
        }
        return forwardedFor.resolve(remoteAddr, Collections.list(values));
    }

    @Override
    public void destroy() {
        if (blockList != null) {
            blockList.stop();
        }
    }

    /** 実行中のブロックリスト（管理画面から reloadNow() したい場合など）。init 前は web.xml 利用時 null。 */
    public IpBlockList getBlockList() {
        return blockList;
    }

    /** X-Forwarded-For の設定。使っていなければ null。init 前は web.xml 利用時 null。 */
    public ForwardedForResolver getForwardedFor() {
        return forwardedFor;
    }

    /** {@link IpBlockFilter} のビルダー（Java API 用）。 */
    public static final class Builder {

        private final IpBlockList blockList;
        private int denyStatus = HttpServletResponse.SC_FORBIDDEN;
        private ForwardedForResolver forwardedFor;

        private Builder(IpBlockList blockList) {
            this.blockList = Objects.requireNonNull(blockList, "blockList");
        }

        /**
         * 拒否するときの HTTP ステータス。既定は 403。
         *
         * @throws IllegalArgumentException 400～599 以外の場合
         */
        public Builder denyStatus(int denyStatus) {
            if (denyStatus < 400 || denyStatus > 599) {
                throw new IllegalArgumentException("denyStatus は 400～599 で指定してください: " + denyStatus);
            }
            this.denyStatus = denyStatus;
            return this;
        }

        /** X-Forwarded-For からクライアント IP を求める設定。null（既定）なら getRemoteAddr() だけで判定する。 */
        public Builder forwardedFor(ForwardedForResolver forwardedFor) {
            this.forwardedFor = forwardedFor;
            return this;
        }

        public IpBlockFilter build() {
            return new IpBlockFilter(this);
        }
    }
}
