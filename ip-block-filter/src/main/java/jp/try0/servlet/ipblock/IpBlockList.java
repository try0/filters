package jp.try0.servlet.ipblock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 複数の {@link BlockListSource} をまとめ、定期的にリロードするブロックリスト本体。
 * Servlet API には依存しないので、Filter 以外（バッチ、独自サーバー等）からも使える。
 *
 * <pre>{@code
 * IpBlockList blockList = IpBlockList.builder()
 *         .addSource(new JdbcBlockListSource(dataSource, "SELECT ip FROM blocked_ip WHERE enabled = TRUE"))
 *         .addSource(XmlBlockListSource.ofFile(Paths.get("/etc/myapp/ip-block-list.xml")))
 *         .reloadInterval(Duration.ofSeconds(30))
 *         .build();
 * blockList.start();              // 初回ロード + 定期リロード開始
 * blockList.isBlocked("192.0.2.10");
 * blockList.close();              // 停止
 * }</pre>
 *
 * <h2>動作</h2>
 * <ul>
 *   <li>リロードのたびに全ソースを読み、不変の {@link IpMatcher} を作って参照ごと差し替える。
 *       判定側はロックを取らない。</li>
 *   <li>あるソースの読み込みに失敗したら、そのソースだけ前回成功時の内容を使い続ける
 *       （DB 障害でブロックが外れたり、他のソースの更新が止まったりしない）。</li>
 *   <li>不正な値は警告ログを出して無視する（同じ値の警告は 1 回だけ）。</li>
 * </ul>
 */
public final class IpBlockList implements AutoCloseable {

    public static final Duration DEFAULT_RELOAD_INTERVAL = Duration.ofSeconds(30);
    static final String THREAD_NAME = "ip-block-list-reloader";

    private static final Logger LOG = LoggerFactory.getLogger(IpBlockList.class);

    private final List<BlockListSource> sources;
    private final Duration reloadInterval;
    private final boolean failOnStartupError;

    /** 現在有効なマッチャー。リロードのたびに丸ごと差し替える。 */
    private volatile IpMatcher matcher = IpMatcher.empty();

    // ---- リロード処理の状態（reloadLock で保護） ----
    private final Object reloadLock = new Object();
    private final Map<BlockListSource, List<String>> lastGood = new IdentityHashMap<>();
    private final Set<BlockListSource> failing = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<String> reportedInvalid = Set.of();

    // ---- ライフサイクル（lifecycleLock で保護） ----
    private final Object lifecycleLock = new Object();
    private ScheduledExecutorService scheduler;
    private boolean running;

