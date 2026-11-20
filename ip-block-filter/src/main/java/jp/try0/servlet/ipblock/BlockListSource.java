package jp.try0.servlet.ipblock;

import java.util.Collection;
import java.util.Objects;

/**
 * ブロック対象（単体 IP または CIDR 表記の文字列）の取得元。
 *
 * <p>標準で {@link JdbcBlockListSource}・{@link XmlBlockListSource}・{@link JsonBlockListSource}・
 * {@link InMemoryBlockListSource} を用意している。独自の取得元はラムダでも書ける。
 *
 * <pre>{@code
 * BlockListSource fromApi = BlockListSource.named("threat-feed", () -> client.fetchBlockedIps());
 * }</pre>
 */
@FunctionalInterface
public interface BlockListSource {

    /**
     * 現在のブロック対象を返す。リロードのたびに（リロード用スレッドから）呼ばれる。
     *
     * <p>例外を投げた場合、{@link IpBlockList} はこのソースについて前回成功時の内容を使い続ける。
     * 不正な形式の値は警告ログを出して無視される。
     */
    Collection<String> load() throws Exception;

    /** ログに出す名前。 */
    default String name() {
        return getClass().getSimpleName();
    }

    /** ラムダ等に名前を付ける。 */
    static BlockListSource named(String name, BlockListSource loader) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(loader, "loader");
        return new BlockListSource() {
            @Override
            public Collection<String> load() throws Exception {
                return loader.load();
            }

            @Override
            public String name() {
                return name;
            }
        };
    }
}
