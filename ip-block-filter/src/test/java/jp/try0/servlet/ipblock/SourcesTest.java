package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourcesTest {

    @TempDir
    Path tmp;

    // ---- XML ----

    @Test
    void xmlReadsIpElements() throws Exception {
        Path file = write("list.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<ip-block-list>\n"
                + "  <ip note=\"攻撃元\">192.0.2.10</ip>\n"
                + "  <ip> 198.51.100.0/24 </ip>\n"
                + "  <ip></ip>\n"
                + "  <!-- <ip>203.0.113.1</ip> -->\n"
                + "</ip-block-list>\n");
        assertEquals(List.of("192.0.2.10", "198.51.100.0/24"), XmlBlockListSource.ofFile(file).load());
    }

    @Test
    void xmlRejectsWrongRootElement() throws Exception {
        Path file = write("list.xml", "<blocked><ip>192.0.2.10</ip></blocked>");
        assertThrows(Exception.class, () -> XmlBlockListSource.ofFile(file).load());
    }

    @Test
    void xmlRejectsDoctypeToPreventXxe() throws Exception {
        Path secret = write("secret.txt", "192.0.2.99");
        Path file = write("list.xml", "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE ip-block-list [<!ENTITY e SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<ip-block-list><ip>&e;</ip></ip-block-list>");
        assertThrows(Exception.class, () -> XmlBlockListSource.ofFile(file).load());
    }

    @Test
    void xmlRejectsBrokenFile() throws Exception {
        Path file = write("list.xml", "<ip-block-list><ip>192.0.2.10</ip>");
        assertThrows(Exception.class, () -> XmlBlockListSource.ofFile(file).load());
    }

    // ---- JSON ----

    @Test
    void jsonAcceptsArrayRoot() throws Exception {
        Path file = write("list.json", "[\"192.0.2.10\", \" 2001:db8::/32 \", \"\"]");
        assertEquals(List.of("192.0.2.10", "2001:db8::/32"), JsonBlockListSource.ofFile(file).load());
    }

    @Test
    void jsonAcceptsObjectRootWithStringAndObjectEntries() throws Exception {
        Path file = write("list.json", "{\n"
                + "  \"ips\": [\n"
                + "    \"192.0.2.10\",\n"
                + "    { \"ip\": \"198.51.100.0/24\", \"note\": \"無視される\" }\n"
                + "  ]\n"
                + "}");
        assertEquals(List.of("192.0.2.10", "198.51.100.0/24"), JsonBlockListSource.ofFile(file).load());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "{\"ips\": \"192.0.2.10\"}", "{\"other\": []}", "[1]", "[{\"note\": \"x\"}]"})
    void jsonRejectsUnexpectedContent(String content) throws Exception {
        Path file = write("list.json", content);
        assertThrows(Exception.class, () -> JsonBlockListSource.ofFile(file).load());
    }

    @Test
    void classpathResourceCanBeLoaded() throws Exception {
        assertEquals(List.of("203.0.113.0/24"), JsonBlockListSource.ofClasspath("ip-block-list-test.json").load());
    }

    @Test
    void missingFileThrows() {
        assertThrows(Exception.class, () -> JsonBlockListSource.ofFile(tmp.resolve("none.json")).load());
    }

    // ---- DB ----

    @Test
    void jdbcReadsFirstColumnAndSkipsNulls() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:sources-test;DB_CLOSE_DELAY=-1");
        try (Connection con = ds.getConnection(); Statement st = con.createStatement()) {
            st.execute("CREATE TABLE blocked_ip (ip VARCHAR(64), enabled BOOLEAN)");
            st.execute("INSERT INTO blocked_ip VALUES ('192.0.2.10', TRUE), (' 198.51.100.0/24 ', TRUE),"
                    + " (NULL, TRUE), ('203.0.113.1', FALSE)");
        }
        JdbcBlockListSource source =
                new JdbcBlockListSource(ds, "SELECT ip FROM blocked_ip WHERE enabled = TRUE");
        assertEquals(Set.of("192.0.2.10", "198.51.100.0/24"), new HashSet<>(source.load()));
    }

    // ---- Java API ----

    @Test
    void inMemoryAddsAtomically() {
        InMemoryBlockListSource source = new InMemoryBlockListSource().add("192.0.2.10");
        assertThrows(IllegalArgumentException.class, () -> source.add("198.51.100.1", "not-an-ip"));
        assertEquals(List.of("192.0.2.10"), source.load());

        source.add(" 198.51.100.0/24 ");
        assertEquals(List.of("192.0.2.10", "198.51.100.0/24"), source.load());

        source.remove("192.0.2.10");
        assertEquals(List.of("198.51.100.0/24"), source.load());

        source.replaceAll(List.of("2001:db8::1"));
        assertEquals(List.of("2001:db8::1"), source.load());
    }

    @Test
    void inMemoryRemoveIgnoresNotation() {
        InMemoryBlockListSource source = new InMemoryBlockListSource().add("2001:DB8::1", "192.0.2.10");
        assertTrue(source.remove("2001:db8:0:0:0:0:0:1"));
        assertTrue(source.remove("192.0.2.10/32"));
        assertEquals(List.of(), source.load());
    }

    @Test
    void inMemoryKeepsFirstNotationForSameAddress() {
        InMemoryBlockListSource source = new InMemoryBlockListSource().add("2001:DB8::1");
        source.add("2001:db8::1", "::ffff:192.0.2.10");
        source.add("192.0.2.10/32");
        assertEquals(List.of("2001:DB8::1", "::ffff:192.0.2.10"), source.load());
    }

    @Test
    void inMemoryRemoveOfInvalidValueReturnsFalse() {
        InMemoryBlockListSource source = new InMemoryBlockListSource().add("192.0.2.10");
        assertFalse(source.remove("not-an-ip"));
        assertEquals(List.of("192.0.2.10"), source.load());
    }

    private Path write(String name, String content) throws Exception {
        return Files.writeString(tmp.resolve(name), content);
    }
}
