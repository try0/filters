package jp.try0.servlet.ipblock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.helpers.DefaultHandler;

/**
 * XML ファイルからブロック対象を読み込むソース。
 *
 * <pre>{@code
 * <?xml version="1.0" encoding="UTF-8"?>
 * <ip-block-list>
 *   <ip>192.0.2.10</ip>
 *   <ip note="属性は自由に付けてよい（無視される）">198.51.100.0/24</ip>
 *   <ip>2001:db8::/32</ip>
 * </ip-block-list>
 * }</pre>
 *
 * <p>ルート要素が違う場合や XML として壊れている場合は例外になり、前回の内容が使われ続ける
 * （編集途中の壊れたファイルでブロックリストが空になることはない）。
 * DOCTYPE 宣言は XXE 対策のため拒否する。
 */
public final class XmlBlockListSource implements BlockListSource {

    static final String ROOT_ELEMENT = "ip-block-list";
    static final String IP_ELEMENT = "ip";

    private final String name;
    private final ResourceOpener opener;

    public XmlBlockListSource(String name, ResourceOpener opener) {
        this.name = Objects.requireNonNull(name, "name");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    public static XmlBlockListSource ofFile(Path path) {
        return new XmlBlockListSource("xml:" + path, ResourceOpener.file(path));
    }

    public static XmlBlockListSource ofClasspath(String resource) {
        return new XmlBlockListSource("xml:classpath:" + resource, ResourceOpener.classpath(resource));
    }

    @Override
    public List<String> load() throws Exception {
        DocumentBuilder parser = newSecureFactory().newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler()); // 標準エラーへの出力を抑止（致命的エラーは例外になる）
        Element root;
        try (InputStream in = opener.open()) {
            root = parser.parse(in).getDocumentElement();
        }
        if (!ROOT_ELEMENT.equals(root.getTagName())) {
            throw new IOException("ルート要素は <" + ROOT_ELEMENT + "> である必要があります: <"
                    + root.getTagName() + ">");
        }
        NodeList nodes = root.getElementsByTagName(IP_ELEMENT);
        List<String> entries = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            String value = nodes.item(i).getTextContent().trim();
            if (!value.isEmpty()) {
                entries.add(value);
            }
        }
        return entries;
    }

    @Override
    public String name() {
        return name;
    }

    private static DocumentBuilderFactory newSecureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
