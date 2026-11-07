package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The json-tool atom, started the way the runner starts it.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made: a nested read, the flattening that reaches the next
 * step, a typed write, a merge, and the refusal of a pointer that is not there.
 */
class JsonToolIT {

    private static final String PACKAGE_JSON = "{\"name\": \"parcel\", \"version\": \"2.4.1\","
            + " \"build\": {\"image\": \"registry/parcel:2.4.1\"},"
            + " \"keywords\": [\"a\", \"b\"], \"private\": true, \"count\": 3}";

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    /** A flattened key of {@code output.json}'s data map — {@code field()} splits on dots, the key does not. */
    private static String data(AtomResult run, String key) {
        Object data = Json.parseObject(run.file("output.json")).get("data");
        assertInstanceOf(Map.class, data, "output.json:\n" + run.file("output.json"));
        Object node = ((Map<?, ?>) data).get(key);
        assertInstanceOf(Map.class, node, "no data key '" + key + "':\n" + run.file("output.json"));
        return String.valueOf(((Map<?, ?>) node).get("value"));
    }

    @Test
    @DisplayName("reading a nested path yields the value and its type")
    void readsOneValue() throws Exception {
        AtomResult run = atom.file("package.json", PACKAGE_JSON)
                .input("{\"operation\": \"read\", \"file\": \"package.json\", \"path\": \"build.image\"}")
                .run();

        run.assertOk();
        assertEquals("registry/parcel:2.4.1", run.field("value"));
        assertEquals("string", run.field("type"));
    }

    @Test
    @DisplayName("reading the whole document flattens nested keys and keeps array indexes")
    void flattensTheDocument() throws Exception {
        AtomResult run = atom.file("package.json", PACKAGE_JSON)
                .input("{\"operation\": \"read\", \"file\": \"package.json\"}")
                .run();

        run.assertOk();
        assertEquals("parcel", run.field("name"));
        assertEquals("2.4.1", run.field("version"));
        assertEquals("registry/parcel:2.4.1", data(run, "build.image"));
        assertEquals("a", data(run, "keywords.0"));
        assertEquals("true", run.field("private"));
        assertEquals("3", run.field("count"));
    }

    @Test
    @DisplayName("write creates the file and the intermediate objects, asNumber stores a number")
    void writesATypedValue() throws Exception {
        AtomResult run = atom.input("{\"operation\": \"write\", \"file\": \"meta/info.json\", \"path\": \"build.number\","
                        + " \"value\": \"42\", \"asNumber\": \"true\"}")
                .run();

        run.assertOk();
        Map<String, Object> doc = Json.parseObject(run.file("meta/info.json"));
        Map<?, ?> build = assertInstanceOf(Map.class, doc.get("build"), "doc: " + doc);
        assertEquals(42, ((Number) build.get("number")).intValue(), "asNumber must store a number: " + doc);
    }

    @Test
    @DisplayName("merge lets the incoming file win and merges nested objects")
    void merges() throws Exception {
        AtomResult run = atom.file("target.json", "{\"a\": 1, \"nested\": {\"keep\": 1, \"replace\": 1}}")
                .file("extra.json", "{\"nested\": {\"replace\": 2}, \"b\": 3}")
                .input("{\"operation\": \"merge\", \"file\": \"target.json\", \"from\": \"extra.json\"}")
                .run();

        run.assertOk();
        assertEquals("{\"a\":1,\"nested\":{\"keep\":1,\"replace\":2},\"b\":3}", Json.write(Json.parse(run.file("target.json"))));
    }

    @Test
    @DisplayName("a missing pointer is an error, not an empty string")
    void refusesAMissingPointer() throws Exception {
        AtomResult run = atom.file("x.json", "{\"a\": 1}")
                .input("{\"operation\": \"read\", \"file\": \"x.json\", \"path\": \"nope\"}")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no value at 'nope'"), "message:\n" + run.file("output.json"));
    }

    @Test
    @DisplayName("a dotted key also gets an upper-snake alias for the next step")
    void emitsVariableAliases() throws Exception {
        AtomResult run = atom.file("pkg.json", "{\"build\": {\"image\": \"node:22\", \"number\": 7}}")
                .input("{\"file\": \"pkg.json\", \"pointer\": \"\", \"mode\": \"read\"}")
                .run();

        run.assertOk();
        assertEquals("node:22", run.field("BUILD_IMAGE"), "BUILD_IMAGE alias missing");
        assertEquals("7", run.field("BUILD_NUMBER"), "BUILD_NUMBER alias missing");
    }
}
