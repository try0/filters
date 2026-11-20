package jp.try0.servlet.ipblock;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java コードからブロック対象を登録・削除するためのソース（スレッドセーフ）。
 *
 * <p>変更は次回のリロードで反映される。すぐに反映したい場合は {@link IpBlockList#reloadNow()} を呼ぶ。
 *
 * <pre>{@code
 * manual.add("203.0.113.5");
 * blockList.reloadNow();
 * }</pre>
 *
 * <p>同じアドレス・範囲は、表記が違っても 1 件として扱う（{@code 2001:DB8::1} と {@code 2001:db8::1}、
 * {@code 192.0.2.10} と {@code 192.0.2.10/32} など）。{@link #remove} も表記に関係なく削除でき、
 * {@link #load()} は最初に登録したときの表記で返す。
 */
public final class InMemoryBlockListSource implements BlockListSource {

    private final String name;
    /** 正規化した表記 → 登録時の表記。this で保護。 */
    private final Map<String, String> entries = new LinkedHashMap<>();

    public InMemoryBlockListSource() {
        this("in-memory");
    }

    public InMemoryBlockListSource(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    /** @throws IllegalArgumentException 1 つでも不正な値があれば、何も追加しない */
    public InMemoryBlockListSource add(String... ipOrCidr) {
        return addAll(Arrays.asList(ipOrCidr));
    }

    /** @throws IllegalArgumentException 1 つでも不正な値があれば、何も追加しない */
    public synchronized InMemoryBlockListSource addAll(Collection<String> ipOrCidr) {
        validated(ipOrCidr).forEach(entries::putIfAbsent);
        return this;
    }

    /**
     * 同じアドレス・範囲のエントリを削除する（表記は問わない）。
     *
     * @return 削除したら true。登録されていない場合や、IP / CIDR として解釈できない値の場合は false
     */
    public synchronized boolean remove(String ipOrCidr) {
        Objects.requireNonNull(ipOrCidr, "ipOrCidr");
        try {
            return entries.remove(IpMatcher.normalize(ipOrCidr)) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 内容を丸ごと置き換える。 @throws IllegalArgumentException 1 つでも不正な値があれば、何も変えない */
    public synchronized void replaceAll(Collection<String> ipOrCidr) {
        Map<String, String> next = validated(ipOrCidr);
        entries.clear();
        entries.putAll(next);
    }

    public synchronized void clear() {
        entries.clear();
    }

    @Override
    public synchronized List<String> load() {
        return List.copyOf(entries.values());
    }

    @Override
    public String name() {
        return name;
    }

    /** 正規化した表記 → 登録時の表記（同じ範囲が複数あれば最初のもの）。 */
    private static Map<String, String> validated(Collection<String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String value : values) {
            out.putIfAbsent(IpMatcher.normalize(value), value.trim());
        }
        return out;
    }
}
