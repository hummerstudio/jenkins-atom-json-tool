package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The json-tool atom's edges: every refusal it owns, the JSON shapes the small reader has to survive (escapes, nulls,
 * empty objects and arrays, floats, exponents) and the malformed documents it must reject instead of guessing.
 *
 * <p>The parser is not on the test classpath as a public API, so each case goes through the atom the runner starts — the
 * same path a job takes.
 */
class JsonToolEdgeIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    private static final String EVERYTHING = "{\"a\": \"line\\n\\ttab \\\"q\\\" back\\\\slash \\u00e9\","
            + " \"b\": {}, \"c\": [], \"d\": [1, 2.5, true, false, null, {\"k\": \"v\"}], \"e\": -1.2e3}";

    /**
     * A flattened key of {@code output.json}'s data map — {@code field()} splits on dots, and {@code d.1} is one key
     * with a dot in it, not a nested object.
     */
    private static String data(AtomResult run, String key) {
        Object data = Json.parseObject(run.file("output.json")).get("data");
        Object node = ((Map<?, ?>) data).get(key);
        assertTrue(node instanceof Map, "no data key '" + key + "':\n" + run.file("output.json"));
        return String.valueOf(((Map<?, ?>) node).get("value"));
    }

    @Test
    @DisplayName("a document of every JSON shape is flattened: escapes decoded, nulls empty, arrays indexed")
    void readsEveryShape() throws Exception {
        AtomResult run = atom.file("all.json", EVERYTHING)
                .input("{\"operation\": \"read\", \"file\": \"all.json\", \"path\": \"\"}")
                .run();

        run.assertOk();
        assertEquals("line\n\ttab \"q\" back\\slash \u00e9", run.field("a"), "the escapes must be decoded");
        assertEquals("", data(run, "d.4"), "a null flattens to the empty string");
        assertEquals("2.5", data(run, "d.1"), "a float keeps its value");
        assertEquals("true", data(run, "d.2"));
        assertEquals("false", data(run, "d.3"));
        assertEquals("v", data(run, "d.5.k"), "an object inside an array keeps a dotted key");
        assertEquals("-1200.0", run.field("e"), "an exponent is a number, not a string");
        assertEquals("8", run.field("count"), "a, d.0-d.4, d.5.k and e are eight leaves: " + run);
    }

    @Test
    @DisplayName("reading one value reports its JSON type, and an object or array is written back as JSON")
    void reportsTheTypeOfAValue() throws Exception {
        AtomResult number = atom.file("t.json", "{\"n\": 42, \"f\": 1.5, \"b\": true, \"arr\": [1], \"obj\": {\"k\": 1}}")
                .input("{\"operation\": \"read\", \"file\": \"t.json\", \"path\": \"n\"}")
                .run();
        number.assertOk();
        assertEquals("42", number.field("value"));
        assertEquals("number", number.field("type"));

        AtomResult bool = atom.file("t.json", "{\"b\": true}")
                .input("{\"operation\": \"read\", \"file\": \"t.json\", \"path\": \"b\"}")
                .run();
        bool.assertOk();
        assertEquals("boolean", bool.field("type"));

        AtomResult array = atom.file("t.json", "{\"arr\": [1, 2]}")
                .input("{\"operation\": \"read\", \"file\": \"t.json\", \"path\": \"arr\"}")
                .run();
        array.assertOk();
        assertEquals("array", array.field("type"));

        AtomResult object = atom.file("t.json", "{\"obj\": {\"k\": 1}}")
                .input("{\"operation\": \"read\", \"file\": \"t.json\", \"path\": \"obj\"}")
                .run();
        object.assertOk();
        assertEquals("object", object.field("type"));
        assertTrue(object.field("value").contains("\"k\": 1"),
                "an object value is written back as JSON: " + object.field("value"));
    }

    @Test
    @DisplayName("an array pointer is followed by index, and a non-numeric index or one past the end is 'no value'")
    void followsArrayPointers() throws Exception {
        AtomResult second = atom.file("arr.json", "{\"keywords\": [\"a\", \"b\"]}")
                .input("{\"operation\": \"read\", \"file\": \"arr.json\", \"path\": \"keywords.1\"}")
                .run();
        second.assertOk();
        assertEquals("b", second.field("value"));

        AtomResult past = atom.file("arr.json", "{\"keywords\": [\"a\"]}")
                .input("{\"operation\": \"read\", \"file\": \"arr.json\", \"path\": \"keywords.9\"}")
                .run();
        past.assertRefused();
        assertEquals("40005", past.field("errorCode"));

        AtomResult notAnIndex = atom.file("arr.json", "{\"keywords\": [\"a\"]}")
                .input("{\"operation\": \"read\", \"file\": \"arr.json\", \"path\": \"keywords.first\"}")
                .run();
        notAnIndex.assertRefused();
        assertEquals("40005", notAnIndex.field("errorCode"));

        AtomResult throughAScalar = atom.file("arr.json", "{\"name\": \"parcel\"}")
                .input("{\"operation\": \"read\", \"file\": \"arr.json\", \"path\": \"name.deeper\"}")
                .run();
        throughAScalar.assertRefused();
        assertEquals("40005", throughAScalar.field("errorCode"));
    }

    @Test
    @DisplayName("rewriting a document keeps its arrays, nulls and escapes intact")
    void rewritesEveryShape() throws Exception {
        AtomResult run = atom.file("all.json", EVERYTHING)
                .input("{\"operation\": \"write\", \"file\": \"all.json\", \"path\": \"added\", \"value\": \"yes\"}")
                .run();

        run.assertOk();
        String written = run.file("all.json");
        assertTrue(written.contains("\"added\": \"yes\""), "the new key is missing:\n" + written);
        assertTrue(written.contains("\\n\\t"), "the escapes must be written back as escapes:\n" + written);
        assertTrue(written.contains("null"), "null must survive the rewrite:\n" + written);
        AtomResult reread = atom.file("all.json", written)
                .input("{\"operation\": \"read\", \"file\": \"all.json\", \"path\": \"d.2\"}")
                .run();
        reread.assertOk();
        assertEquals("true", reread.field("value"));
    }

    @Test
    @DisplayName("asNumber stores a decimal as a number, and a value that is not numeric stays a string")
    void writesNumbersOnlyWhenTheyAreNumbers() throws Exception {
        AtomResult decimal = atom.input("{\"operation\": \"write\", \"file\": \"n.json\", \"path\": \"ratio\", "
                        + "\"value\": \"2.5\", \"asNumber\": \"true\"}")
                .run();
        decimal.assertOk();
        assertTrue(decimal.file("n.json").contains("\"ratio\": 2.5"), decimal.file("n.json"));

        AtomResult text = atom.input("{\"operation\": \"write\", \"file\": \"n.json\", \"path\": \"label\", "
                        + "\"value\": \"not-a-number\", \"asNumber\": \"true\"}")
                .run();
        text.assertOk();
        assertTrue(text.file("n.json").contains("\"label\": \"not-a-number\""), text.file("n.json"));

        AtomResult blank = atom.input("{\"operation\": \"write\", \"file\": \"n.json\", \"path\": \"other\", "
                        + "\"value\": \"plain\"}")
                .run();
        blank.assertOk();
        assertTrue(blank.file("n.json").contains("\"other\": \"plain\""), blank.file("n.json"));
    }

    @Test
    @DisplayName("merge counts what it replaced, merges nested objects and refuses a source that is not there")
    void mergesAndRefusesBadSources() throws Exception {
        AtomResult nested = atom.file("target.json", "{\"nested\": {\"keep\": 1, \"replace\": 1}}")
                .file("extra.json", "{\"nested\": {\"replace\": 2}, \"b\": 3}")
                .input("{\"operation\": \"merge\", \"file\": \"target.json\", \"from\": \"extra.json\"}")
                .run();
        nested.assertOk();
        assertEquals("2", nested.field("merged"), "replace and b are two merged keys: " + nested);

        AtomResult missingTarget = atom.input("{\"operation\": \"merge\", \"file\": \"gone.json\", "
                        + "\"from\": \"extra.json\"}")
                .run();
        missingTarget.assertRefused();
        assertEquals("40004", missingTarget.field("errorCode"));

        AtomResult noSource = atom.file("target.json", "{\"a\": 1}")
                .input("{\"operation\": \"merge\", \"file\": \"target.json\", \"from\": \"\"}")
                .run();
        noSource.assertRefused();
        assertEquals("40007", noSource.field("errorCode"));

        AtomResult absentSource = atom.file("target.json", "{\"a\": 1}")
                .input("{\"operation\": \"merge\", \"file\": \"target.json\", \"from\": \"nope.json\"}")
                .run();
        absentSource.assertRefused();
        assertEquals("40007", absentSource.field("errorCode"));

        AtomResult arrayTarget = atom.file("target.json", "[1, 2]")
                .file("extra.json", "{\"a\": 1}")
                .input("{\"operation\": \"merge\", \"file\": \"target.json\", \"from\": \"extra.json\"}")
                .run();
        arrayTarget.assertOk();
        assertEquals("0", arrayTarget.field("merged"), "merging into an array changes nothing: " + arrayTarget);
    }

    @Test
    @DisplayName("the input mistakes are refused by code, before any file is touched")
    void refusesBadInput() throws Exception {
        AtomResult noFile = atom.input("{\"operation\": \"read\", \"file\": \"\"}").run();
        noFile.assertRefused();
        assertEquals("40001", noFile.field("errorCode"));
        assertTrue(noFile.field("message").contains("file is required"), noFile.field("message"));

        AtomResult outside = atom.input("{\"operation\": \"read\", \"file\": \"../outside.json\"}").run();
        outside.assertRefused();
        assertEquals("40002", outside.field("errorCode"));

        AtomResult unknown = atom.input("{\"operation\": \"delete\", \"file\": \"x.json\"}").run();
        unknown.assertRefused();
        assertEquals("40003", unknown.field("errorCode"));
        assertTrue(unknown.field("message").contains("unknown operation 'delete'"), unknown.field("message"));

        AtomResult missing = atom.input("{\"operation\": \"read\", \"file\": \"nope.json\"}").run();
        missing.assertRefused();
        assertEquals("40004", missing.field("errorCode"));

        AtomResult writeWithoutPath = atom.input("{\"operation\": \"write\", \"file\": \"x.json\", "
                        + "\"value\": \"1\"}")
                .run();
        writeWithoutPath.assertRefused();
        assertEquals("40006", writeWithoutPath.field("errorCode"));
    }

    @Test
    @DisplayName("writing a.b where a is a string replaces it with an object: put builds the path it needs")
    void writeReplacesAScalarOnThePath() throws Exception {
        AtomResult run = atom.file("x.json", "{\"a\": \"already a string\"}")
                .input("{\"operation\": \"write\", \"file\": \"x.json\", \"path\": \"a.b\", \"value\": \"2\"}")
                .run();

        run.assertOk();
        assertEquals("2", run.field("value"));
        assertTrue(run.file("x.json").contains("\"b\": \"2\""), "the new nested key is missing:\n" + run.file("x.json"));
    }

    @Test
    @DisplayName("a document that is not JSON is refused with the parser's reason, never half-read")
    void refusesMalformedJson() throws Exception {
        assertParserRejects("{\"a\": 1} trailing", "trailing content");
        assertParserRejects("{\"a\": 1 \"b\": 2}", "expected , or }");
        assertParserRejects("{\"a\": [1 2]}", "expected , or ]");
        assertParserRejects("{\"a\": }", "unexpected '}'");
        assertParserRejects("{\"a\": tru}", "expected true");
        assertParserRejects("{a: 1}", "expected '\"'");
        assertParserRejects("{\"a\": ", "unexpected end of JSON");
        assertParserRejects("{\"a\": 1e}", "For input string");
    }

    /** Runs a read over a file with the given content and asserts the parser's complaint is what the job sees. */
    private void assertParserRejects(String document, String reason) throws Exception {
        AtomResult run = atom.file("bad.json", document)
                .input("{\"operation\": \"read\", \"file\": \"bad.json\"}")
                .run();

        run.assertRefused();
        assertTrue(run.field("message").contains(reason),
                "expected '" + reason + "' for " + document + ", got: " + run.field("message"));
    }
}
