package jp.try0.servlet.ipblock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON ファイルからブロック対象を読み込むソース。次のどちらの形式でもよい。
 *
 * <pre>{@code
 * ["192.0.2.10", "198.51.100.0/24"]
 *
 * {
 *   "ips": [
 *     "192.0.2.10",
 *     { "ip": "198.51.100.0/24", "note": "ip 以外のキーは無視される" }
 *   ]
 * }
 * }</pre>
 *
 * <p>ファイルは UTF-8（BOM 付きでもよい）。標準の JSON だけを受け付け、コメントや末尾のカンマは書けない
 * （詳細は {@link SimpleJson}）。形式が違う場合や JSON として壊れている場合（空ファイルを含む）は
 * 例外になり、前回の内容が使われ続ける。
 */
public final class JsonBlockListSource implements BlockListSource {

    static final String IPS_FIELD = "ips";
    static final String IP_FIELD = "ip";

    private final String name;
    private final ResourceOpener opener;

    public JsonBlockListSource(String name, ResourceOpener opener) {
        this.name = Objects.requireNonNull(name, "name");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    public static JsonBlockListSource ofFile(Path path) {
        return new JsonBlockListSource("json:" + path, ResourceOpener.file(path));
    }

    public static JsonBlockListSource ofClasspath(String resource) {
        return new JsonBlockListSource("json:classpath:" + resource, ResourceOpener.classpath(resource));
    }

    @Override
    public List<String> load() throws IOException {
        Object root;
        try (InputStream in = opener.open()) {
            root = SimpleJson.parse(in);
        }
        Object list = root instanceof Map ? ((Map<?, ?>) root).get(IPS_FIELD) : root;
        if (!(list instanceof List)) {
            throw new IOException("JSON は配列、または \"" + IPS_FIELD + "\" 配列を持つオブジェクトである必要があります");
        }
        List<?> items = (List<?>) list;
        List<String> entries = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            Object value = item instanceof Map ? ((Map<?, ?>) item).get(IP_FIELD) : item;
            if (!(value instanceof String)) {
                throw new IOException((i + 1) + " 番目の要素が不正です。各要素は文字列、または \""
                        + IP_FIELD + "\" に文字列を持つオブジェクトである必要があります");
            }
            String v = ((String) value).trim();
            if (!v.isEmpty()) {
                entries.add(v);
            }
        }
        return entries;
    }

    @Override
    public String name() {
        return name;
    }
}