    private IpBlockList(Builder builder) {
        this.sources = List.copyOf(builder.sources);
        this.reloadInterval = builder.reloadInterval;
        this.failOnStartupError = builder.failOnStartupError;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 指定アドレスがブロック対象か。{@link #start()} 前は常に false。 */
    public boolean isBlocked(String remoteAddr) {
        return matcher.matches(remoteAddr);
    }

    /** 現在有効なエントリ数。 */
    public int size() {
        return matcher.size();
    }

    /** 現在有効なエントリ（変更不可のスナップショット）。 */
    public List<String> entries() {
        return matcher.entries();
    }

    public boolean isRunning() {
        synchronized (lifecycleLock) {
            return running;
        }
    }

    /**
     * 初回ロードを行い、定期リロードを開始する。すでに開始済みなら何もしない。
     *
     * @throws IllegalStateException failOnStartupError が true で、初回ロードに失敗したソースがある場合
     */
    public void start() {
        synchronized (lifecycleLock) {
            if (running) {
                return;
            }
            boolean allLoaded = reloadNow();
            if (!allLoaded && failOnStartupError) {
                throw new IllegalStateException(
                        "初回ロードに失敗したソースがあります（failOnStartupError=true）。詳細は警告ログを参照してください");
            }
            if (!reloadInterval.isZero()) {
                long millis = Math.max(1, reloadInterval.toMillis());
                scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, THREAD_NAME);
                    t.setDaemon(true);
                    return t;
                });
                scheduler.scheduleWithFixedDelay(this::reloadSafely, millis, millis, TimeUnit.MILLISECONDS);
            }
            running = true;
            LOG.info("IP ブロックリストを開始しました（ソース: {}、リロード間隔: {}、件数: {}）",
                    sourceNames(), reloadInterval.isZero() ? "なし" : reloadInterval, size());
        }
    }

    /** 定期リロードを停止する（リロード用スレッドも終了する）。何度呼んでもよい。 */
    public void stop() {
        ScheduledExecutorService s;
        synchronized (lifecycleLock) {
            s = scheduler;
            scheduler = null;
            running = false;
        }
        if (s == null) {
            return;
        }
        s.shutdownNow();
        try {
            if (!s.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("リロード用スレッドが 5 秒以内に停止しませんでした");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        stop();
    }

    /**
     * すべてのソースをすぐに読み直す。{@link #start()} 前でも呼べる。
     *
     * @return すべてのソースの読み込みに成功したら true
     */
    public boolean reloadNow() {
        synchronized (reloadLock) {
            boolean allLoaded = true;
            IpMatcher.Builder builder = IpMatcher.builder();
            Set<String> invalid = new LinkedHashSet<>();

            for (BlockListSource source : sources) {
                List<String> entries;
                try {
                    entries = normalize(source.load());
                    lastGood.put(source, entries);
                    if (failing.remove(source)) {
                        LOG.info("[{}] 読み込みが復旧しました", source.name());
                    }
                } catch (Exception e) {
                    allLoaded = false;
                    entries = lastGood.getOrDefault(source, List.of());
                    logFailure(source, e, entries.size());
                }
                for (String entry : entries) {
                    try {
                        builder.add(entry);
                    } catch (IllegalArgumentException e) {
                        invalid.add("[" + source.name() + "] " + e.getMessage());
                    }
                }
            }
            reportInvalid(invalid);

            IpMatcher next = builder.build();
            int before = matcher.size();
            matcher = next;
            if (before != next.size()) {
                LOG.info("IP ブロックリストを更新しました: {} 件 → {} 件", before, next.size());
            }
            return allLoaded;
        }
    }

    // ------------------------------------------------------------------

    /** 定期実行用。例外が漏れると以降の定期実行が止まってしまうので、すべて捕まえる。 */
    private void reloadSafely() {
        try {
            reloadNow();
        } catch (Throwable t) {
            LOG.error("IP ブロックリストのリロード中に予期しないエラーが発生しました", t);
        }
    }

    private void logFailure(BlockListSource source, Exception e, int kept) {
        String message = "[{}] 読み込みに失敗しました。前回成功時の内容（{} 件）を使い続けます: {}";
        if (failing.add(source)) {
            // 失敗し始めた最初の 1 回だけスタックトレースを出す（最後の引数が Throwable だとスタックトレースになる）
            LOG.warn(message, source.name(), kept, e.toString(), e);
        } else {
            LOG.warn(message, source.name(), kept, e.toString());
        }
    }

    private void reportInvalid(Set<String> invalid) {
        for (String message : invalid) {
            if (!reportedInvalid.contains(message)) {
                LOG.warn("不正なエントリを無視しました: {}", message);
            }
        }
        reportedInvalid = invalid;
    }

    private static List<String> normalize(Collection<String> loaded) {
        if (loaded == null) {
            throw new IllegalStateException("load() が null を返しました");
        }
        List<String> out = new ArrayList<>(loaded.size());
        for (String value : loaded) {
            if (value != null && !value.isBlank()) {
                out.add(value.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }

    private String sourceNames() {
        List<String> names = new ArrayList<>(sources.size());
        for (BlockListSource source : sources) {
            names.add(source.name());
        }
        return String.join(", ", names);
    }

    /** {@link IpBlockList} のビルダー。 */
    public static final class Builder {

        private final List<BlockListSource> sources = new ArrayList<>();
        private Duration reloadInterval = DEFAULT_RELOAD_INTERVAL;
        private boolean failOnStartupError;

        private Builder() {
        }

        /** ソースを追加する。複数追加した場合は、すべてのソースの和集合がブロック対象になる。 */
        public Builder addSource(BlockListSource source) {
            sources.add(Objects.requireNonNull(source, "source"));
            return this;
        }

        /** リロード間隔（既定 30 秒）。{@link Duration#ZERO} なら定期リロードしない。 */
        public Builder reloadInterval(Duration interval) {
            Objects.requireNonNull(interval, "interval");
            if (interval.isNegative()) {
                throw new IllegalArgumentException("reloadInterval は 0 以上: " + interval);
            }
            this.reloadInterval = interval;
            return this;
        }

        /**
         * true にすると、初回ロードに失敗したソースがある場合に {@link #start()} が例外を投げる
         * （Filter から使うとアプリの起動が失敗する）。既定は false で、その場合は読めたソースだけで動き始める。
         */
        public Builder failOnStartupError(boolean failOnStartupError) {
            this.failOnStartupError = failOnStartupError;
            return this;
        }

        public IpBlockList build() {
            if (sources.isEmpty()) {
                throw new IllegalStateException("ソースが 1 つも指定されていません");
            }
            return new IpBlockList(this);
        }
    }
}
