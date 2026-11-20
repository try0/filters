package jp.try0.servlet.ipblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SimpleJsonTest {

    @Test
    void parsesAllValueTypes() throws Exception {
        Object parsed = SimpleJson.parse(
                " {\"a\" : [0, -1, 2.5, -3e2, 4E+1, 5e-1, true, false, null, \"x\"],\r\n\t\"b\":{}, \"c\":[]} ");

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", Arrays.asList(0.0, -1.0, 2.5, -300.0, 40.0, 0.5, true, false, null, "x"));
        expected.put("b", Map.of());
        expected.put("c", List.of());
        assertEquals(expected, parsed);
    }

    @Test
    void keepsKeyOrder() throws Exception {
        Map<?, ?> map = (Map<?, ?>) SimpleJson.parse("{\"z\":1,\"a\":2,\"m\":3}");
        assertEquals(List.of("z", "a", "m"), List.copyOf(map.keySet()));
    }

    @Test
    void decodesEscapes() throws Exception {
        String json = "\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t \\u3042 \\uD83D\\uDE00 \\u00e9\"";
        assertEquals("\" \\ / \b \f \n \r \t あ 😀 é", SimpleJson.parse(json));
    }

    @Test
    void readsUtf8WithAndWithoutBom() throws Exception {
        byte[] body = "[\"日本語\"]".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertEquals(List.of("日本語"), SimpleJson.parse(new ByteArrayInputStream(body)));
        assertEquals(List.of("日本語"), SimpleJson.parse(new ByteArrayInputStream(withBom)));
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] bytes = {'[', '"', (byte) 0xFF, '"', ']'};
        assertThrows(IOException.class, () -> SimpleJson.parse(new ByteArrayInputStream(bytes)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "\uFEFF",
            "{", "[", "[1", "{\"a\"", "{\"a\":", "{\"a\":1",
            "[1,]", "{\"a\":1,}", "[,1]", "[1 2]", "{\"a\" 1}", "{\"a\":1 \"b\":2}",
            "{a:1}", "{'a':1}", "['a']", "{1:2}",
            "[01]", "[-01]", "[1.]", "[.5]", "[-]", "[+1]", "[1e]", "[1e+]", "[0x10]",
            "[NaN]", "[Infinity]", "[-Infinity]", "[tru]", "[nul]", "[True]", "[undefined]",
            "\"unterminated", "[\"a\\\"]", "[\"a\nb\"]", "[\"a\tb\"]",
            "[\"\\x\"]", "[\"\\u12\"]", "[\"\\u12G4\"]", "[\"\\u１２３４\"]",
            "{\"a\":1,\"a\":2}",
            "[] x", "[][]", "1 2", "// comment\n[]", "[] // comment", "/* c */ []", "[1] ,",
    })
    void rejectsInvalidJson(String json) {
        assertThrows(IOException.class, () -> SimpleJson.parse(json));
    }

    @Test
    void acceptsTopLevelScalars() throws Exception {
        assertEquals("a", SimpleJson.parse("\"a\""));
        assertEquals(1.0, SimpleJson.parse("1"));
        assertEquals(Boolean.TRUE, SimpleJson.parse("true"));
        assertEquals(null, SimpleJson.parse(" null "));
    }

    @Test
    void nestingUpToLimitIsAccepted() throws Exception {
        String json = "[".repeat(SimpleJson.MAX_DEPTH) + "]".repeat(SimpleJson.MAX_DEPTH);
        SimpleJson.parse(json);
    }

    @Test
    void nestingBeyondLimitIsRejected() {
        String json = "[".repeat(SimpleJson.MAX_DEPTH + 1) + "]".repeat(SimpleJson.MAX_DEPTH + 1);
        assertThrows(IOException.class, () -> SimpleJson.parse(json));
    }

    @Test
    void veryDeepInputFailsWithIoExceptionNotStackOverflow() {
        String json = "[".repeat(1_000_000) + "{\"a\":".repeat(1_000_000);
        assertThrows(IOException.class, () -> SimpleJson.parse(json));
    }

    @Test
    void errorMessageHasLineAndColumn() {
        IOException e = assertThrows(IOException.class, () -> SimpleJson.parse("[\n  \"a\",\n  x\n]"));
        assertTrue(e.getMessage().contains("3 行 3 文字目"), e.getMessage());
    }

    @Test
    void duplicateKeyIsReportedAtTheSecondKey() {
        IOException e = assertThrows(IOException.class, () -> SimpleJson.parse("{\"ips\":[],\n\"ips\":[]}"));
        assertTrue(e.getMessage().contains("2 行 1 文字目"), e.getMessage());
        assertTrue(e.getMessage().contains("\"ips\""), e.getMessage());
    }
}
