package jp.try0.servlet.ipblock;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link JsonBlockListSource} 用の小さな JSON パーサー。外部ライブラリに依存しない。
 *
 * <p>JSON の値は次の Java オブジェクトになる。
 * <ul>
 *   <li>オブジェクト → {@code Map<String, Object>}（キーの順序を保つ）</li>
 *   <li>配列 → {@code List<Object>}</li>
 *   <li>文字列 → {@code String}</li>
 *   <li>数値 → {@code Double}</li>
 *   <li>true / false → {@code Boolean}</li>
 *   <li>null → {@code null}</li>
 * </ul>
 *
 * <p>RFC 8259 のとおり厳密に読む。コメント、末尾のカンマ、単一引用符、引用符のないキー、
 * 先頭ゼロ（{@code 01}）、{@code NaN}、重複キー、値の後ろの余計な内容はすべてエラーになる。
 * 入力は UTF-8 として読み（先頭の BOM は読み飛ばす）、UTF-8 として不正なバイト列はエラーになる。
 *
 * <p>ネストは {@value #MAX_DEPTH} 段まで。深すぎる入力で {@link StackOverflowError} を起こさず、
 * 通常の読み込みエラーとして扱うため。
 *
 * <p>エラーはすべて、何行目の何文字目かを含む {@link IOException} で通知する。
 */
final class SimpleJson {

    /** オブジェクト・配列のネストの上限。 */
    static final int MAX_DEPTH = 100;

    private final String text;
    private int pos;

    private SimpleJson(String text) {
        this.text = text;
    }

    /** UTF-8 の JSON を読む。ストリームは閉じない。 */
    static Object parse(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("JSON を UTF-8 として読めません", e);
        }
        return parse(text);
    }

    static Object parse(String text) throws IOException {
        SimpleJson parser = new SimpleJson(text);
        if (text.startsWith("﻿")) {
            parser.pos = 1;
        }
        parser.skipWhitespace();
        Object value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error(parser.pos, "値の後ろに余計な内容があります");
        }
        return value;
    }

    /** @param depth この値を囲んでいるオブジェクト・配列の数 */
    private Object readValue(int depth) throws IOException {
        if (pos >= text.length()) {
            throw error(pos, "値がありません（空のファイルか、途中で終わっています）");
        }
        char c = text.charAt(pos);
        switch (c) {
            case '{':
                return readObject(depth + 1);
            case '[':
                return readArray(depth + 1);
            case '"':
                return readString();
            case 't':
                readLiteral("true");
                return Boolean.TRUE;
            case 'f':
                readLiteral("false");
                return Boolean.FALSE;
            case 'n':
                readLiteral("null");
                return null;
            default:
                if (c == '-' || isDigit(c)) {
                    return readNumber();
                }
                throw error(pos, "予期しない文字 '" + c + "' があります");
        }
    }

    private Map<String, Object> readObject(int depth) throws IOException {
        checkDepth(depth);
        pos++; // '{'
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error(pos, "オブジェクトのキーは \"...\" で書く必要があります");
            }
            int keyPos = pos;
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error(pos, "キーの後ろに ':' が必要です");
            }
            pos++;
            skipWhitespace();
            Object value = readValue(depth);
            if (map.containsKey(key)) {
                throw error(keyPos, "キー \"" + key + "\" が重複しています");
            }
            map.put(key, value);
            skipWhitespace();
            int c = peek();
            if (c == '}') {
                pos++;
                return map;
            }
            if (c != ',') {
                throw error(pos, "',' か '}' が必要です");
            }
            pos++;
        }
    }

    private List<Object> readArray(int depth) throws IOException {
        checkDepth(depth);
        pos++; // '['
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue(depth));
            skipWhitespace();
            int c = peek();
            if (c == ']') {
                pos++;
                return list;
            }
            if (c != ',') {
                throw error(pos, "',' か ']' が必要です");
            }
            pos++;
        }
    }

    private String readString() throws IOException {
        int start = pos;
        pos++; // '"'
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error(start, "文字列が閉じられていません");
            }
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c < 0x20) {
                throw error(pos, "文字列の中に改行やタブなどの制御文字があります（\\n や \\t と書く）");
            }
            if (c != '\\') {
                sb.append(c);
                pos++;
                continue;
            }
            if (pos + 1 >= text.length()) {
                throw error(start, "文字列が閉じられていません");
            }
            char e = text.charAt(pos + 1);
            switch (e) {
                case '"':  sb.append('"');  break;
                case '\\': sb.append('\\'); break;
                case '/':  sb.append('/');  break;
                case 'b':  sb.append('\b'); break;
                case 'f':  sb.append('\f'); break;
                case 'n':  sb.append('\n'); break;
                case 'r':  sb.append('\r'); break;
                case 't':  sb.append('\t'); break;
                case 'u':
                    sb.append(readUnicodeEscape());
                    continue; // pos は readUnicodeEscape で進めた
                default:
                    throw error(pos, "不正なエスケープ \\" + e + " があります");
            }
            pos += 2;
        }
    }

    /** pos が "\\u" を指している状態で呼ぶ。 */
    private char readUnicodeEscape() throws IOException {
        int start = pos;
        if (pos + 6 > text.length()) {
            throw error(start, "\\u の後ろには 16 進数 4 桁が必要です");
        }
        int value = 0;
        for (int i = pos + 2; i < pos + 6; i++) {
            int digit = hexValue(text.charAt(i));
            if (digit < 0) {
                throw error(start, "\\u の後ろには 16 進数 4 桁が必要です");
            }
            value = value * 16 + digit;
        }
        pos += 6;
        return (char) value;
    }

    /** 数値の文法: -? (0 | [1-9][0-9]*) (. [0-9]+)? ([eE] [+-]? [0-9]+)? */
    private Double readNumber() throws IOException {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (peek() == '0') {
            pos++;
            if (isDigit(peek())) {
                throw error(start, "数値の先頭に 0 は付けられません");
            }
        } else {
            readDigits(start);
        }
        if (peek() == '.') {
            pos++;
            readDigits(start);
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++;
            if (peek() == '+' || peek() == '-') {
                pos++;
            }
            readDigits(start);
        }
        return Double.valueOf(text.substring(start, pos));
    }

    private void readDigits(int numberStart) throws IOException {
        if (!isDigit(peek())) {
            throw error(numberStart, "数値の書き方が正しくありません");
        }
        while (isDigit(peek())) {
            pos++;
        }
    }

    private void readLiteral(String literal) throws IOException {
        if (!text.startsWith(literal, pos)) {
            throw error(pos, "書き方が正しくありません（true / false / null 以外の単語は書けません）");
        }
        pos += literal.length();
    }

    private void checkDepth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw error(pos, "ネストが深すぎます（上限 " + MAX_DEPTH + " 段）");
        }
    }

    private void skipWhitespace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            pos++;
        }
    }

    /** 現在位置の文字。末尾なら -1。 */
    private int peek() {
        return pos < text.length() ? text.charAt(pos) : -1;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** ASCII の 16 進数字だけを受け付ける（Character.digit は全角数字も受け付けてしまうため使わない）。 */
    private static int hexValue(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private IOException error(int at, String message) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < at && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int column = at - lineStart + 1;
        return new IOException("JSON の " + line + " 行 " + column + " 文字目: " + message);
    }
}
