package jp.try0.servlet.ipblock;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** XML / JSON ソースの読み込み元。呼ばれるたびに新しいストリームを開く。 */
@FunctionalInterface
public interface ResourceOpener {

    InputStream open() throws IOException;

    /** ファイルシステム上のファイル。リロードのたびに読み直すので、運用中の書き換えが反映される。 */
    static ResourceOpener file(Path path) {
        Objects.requireNonNull(path, "path");
        return () -> Files.newInputStream(path);
    }

    /**
     * クラスパス上のリソース。呼び出し時点のコンテキストクラスローダー（なければこのクラスのローダー）を使う。
     * jar 内のリソースは実行中に書き換えられないので、内容を変えたい場合は {@link #file(Path)} を使う。
     */
    static ResourceOpener classpath(String resource) {
        Objects.requireNonNull(resource, "resource");
        String name = resource.startsWith("/") ? resource.substring(1) : resource;
        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = tccl != null ? tccl : ResourceOpener.class.getClassLoader();
        return () -> {
            InputStream in = loader.getResourceAsStream(name);
            if (in == null) {
                throw new FileNotFoundException("クラスパス上に見つかりません: " + name);
            }
            return in;
        };
    }
}
