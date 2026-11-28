import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small, bounded JSON codec for the official authentication APIs (Java 8, no dependencies). */
final class AuthJson {
    private AuthJson() { }

    static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            out.append('"');
            String text = (String) value;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\b': out.append("\\b"); break;
                    case '\f': out.append("\\f"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (c < 32) {
                            out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            out.append(c);
                        }
                }
            }
            out.append('"');
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(',');
                first = false;
                append(out, entry.getKey().toString());
                out.append(':');
                append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                append(out, item);
            }
            out.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("Unsupported JSON type");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String source) throws IOException {
        if (source == null || source.length() > 1024 * 1024) throw invalid();
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.space();
        if (parser.pos != source.length() || !(value instanceof Map)) throw invalid();
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) throws IOException {
        if (!(value instanceof Map)) throw invalid();
        return (Map<String, Object>) value;
    }

    static String string(Map<String, Object> map, String key) throws IOException {
        Object value = map.get(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw invalid();
        return (String) value;
    }

    private static IOException invalid() {
        // Never include the response: it may contain credentials.
        return new IOException("Invalid authentication JSON");
    }

    private static final class Parser {
        final String source;
        int pos;

        Parser(String source) { this.source = source; }

        void space() {
            while (pos < source.length() && " \n\r\t".indexOf(source.charAt(pos)) >= 0) pos++;
        }

        char take() throws IOException {
            if (pos >= source.length()) throw invalid();
            return source.charAt(pos++);
        }

        Object value(int depth) throws IOException {
            if (depth > 32) throw invalid();
            space();
            char c = take();
            if (c == '"') return quoted();
            if (c == '{') {
                Map<String, Object> result = new LinkedHashMap<String, Object>();
                space();
                if (pos < source.length() && source.charAt(pos) == '}') { pos++; return result; }
                while (true) {
                    space();
                    if (take() != '"') throw invalid();
                    String key = quoted();
                    space();
                    if (take() != ':' || result.containsKey(key)) throw invalid();
                    result.put(key, value(depth + 1));
                    space();
                    char end = take();
                    if (end == '}') return result;
                    if (end != ',') throw invalid();
                }
            }
            if (c == '[') {
                List<Object> result = new ArrayList<Object>();
                space();
                if (pos < source.length() && source.charAt(pos) == ']') { pos++; return result; }
                while (true) {
                    result.add(value(depth + 1));
                    space();
                    char end = take();
                    if (end == ']') return result;
                    if (end != ',') throw invalid();
                }
            }
            pos--;
            for (String literal : new String[] {"true", "false", "null"}) {
                if (source.startsWith(literal, pos)) {
                    pos += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            int start = pos;
            if (pos < source.length() && source.charAt(pos) == '-') pos++;
            if (pos >= source.length()) throw invalid();
            if (source.charAt(pos) == '0') {
                pos++;
            } else {
                int digits = pos;
                while (pos < source.length() && digit(source.charAt(pos))) pos++;
                if (digits == pos) throw invalid();
            }
            boolean decimal = false;
            if (pos < source.length() && source.charAt(pos) == '.') {
                decimal = true;
                pos++;
                int digits = pos;
                while (pos < source.length() && digit(source.charAt(pos))) pos++;
                if (digits == pos) throw invalid();
            }
            if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) pos++;
                int digits = pos;
                while (pos < source.length() && digit(source.charAt(pos))) pos++;
                if (digits == pos) throw invalid();
            }
            try {
                String number = source.substring(start, pos);
                if (!decimal) return Long.valueOf(number);
                double value = Double.parseDouble(number);
                if (Double.isInfinite(value) || Double.isNaN(value)) throw invalid();
                return Double.valueOf(value);
            } catch (NumberFormatException e) {
                throw invalid();
            }
        }

        String quoted() throws IOException {
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = take();
                if (c == '"') return out.toString();
                if (c < 32) throw invalid();
                if (c != '\\') { out.append(c); continue; }
                switch (take()) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(take(), 16);
                            if (digit < 0) throw invalid();
                            code = code * 16 + digit;
                        }
                        out.append((char) code);
                        break;
                    default: throw invalid();
                }
            }
        }

        boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}
