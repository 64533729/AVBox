package com.github.tvbox.osc.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.github.tvbox.osc.bean.LiveSettingItem;
import com.github.tvbox.osc.bean.SourceBean;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ConfigParserTest {

    private static final Gson gson = new Gson();

    private static final Supplier<String> LOCAL = () -> "http://192.168.1.9:9978/";
    private static final Supplier<String> NEVER = () -> {
        throw new AssertionError("非 clan://localhost/ 地址不应该去取本机服务基址");
    };

    private static JsonObject json(String text) {
        return gson.fromJson(text, JsonObject.class);
    }

    private static JsonArray jsonArray(String text) {
        return gson.fromJson(text, JsonArray.class);
    }

    @Test
    public void trimJsonObject_keepsOnlyObjectBody() {
        assertEquals("{\"a\":1}", ConfigParser.trimJsonObject("// 版权说明\n{\"a\":1}\n// 尾巴"));
        assertEquals("{\"a\":1}", ConfigParser.trimJsonObject("  {\"a\":1}  "));
        assertEquals("没有花括号", ConfigParser.trimJsonObject("没有花括号"));
        assertEquals("", ConfigParser.trimJsonObject(null));
    }

    @Test
    public void trimJsonObject_keepsRawTextWhenBracesReversed() {
        assertEquals("} {", ConfigParser.trimJsonObject("} {"));
    }

    @Test
    public void isLiveJsonContent_detectsBomAndText() {
        assertTrue(ConfigParser.isLiveJsonContent("{\"lives\":[]}"));
        assertTrue(ConfigParser.isLiveJsonContent("\ufeff {\"lives\":[]}"));
        assertFalse(ConfigParser.isLiveJsonContent("#EXTM3U\nCCTV1,http://a/1"));
        assertFalse(ConfigParser.isLiveJsonContent("   "));
        assertFalse(ConfigParser.isLiveJsonContent(null));
    }

    @Test
    public void extractQuotedAttr_readsValueAndToleratesMalformed() {
        String line = "#EXTM3U url-tvg=\" http://a/e.xml \" tvg-url=\"http://b/e.xml\"";
        assertEquals("http://a/e.xml", ConfigParser.extractQuotedAttr(line, "url-tvg"));
        assertEquals("http://b/e.xml", ConfigParser.extractQuotedAttr(line, "tvg-url"));
        assertEquals("", ConfigParser.extractQuotedAttr(line, "x-tvg-url"));
        assertEquals("", ConfigParser.extractQuotedAttr("#EXTM3U x-tvg-url=\"http://a/e.xml", "x-tvg-url"));
    }

    @Test
    public void extractQuotedAttr_tvgUrlAlsoMatchesInsideXTvgUrl() {
        String line = "#EXTM3U x-tvg-url=\"http://a/e.xml\" tvg-url=\"http://b/e.xml\"";
        assertEquals("http://a/e.xml", ConfigParser.extractQuotedAttr(line, "tvg-url"));
    }

    @Test
    public void extractLiveTextEpg_triesThreeAttributeNamesInOrder() {
        assertEquals("http://a/e.xml",
                ConfigParser.extractLiveTextEpg("#EXTM3U x-tvg-url=\"http://a/e.xml\"\nCCTV1,http://a/1"));
        assertEquals("http://b/e.xml", ConfigParser.extractLiveTextEpg("#EXTM3U tvg-url=\"http://b/e.xml\""));
        assertEquals("http://c/e.xml", ConfigParser.extractLiveTextEpg("#EXTM3U url-tvg=\"http://c/e.xml\""));
        assertEquals("http://a/e.xml", ConfigParser.extractLiveTextEpg(
                "#EXTM3U x-tvg-url=\"http://a/e.xml\" tvg-url=\"http://b/e.xml\""));
    }

    @Test
    public void extractLiveTextEpg_handlesBomCrlfAndMissing() {
        assertEquals("http://a/e.xml", ConfigParser.extractLiveTextEpg("\ufeff#EXTM3U x-tvg-url=\"http://a/e.xml\""));
        assertEquals("http://a/e.xml",
                ConfigParser.extractLiveTextEpg("CCTV1,http://a/1\r\n#EXTM3U x-tvg-url=\"http://a/e.xml\"\r\n"));
        assertEquals("", ConfigParser.extractLiveTextEpg("#EXTM3U\nCCTV1,http://a/1"));
        assertEquals("", ConfigParser.extractLiveTextEpg(null));
    }

    @Test
    public void parseSites_buildsBeansAndSkipsIncomplete() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":["
                + "{\"key\":\"csp_A\",\"name\":\"站点A\",\"type\":3,\"api\":\"http://a/api\",\"ext\":\"/path/ext\","
                + "\"icon\":\"http://a/i.png\",\"categories\":[\"电影\",\"剧集\"],\"searchable\":0},"
                + "{\"key\":\"no_api\",\"type\":1},"
                + "{\"type\":1,\"api\":\"http://c/api\"},"
                + "{\"key\":\"py_B\",\"type\":1,\"api\":\"http://b/api\"}]}"));

        assertEquals(2, sites.size());
        SourceBean a = sites.get(0);
        assertEquals("csp_A", a.getKey());
        assertEquals("站点A", a.getName());
        assertEquals(3, a.getType());
        assertEquals("http://a/api", a.getApi());
        assertEquals("/path/ext", a.getExt());
        assertEquals("http://a/i.png", a.getIcon());
        assertEquals(2, a.getCategories().size());
        assertFalse(a.isSearchable());
    }

    @Test
    public void parseSites_appliesDefaultsAndPyFilterable() {
        List<SourceBean> sites = ConfigParser.parseSites(json(
                "{\"sites\":[{\"key\":\"py_B\",\"type\":1,\"api\":\"http://b/api\"}]}"));

        assertEquals(1, sites.size());
        SourceBean b = sites.get(0);
        assertEquals("py_B", b.getName());
        assertTrue(b.isSearchable());
        assertTrue(b.isQuickSearch());
        assertTrue(b.isChangeable());
        assertEquals(1, b.getFilterable());
        assertEquals(-1, b.getPlayerType());
        assertEquals(0, b.getTimeout());
        assertEquals("", b.getJar());
        assertEquals("", b.getStyle());
        assertEquals("", b.getClickSelector());
    }

    @Test
    public void parseSites_keepsConfigOrder() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":["
                + "{\"key\":\"b\",\"type\":1,\"api\":\"http://b\"},"
                + "{\"key\":\"a\",\"type\":1,\"api\":\"http://a\"}]}"));
        assertEquals("b", sites.get(0).getKey());
        assertEquals("a", sites.get(1).getKey());
    }

    @Test
    public void parseSites_readsHideIndexsDanmakuAndHeader() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":["
                + "{\"key\":\"a\",\"type\":1,\"api\":\"http://a\",\"hide\":1,\"indexs\":1,\"danmaku\":0,"
                + "\"header\":{\"User-Agent\":\"ua\",\"Referer\":\"http://a/\",\"num\":5}},"
                + "{\"key\":\"b\",\"type\":1,\"api\":\"http://b\",\"header\":\"not-an-object\"}]}"));

        SourceBean a = sites.get(0);
        assertTrue(a.isHidden());
        assertTrue(a.isIndexSource());
        assertFalse(a.isDanmakuEnabled());
        assertEquals(3, a.getHeader().size());
        assertEquals("ua", a.getHeader().get("User-Agent"));
        assertEquals("5", a.getHeader().get("num"));

        SourceBean b = sites.get(1);
        assertFalse(b.isHidden());
        assertFalse(b.isIndexSource());
        assertTrue(b.isDanmakuEnabled());
        assertTrue(b.getHeader().isEmpty());
    }

    @Test
    public void parseSites_dropsIllegalHeaders() {
        List<SourceBean> sites = ConfigParser.parseSites(json("{\"sites\":[{\"key\":\"a\",\"type\":1,\"api\":\"http://a\","
                + "\"header\":{\"Referer\":\"http://a/\",\"中文名\":\"x\",\"X-Cn\":\"中文值\",\"\":\"v\"}}]}"));

        Map<String, String> header = sites.get(0).getHeader();
        assertEquals(1, header.size());
        assertEquals("http://a/", header.get("Referer"));
    }

    @Test
    public void parseApiCollection_acceptsObjectsAndPrimitives() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "{\"urls\":[{\"name\":\"线路1\",\"url\":\"http://a/1\"},{\"url\":\"http://b/2\"},\"http://c/3\"]}");

        assertEquals(3, lines.size());
        assertEquals("线路1\thttp://a/1", lines.get(0));
        assertEquals("http://b/2\thttp://b/2", lines.get(1));
        assertEquals("http://c/3\thttp://c/3", lines.get(2));
    }

    @Test
    public void parseApiCollection_usesApiFieldWhenUrlMissing() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "{\"urls\":[{\"name\":\"x\",\"api\":\"http://d/4\"},{\"name\":\"empty\",\"url\":\"\"}]}");
        assertEquals(1, lines.size());
        assertEquals("x\thttp://d/4", lines.get(0));
    }

    @Test
    public void parseApiCollection_toleratesSurroundingText() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "说明文字\n{\"urls\":[\"http://c/3\"]}\n尾巴");
        assertEquals(1, lines.size());
        assertEquals("http://c/3\thttp://c/3", lines.get(0));
    }

    @Test
    public void parseApiCollection_rejectsNormalConfigAndGarbage() {
        assertTrue(ConfigParser.parseApiCollection("{\"sites\":[],\"urls\":[\"http://a/1\"]}").isEmpty());
        assertTrue(ConfigParser.parseApiCollection("{\"urls\":\"http://a/1\"}").isEmpty());
        assertTrue(ConfigParser.parseApiCollection("这不是 JSON").isEmpty());
        assertTrue(ConfigParser.parseApiCollection(null).isEmpty());
    }

    @Test
    public void isDepotJson_matchesOnlyUrlsWithoutSites() {
        assertTrue(ConfigParser.isDepotJson(json("{\"urls\":[{\"name\":\"仓A\",\"url\":\"http://a/1\"}]}")));
        assertFalse(ConfigParser.isDepotJson(json("{\"urls\":[]}")));
        assertFalse(ConfigParser.isDepotJson(json("{\"urls\":\"http://a/1\"}")));
        assertFalse(ConfigParser.isDepotJson(json("{\"urls\":{\"url\":\"http://a/1\"}}")));
        assertFalse(ConfigParser.isDepotJson(json("{\"sites\":[],\"urls\":[\"http://a/1\"]}")));
        assertFalse(ConfigParser.isDepotJson(json("{\"lives\":[]}")));
        assertFalse(ConfigParser.isDepotJson(null));
    }

    @Test
    public void parseApiCollection_toleratesBadNameAndEmptyEntries() {
        ArrayList<String> lines = ConfigParser.parseApiCollection(
                "{\"urls\":[{\"url\":\"http://a/1\",\"name\":123},{\"name\":\"空地址\"},\"http://c/3\"]}");
        assertEquals(2, lines.size());
        assertEquals("http://a/1\thttp://a/1", lines.get(0));
        assertEquals("http://c/3\thttp://c/3", lines.get(1));
    }

    @Test
    public void parseLiveSettingItems_namesAndIndexes() {
        List<LiveSettingItem> items = ConfigParser.parseLiveSettingItems(
                jsonArray("[{\"name\":\"线路A\"},{},{\"name\":\"\"}]"));

        assertEquals(3, items.size());
        assertEquals(0, items.get(0).getItemIndex());
        assertEquals("线路A", items.get(0).getItemName());
        assertEquals(1, items.get(1).getItemIndex());
        assertEquals("线路2", items.get(1).getItemName());
        assertEquals("", items.get(2).getItemName());
    }

    @Test
    public void parseHosts_splitsOnFirstEqualsOnly() {
        Map<String, String> hosts = ConfigParser.parseHosts(
                jsonArray("[\"a.com=1.2.3.4\",\"b.com=2.3.4.5=x\",\"bad\"]"));

        assertEquals(2, hosts.size());
        assertEquals("1.2.3.4", hosts.get("a.com"));
        assertEquals("2.3.4.5=x", hosts.get("b.com"));
    }

    @Test
    public void parseLiveChannelName_prefersNameThenFirstUrl() {
        ArrayList<String> urls = new ArrayList<>(Arrays.asList("http://a/1", "http://a/2"));

        assertEquals("CCTV1", ConfigParser.parseLiveChannelName(json("{\"name\":\" CCTV1 \"}"), urls));
        assertEquals("http://a/1", ConfigParser.parseLiveChannelName(json("{\"urls\":[\"http://a/1\"]}"), urls));
        assertEquals("http://a/1", ConfigParser.parseLiveChannelName(json("{\"name\":null}"), urls));
        assertEquals("http://a/1", ConfigParser.parseLiveChannelName(json("{\"name\":[\"CCTV1\"]}"), urls));
        assertEquals("http://a/1", ConfigParser.parseLiveChannelName(json("{\"name\":{\"id\":1}}"), urls));
        assertEquals("3", ConfigParser.parseLiveChannelName(json("{\"name\":3}"), urls));
        assertEquals("http://a/2",
                ConfigParser.parseLiveChannelName(json("{}"), new ArrayList<>(Arrays.asList("", "http://a/2"))));
        assertEquals("", ConfigParser.parseLiveChannelName(json("{}"), new ArrayList<>()));
        assertEquals("", ConfigParser.parseLiveChannelName(null, new ArrayList<>()));
    }

    @Test
    public void parseLiveCatchup_shapesAndBadValues() {
        JsonObject asObject = ConfigParser.parseLiveCatchup(
                json("{\"catchup\":{\"type\":\"default\",\"source\":\"http://a/{date}\"}}"));
        assertEquals("default", asObject.get("type").getAsString());
        assertEquals("http://a/{date}", asObject.get("source").getAsString());

        JsonObject asScalar = ConfigParser.parseLiveCatchup(json(
                "{\"catchup\":\"default\",\"catchup-source\":\"http://a/{date}\",\"catchup-replace\":\"a,b\"}"));
        assertEquals("default", asScalar.get("type").getAsString());
        assertEquals("http://a/{date}", asScalar.get("source").getAsString());
        assertEquals("a,b", asScalar.get("replace").getAsString());

        assertNull(ConfigParser.parseLiveCatchup(json("{\"catchup\":null}")));
        assertNull(ConfigParser.parseLiveCatchup(json("{\"name\":\"CCTV1\"}")));
        assertNull(ConfigParser.parseLiveCatchup(json("{\"catchup\":[\"default\"]}")));
    }

    @Test
    public void clanToAddress_localhostUsesLocalBase() {
        assertEquals("http://192.168.1.9:9978/file/abc.json",
                ConfigParser.clanToAddress("clan://localhost/abc.json", LOCAL));
    }

    @Test
    public void clanToAddress_remoteHostDoesNotTouchLocalBase() {
        assertEquals("http://tvbox.example.com/file/abc.json",
                ConfigParser.clanToAddress("clan://tvbox.example.com/abc.json", NEVER));
    }

    @Test
    public void clanContentFix_rewritesBothPrefixes() {
        String fixed = ConfigParser.clanContentFix("http://192.168.1.9:9978/file/abc.json",
                "{\"a\":\"clan://localhost/x.jpg\",\"b\":\"file:///sdcard/y.jpg\"}");
        assertEquals("{\"a\":\"http://192.168.1.9:9978/file/x.jpg\",\"b\":\"http://192.168.1.9:9978/file//sdcard/y.jpg\"}",
                fixed);
    }

    @Test
    public void fixContentPath_untouchedWithoutRelativePath() {
        String content = "{\"a\":\"http://a/x.jpg\"}";
        assertEquals(content, ConfigParser.fixContentPath("http://h/dir/config.json", content, NEVER));
    }

    @Test
    public void fixContentPath_resolvesRelativePathsAgainstConfigUrl() {
        assertEquals("{\"a\":\"http://h/dir/pic.jpg\"}",
                ConfigParser.fixContentPath("http://h/dir/config.json", "{\"a\":\"./pic.jpg\"}", NEVER));
        assertEquals("{\"a\":\"http://h/pic.jpg\"}",
                ConfigParser.fixContentPath("h/dir/config.json", "{\"a\":\"../pic.jpg\"}", NEVER));
    }

    @Test
    public void fixContentPath_clanUrlGoesThroughLocalBase() {
        assertEquals("{\"a\":\"http://192.168.1.9:9978/file/pic.jpg\"}",
                ConfigParser.fixContentPath("clan://localhost/config.json", "{\"a\":\"./pic.jpg\"}", LOCAL));
    }

    @Test
    public void configUrl_keepsHttpAndAddsScheme() {
        ConfigParser.ConfigUrl http = ConfigParser.configUrl("http://a/config.json", NEVER);
        assertEquals("http://a/config.json", http.url);
        assertNull(http.key);

        assertEquals("http://a/config.json", ConfigParser.configUrl("a/config.json", NEVER).url);
    }

    @Test
    public void configUrl_splitsPkKey() {
        ConfigParser.ConfigUrl withKey = ConfigParser.configUrl("http://a/config.json;pk;1234", NEVER);
        assertEquals("http://a/config.json", withKey.url);
        assertEquals("1234", withKey.key);

        ConfigParser.ConfigUrl noScheme = ConfigParser.configUrl("a/config.json;pk;k", NEVER);
        assertEquals("http://a/config.json", noScheme.url);
        assertEquals("k", noScheme.key);
    }

    @Test
    public void configUrl_clanAndFileLinks() {
        assertEquals("http://192.168.1.9:9978/file/config.json",
                ConfigParser.configUrl("clan://localhost/config.json", LOCAL).url);
        assertEquals("http://tvbox.example.com/file/c.json",
                ConfigParser.configUrl("clan://tvbox.example.com/c.json;pk;k", NEVER).url);
        assertEquals("http://192.168.1.9:9978/file//sdcard/c.json",
                ConfigParser.configUrl("file:///sdcard/c.json", LOCAL).url);
    }
}
