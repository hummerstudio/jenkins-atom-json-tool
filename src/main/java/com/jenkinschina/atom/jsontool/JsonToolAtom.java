package com.jenkinschina.atom.jsontool;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON as an atom: read a value out of a file, write one, or merge the fields of another file into it.
 *
 * <p>The point is the read side: a version or an image tag that lives in {@code package.json} should reach the next step
 * as a variable, without a plugin that only pipelines can use.
 */
public class JsonToolAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String file = context.getParam("file", "");
        String operation = context.getParam("operation", "read").trim().toLowerCase(java.util.Locale.ROOT);
        if (file.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001, "file is required");
        }
        Path path = workspace.resolve(file).normalize();
        if (!path.startsWith(workspace)) {
            return AtomResult.error(ErrorType.USER, 40002, "the file must stay inside the workspace: " + file);
        }

        return switch (operation) {
            case "read" -> read(path, file, context.getParam("path", ""));
            case "write" -> write(path, file, context.getParam("path", ""), context.getParam("value", ""),
                    context.getBooleanParam("asNumber", false));
            case "merge" -> merge(path, file, context.getParam("from", ""));
            default -> AtomResult.error(ErrorType.USER, 40003,
                    "unknown operation '" + operation + "'; use read, write or merge");
        };
    }

    private AtomResult read(Path path, String file, String pointer) throws Exception {
        if (!Files.isRegularFile(path)) {
            return AtomResult.error(ErrorType.USER, 40004, "no such file: " + file);
        }
        Object root = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
        if (pointer.isBlank()) {
            // the whole document: flat keys become variables, nesting becomes a.b
            Map<String, String> flat = new LinkedHashMap<>();
            flatten("", root, flat);
            AtomResult result = AtomResult.success().data("count", String.valueOf(flat.size()));
            flat.forEach(result::data);
            return result;
        }
        Object value = MiniJson.at(root, pointer);
        if (value == null) {
            return AtomResult.error(ErrorType.USER, 40005, "no value at '" + pointer + "' in " + file);
        }
        String text = value instanceof String s ? s : MiniJson.write(value);
        return AtomResult.success()
                .data("value", text)
                .data("type", typeOf(value));
    }

    private AtomResult write(Path path, String file, String pointer, String value, boolean asNumber) throws Exception {
        if (pointer.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40006, "path is required for write");
        }
        Object root = Files.isRegularFile(path) ? MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8))
                : new LinkedHashMap<String, Object>();
        Object stored = asNumber ? parseNumber(value) : value;
        MiniJson.put(root, pointer, stored);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, MiniJson.write(root) + "\n", StandardCharsets.UTF_8);
        return AtomResult.success().data("file", file).data("path", pointer).data("value", value);
    }

    private AtomResult merge(Path path, String file, String from) throws Exception {
        if (!Files.isRegularFile(path)) {
            return AtomResult.error(ErrorType.USER, 40004, "no such file: " + file);
        }
        Path source = path.getFileSystem().getPath(from.isBlank() ? "" : from);
        Path fromPath = from.isBlank() ? null : path.getParent().resolve(source).normalize();
        if (fromPath == null || !Files.isRegularFile(fromPath)) {
            return AtomResult.error(ErrorType.USER, 40007, "no such file to merge from: " + from);
        }
        Object target = MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8));
        Object increment = MiniJson.parse(Files.readString(fromPath, StandardCharsets.UTF_8));
        int merged = MiniJson.merge(target, increment);
        Files.writeString(path, MiniJson.write(target) + "\n", StandardCharsets.UTF_8);
        return AtomResult.success().data("file", file).data("merged", String.valueOf(merged));
    }

    private static Object parseNumber(String value) {
        try {
            if (value.contains(".")) {
                return Double.parseDouble(value);
            }
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static String typeOf(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof List) {
            return "array";
        }
        return "object";
    }

    /** {@code {a:{b:1}}} becomes {@code a.b=1}; arrays keep their index ({@code a.0}). */
    private static void flatten(String prefix, Object value, Map<String, String> out) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                flatten(prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey(),
                        entry.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(prefix + "." + i, list.get(i), out);
            }
        } else {
            out.put(prefix, value == null ? "" : String.valueOf(value));
        }
    }

    /**
     * A deliberately small JSON reader/writer: the atom needs objects, arrays, strings, numbers, booleans and null, and
     * pulling a full library into every atom package is not worth it.
     */
    static final class MiniJson {

        private final String text;
        private int index;

        private MiniJson(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            MiniJson reader = new MiniJson(text);
            reader.skip();
            Object value = reader.value();
            reader.skip();
            if (reader.index < reader.text.length()) {
                throw new IllegalArgumentException("trailing content at " + reader.index);
            }
            return value;
        }

        static Object at(Object root, String pointer) {
            Object node = root;
            for (String part : pointer.split("\\.")) {
                if (node instanceof Map<?, ?> map) {
                    node = map.get(part);
                } else if (node instanceof List<?> list) {
                    int i = indexOf(part);
                    node = i >= 0 && i < list.size() ? list.get(i) : null;
                } else {
                    return null;
                }
                if (node == null) {
                    return null;
                }
            }
            return node;
        }

        @SuppressWarnings("unchecked")
        static void put(Object root, String pointer, Object value) {
            String[] parts = pointer.split("\\.");
            Object node = root;
            for (int i = 0; i < parts.length - 1; i++) {
                if (!(node instanceof Map)) {
                    throw new IllegalArgumentException("cannot descend into '" + parts[i] + "'");
                }
                Map<String, Object> map = (Map<String, Object>) node;
                Object next = map.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    map.put(parts[i], next);
                }
                node = next;
            }
            ((Map<String, Object>) node).put(parts[parts.length - 1], value);
        }

        @SuppressWarnings("unchecked")
        static int merge(Object target, Object increment) {
            if (!(target instanceof Map) || !(increment instanceof Map)) {
                return 0;
            }
            Map<String, Object> into = (Map<String, Object>) target;
            Map<String, Object> from = (Map<String, Object>) increment;
            int count = 0;
            for (Map.Entry<String, Object> entry : from.entrySet()) {
                Object existing = into.get(entry.getKey());
                if (existing instanceof Map && entry.getValue() instanceof Map) {
                    count += merge(existing, entry.getValue());
                } else {
                    into.put(entry.getKey(), entry.getValue());
                    count++;
                }
            }
            return count;
        }

        static String write(Object value) {
            StringBuilder out = new StringBuilder();
            write(value, out, 0);
            return out.toString();
        }

        private static void write(Object value, StringBuilder out, int depth) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    out.append('\n').append("  ".repeat(depth + 1));
                    out.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
                    write(entry.getValue(), out, depth + 1);
                }
                if (!map.isEmpty()) {
                    out.append('\n').append("  ".repeat(depth));
                }
                out.append('}');
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append('\n').append("  ".repeat(depth + 1));
                    write(list.get(i), out, depth + 1);
                }
                if (!list.isEmpty()) {
                    out.append('\n').append("  ".repeat(depth));
                }
                out.append(']');
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                out.append('"').append(escape(String.valueOf(value))).append('"');
            }
        }

        private Object value() {
            char c = peek();
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<>();
            expect('{');
            skip();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skip();
                String key = string();
                skip();
                expect(':');
                skip();
                map.put(key, value());
                skip();
                char c = text.charAt(index++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or } at " + (index - 1));
                }
            }
        }

        private List<Object> array() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skip();
            if (peek() == ']') {
                index++;
                return list;
            }
            while (true) {
                skip();
                list.add(value());
                skip();
                char c = text.charAt(index++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected , or ] at " + (index - 1));
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case 'n' -> out.append('\n');
                        case 't' -> out.append('\t');
                        case 'r' -> out.append('\r');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            index += 4;
                        }
                        default -> out.append(escaped);
                    }
                } else {
                    out.append(c);
                }
            }
        }

        private Object number() {
            int start = index;
            while (index < text.length() && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("unexpected '" + peek() + "' at " + start);
            }
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            return Long.parseLong(raw);
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, index)) {
                throw new IllegalArgumentException("expected " + word + " at " + index);
            }
            index += word.length();
            return value;
        }

        private void expect(char c) {
            if (text.charAt(index) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + index);
            }
            index++;
        }

        private char peek() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return text.charAt(index);
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private static int indexOf(String part) {
            try {
                return Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return -1;
            }
        }

        private static String escape(String value) {
            StringBuilder out = new StringBuilder();
            for (char c : value.toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(c);
                }
            }
            return out.toString();
        }
    }
}
