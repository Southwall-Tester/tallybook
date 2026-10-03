package dev.tallybook.core;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** Bounded strict JSON validation before the platform's more permissive parser. */
final class JsonInput {
    private final String text;
    private int position;

    private JsonInput(String text) { this.text = text; }

    static JSONObject object(String text) throws Exception {
        return object(text, WechatBillParser.MAX_INPUT_BYTES);
    }

    static JSONObject object(String text, int maxBytes) throws Exception {
        if (text == null || text.length() > maxBytes
                || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("Invalid JSON length");
        }
        JsonInput validator = new JsonInput(text);
        validator.space();
        if (!validator.at('{')) throw new IllegalArgumentException("Expected JSON object");
        validator.value(0);
        validator.space();
        if (validator.position != text.length()) throw new IllegalArgumentException("Trailing JSON data");
        return (JSONObject) new JSONTokener(text).nextValue();
    }

    private void value(int depth) throws Exception {
        space();
        if (position >= text.length()) throw invalid();
        if (at('{')) {
            if (depth >= 32) throw invalid();
            position++;
            space();
            if (take('}')) return;
            Set<String> keys = new HashSet<>();
            do {
                space();
                int start = position;
                string();
                String key = (String) new JSONTokener(text.substring(start, position)).nextValue();
                if (!keys.add(key)) throw invalid();
                space();
                require(':');
                value(depth + 1);
                space();
                if (take('}')) return;
                require(',');
            } while (true);
        }
        if (at('[')) {
            if (depth >= 32) throw invalid();
            position++;
            space();
            if (take(']')) return;
            do {
                value(depth + 1);
                space();
                if (take(']')) return;
                require(',');
            } while (true);
        }
        if (at('"')) { string(); return; }
        if (text.startsWith("true", position)) { position += 4; return; }
        if (text.startsWith("false", position)) { position += 5; return; }
        if (text.startsWith("null", position)) { position += 4; return; }
        number();
    }

    private void string() {
        require('"');
        while (position < text.length()) {
            char c = text.charAt(position++);
            if (c == '"') return;
            if (c < 0x20) throw invalid();
            if (c == '\\') {
                if (position >= text.length()) throw invalid();
                char escape = text.charAt(position++);
                if (escape == 'u') {
                    for (int i = 0; i < 4; i++) {
                        if (position >= text.length()) throw invalid();
                        char hex = text.charAt(position++);
                        if (!((hex >= '0' && hex <= '9') || (hex >= 'a' && hex <= 'f') || (hex >= 'A' && hex <= 'F'))) throw invalid();
                    }
                } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw invalid();
            }
        }
        throw invalid();
    }

    private void number() {
        int start = position;
        take('-');
        if (!take('0')) {
            if (position >= text.length() || text.charAt(position) < '1' || text.charAt(position) > '9') throw invalid();
            digits();
        }
        if (take('.')) {
            int before = position;
            digits();
            if (before == position) throw invalid();
        }
        if (take('e') || take('E')) {
            if (!take('+')) take('-');
            int before = position;
            digits();
            if (before == position) throw invalid();
        }
        if (position - start > 64) throw invalid();
    }

    private void digits() {
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') position++;
    }

    private void space() {
        while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) position++;
    }

    private boolean at(char c) { return position < text.length() && text.charAt(position) == c; }
    private boolean take(char c) {
        if (!at(c)) return false;
        position++;
        return true;
    }
    private void require(char c) { if (!take(c)) throw invalid(); }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid or excessively nested JSON"); }
}
