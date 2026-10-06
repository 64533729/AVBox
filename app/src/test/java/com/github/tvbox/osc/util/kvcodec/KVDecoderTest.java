package com.github.tvbox.osc.util.kvcodec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KVDecoderTest {

    private KVDecoder decoder;

    private static final Map<String, Type> TYPES = new HashMap<>();

    static {
        TYPES.put("search_history", new TypeToken<ArrayList<String>>() {
        }.getType());
        TYPES.put("subscribe_list", new TypeToken<ArrayList<String>>() {
        }.getType());
        TYPES.put("live_group_list", TypeToken.get(JsonArray.class).getType());
        TYPES.put("source_card_policy", new TypeToken<HashMap<String, String>>() {
        }.getType());
        TYPES.put("checked_sources_for_search", new TypeToken<HashMap<String, HashMap<String, String>>>() {
        }.getType());
        TYPES.put("api_url", TypeToken.get(String.class).getType());
        TYPES.put("play_type", TypeToken.get(int.class).getType());
        TYPES.put("live_group_index", TypeToken.get(int.class).getType());
    }

    @Before
    public void setUp() {
        decoder = new KVDecoder();
        decoder.setRegistry(key -> {
            Type direct = TYPES.get(key);
            if (direct != null) return direct;
            if (key.startsWith("live_group_index")) return TYPES.get("live_group_index");
            if (key.startsWith("jsRuntime_")) return TYPES.get("api_url");
            return null;
        });
    }

    @Test
    public void stringRoundTrip_keepsLiteralText() {
        String encoded = decoder.encode("hello");
        assertEquals("hello", encoded);
        assertEquals("hello", decoder.decode("api_url", encoded, ""));
    }

    @Test
    public void stringThatLooksLikeJson_isNotMistakenForComplexValue() {
        String tricky = "{\"a\":1}";
        String encoded = decoder.encode(tricky);
        assertEquals(tricky, decoder.decode("api_url", encoded, ""));
    }

    @Test
    public void primitiveRoundTrip() {
        assertEquals(Integer.valueOf(2), decoder.decode("play_type", 2, 0));
        assertEquals(Boolean.TRUE, decoder.decode("play_type", Boolean.TRUE, Boolean.FALSE));
        assertEquals(Float.valueOf(1.5f), decoder.decode("play_type", 1.5f, 0f));
        assertEquals(Long.valueOf(9L), decoder.decode("play_type", 9L, 0L));
    }

    @Test
    public void arrayList_restoresStringElementType() {
        List<String> value = new ArrayList<>();
        value.add("源A\thttp://a");
        value.add("源B\thttp://b");

        String encoded = decoder.encode(value);
        ArrayList<String> restored = decoder.decode("subscribe_list", encoded, new ArrayList<>());

        assertNotNull(restored);
        assertEquals(value, restored);
        assertTrue(restored.get(0) instanceof String);
    }

    @Test
    public void jsonArray_restoresNodeTreeNotList() {
        JsonArray value = JsonParser.parseString("[{\"group\":\"央视\"},{\"group\":\"卫视\"}]").getAsJsonArray();

        String encoded = decoder.encode(value);
        JsonArray restored = decoder.decode("live_group_list", encoded, new JsonArray());

        assertNotNull(restored);
        assertEquals(2, restored.size());
        assertEquals("央视", restored.get(0).getAsJsonObject().get("group").getAsString());
    }

    @Test
    public void flatMap_restoresStringValues() {
        HashMap<String, String> value = new HashMap<>();
        value.put("sourceA", "detail");
        String encoded = decoder.encode(value);

        HashMap<String, String> restored =
                decoder.decode("source_card_policy", encoded, new HashMap<>());

        assertNotNull(restored);
        assertEquals("detail", restored.get("sourceA"));
        assertTrue(restored.get("sourceA") instanceof String);
    }

    @Test
    public void nestedMap_registryTypeRestoresStringValues() {
        HashMap<String, HashMap<String, String>> value = new HashMap<>();
        HashMap<String, String> inner = new HashMap<>();
        inner.put("sourceA", "1");
        value.put("http://api", inner);
        String encoded = decoder.encode(value);

        KVDecoder forced = new KVDecoder();
        forced.setRegistry(key -> TYPES.get("checked_sources_for_search"));
        Object restored = forced.decode("checked_sources_for_search", encoded, null);

        assertTrue(restored instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> asMap = (Map<String, Map<String, String>>) restored;
        assertEquals("1", asMap.get("http://api").get("sourceA"));
        assertTrue(asMap.get("http://api").get("sourceA") instanceof String);
    }

    @Test
    public void nestedMap_withoutRegistry_collapsesInnerElementsToNodeTree() {
        HashMap<String, HashMap<String, String>> value = new HashMap<>();
        HashMap<String, String> inner = new HashMap<>();
        inner.put("sourceA", "1");
        value.put("http://api", inner);
        String encoded = decoder.encode(value);

        KVDecoder bare = new KVDecoder();
        Object restored = bare.decode("checked_sources_for_search", encoded, new HashMap<>());

        assertTrue(restored instanceof Map);
        Object innerValue = ((Map<?, ?>) restored).get("http://api");
        assertTrue(innerValue instanceof Map);
        assertEquals("1", ((Map<?, ?>) innerValue).get("sourceA"));
    }

    @Test
    public void callerConcreteTypeWins_forDynamicKeysOutsideRegistry() {
        String encoded = decoder.encode("payload");
        assertEquals("payload", decoder.decode("jsRuntime_abc_def", encoded, ""));
    }

    @Test
    public void searchThreadsCrashRegression_zeroUnwrapsToIntNotDefault() {
        assertEquals(Integer.valueOf(0), decoder.decode("search_threads", 0, 32));
        assertNull(decoder.decode("search_threads", null, null));
    }

    @Test
    public void unregisteredKeyWithoutDefault_fallsBackToJsonNodeTree() {
        JsonArray value = JsonParser.parseString("[{\"group\":\"央视\"}]").getAsJsonArray();

        Object restored = decoder.decode("unknown_key", decoder.encode(value), null);
        assertTrue(restored instanceof JsonArray);
        assertEquals("央视", ((JsonArray) restored).get(0).getAsJsonObject().get("group").getAsString());
    }

    @Test
    public void objectDefault_isTreatedAsNoTypeHint_notAsPassthrough() {
        JsonArray value = JsonParser.parseString("[1,2]").getAsJsonArray();
        Object restored = decoder.decode("unknown_key", decoder.encode(value), new Object());
        assertTrue("复杂值应按节点树还原,而不是返回原始字符串", restored instanceof JsonArray);
    }

    @Test
    public void unregisteredPlainTextKeyWithoutDefault_isReportedNotSilentlyDefaulted() {
        assertNull(decoder.decode("unknown_key", "plain", null));
    }

    @Test
    public void quietCopy_reportsNothing_butKeepsSameResult() {
        KVDecoder quiet = decoder.quiet();
        assertNull(quiet.decode("unknown_key", "plain", null));
        assertEquals("payload", quiet.decode("jsRuntime_abc_def", decoder.encode("payload"), ""));
    }

    @Test
    public void decodeFailure_returnsNullInsteadOfThrowing() {
        Object restored = decoder.decode("checked_sources_for_search", "\u0001json:{not-json", new HashMap<>());
        assertNull(restored);
    }

    @Test
    public void legacyPlainTextValue_isConvertedByTargetType() {
        assertEquals("硬解码", decoder.decode("api_url", "硬解码", ""));
    }

    @Test
    public void jsonNumberParsedAsDouble_isCoercedToIntTarget() {
        assertEquals(Integer.valueOf(0), decoder.decode("play_type", 0, 32));
        assertEquals(Integer.valueOf(3), decoder.decode("play_type", 3, 32));
    }

    @Test
    public void jsonNumberCoercion_coversAllNumericTargets() {
        assertEquals(Integer.valueOf(7), decoder.decode("k", 7, 0));
        assertEquals(Long.valueOf(7L), decoder.decode("k", 7, 0L));
        assertEquals(Float.valueOf(2.5f), decoder.decode("k", 2.5, 0f));
        assertEquals(Double.valueOf(2.5d), decoder.decode("k", 2.5, 0d));
    }

    @Test
    public void intTarget_outOfRangeFallsBackToDefaultInsteadOfTruncating() {
        assertNull(decoder.coerceNumber(int.class, ((long) Integer.MAX_VALUE) + 1));
        assertNull(decoder.coerceNumber(Integer.class, Double.MAX_VALUE));
    }

    @Test
    public void stringValueOnNumericTarget_isNotCoerced() {
        assertEquals("32", decoder.coerceNumber(int.class, "32"));
    }
}
