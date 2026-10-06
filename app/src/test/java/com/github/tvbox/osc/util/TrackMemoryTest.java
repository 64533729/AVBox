package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TrackMemoryTest {

    @Test
    public void contentKey_isSourcePlusVodId() {
        assertEquals("csp_abc@12345", TrackMemory.contentKey("csp_abc", "12345"));
    }

    @Test
    public void contentKey_emptyWhenMissingPart() {
        assertEquals("", TrackMemory.contentKey(null, "1"));
        assertEquals("", TrackMemory.contentKey("src", ""));
        assertEquals("", TrackMemory.contentKey("  ", "  "));
    }

    @Test
    public void fingerprint_distinguishesCodecAndChannel() {
        String aac = TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2);
        String eac3 = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        assertFalse(aac.equals(eac3));
        assertEquals("A/国语/mp4a.40.2/2", aac);
    }

    @Test
    public void fingerprint_codecIsCaseInsensitive() {
        assertEquals(TrackMemory.audioFingerprint("国语", "AAC", 2), TrackMemory.audioFingerprint("国语", "aac", 2));
    }

    @Test
    public void usable_rejectsDegenerateFingerprint() {
        assertFalse(TrackMemory.usable(TrackMemory.audioFingerprint("", "", 0)));
        assertFalse(TrackMemory.usable(TrackMemory.videoFingerprint("", 0, 0)));
        assertFalse(TrackMemory.usable(TrackMemory.textFingerprint("", "")));
        assertFalse(TrackMemory.usable(null));
        assertFalse(TrackMemory.usable("A"));
        assertTrue(TrackMemory.usable(TrackMemory.audioFingerprint("", "aac", 0)));
        assertTrue(TrackMemory.usable(TrackMemory.videoFingerprint("h264", 1920, 1080)));
        assertTrue(TrackMemory.usable(TrackMemory.textFingerprint("国语", "")));
    }

    @Test
    public void pick_exactMatchWins() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
                remembered,
                TrackMemory.audioFingerprint("粤语", "mp4a.40.2", 2));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_languageFallbackWhenCodecChanged() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2),
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_languageAmbiguousReturnsMiss() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.audioFingerprint("国语", "mp4a.40.2", 2),
                TrackMemory.audioFingerprint("国语", "ec-3", 2));
        assertEquals(-1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_missWhenNothingMatches() {
        String remembered = TrackMemory.audioFingerprint("国语", "ec-3", 6);
        java.util.List<String> available = Collections.singletonList(
                TrackMemory.audioFingerprint("英语", "mp4a.40.2", 2));
        assertEquals(-1, TrackMemory.pick(available, remembered));
        assertEquals(-1, TrackMemory.pick(available, null));
        assertEquals(-1, TrackMemory.pick(Collections.<String>emptyList(), remembered));
    }

    @Test
    public void pick_videoByResolution() {
        String remembered = TrackMemory.videoFingerprint("h264", 1920, 1080);
        java.util.List<String> available = Arrays.asList(
                TrackMemory.videoFingerprint("h264", 1280, 720),
                TrackMemory.videoFingerprint("h264", 1920, 1080));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void pick_textByLanguage() {
        String remembered = TrackMemory.textFingerprint("英语", "srt");
        java.util.List<String> available = Arrays.asList(
                TrackMemory.textFingerprint("国语", "srt"),
                TrackMemory.textFingerprint("英语", "srt"));
        assertEquals(1, TrackMemory.pick(available, remembered));
    }

    @Test
    public void subtitleSource_roundTrip() {
        String local = TrackMemory.subtitleLocal("/data/user/0/app/cache/subtitle_1_a.srt");
        assertTrue(TrackMemory.isSubtitleLocal(local));
        assertEquals("/data/user/0/app/cache/subtitle_1_a.srt", TrackMemory.localPath(local));
        assertTrue(local.contains("/data/user/0/"));

        String online = TrackMemory.subtitleOnline("https://assrt.net/sub/12345", "Show.S01E02.ass");
        assertTrue(TrackMemory.isSubtitleOnline(online));
        assertEquals("https://assrt.net/sub/12345", TrackMemory.onlineRelease(online));
        assertEquals("Show.S01E02.ass", TrackMemory.onlineFileName(online));
    }

    @Test
    public void subtitleSource_offAndTrackAreDistinguished() {
        assertTrue(TrackMemory.isSubtitleOff(TrackMemory.SUBTITLE_OFF));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.SUBTITLE_OFF));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleLocal("/tmp/a.srt")));
        assertFalse(TrackMemory.isSubtitleTrack(TrackMemory.subtitleOnline("https://a/b", "c.ass")));
        String builtin = TrackMemory.textFingerprint("国语", "srt");
        assertTrue(TrackMemory.isSubtitleTrack(builtin));
    }

    @Test
    public void subtitleSource_emptyInputsRejected() {
        assertEquals("", TrackMemory.subtitleLocal(null));
        assertEquals("", TrackMemory.subtitleLocal("   "));
        assertEquals("", TrackMemory.subtitleOnline("", "a.ass"));
        assertFalse(TrackMemory.isSubtitleOnline(null));
        assertEquals("", TrackMemory.onlineRelease(TrackMemory.SUBTITLE_OFF));
        assertEquals("", TrackMemory.onlineFileName(TrackMemory.SUBTITLE_OFF));
    }

    @Test
    public void subtitleSource_onlineWithoutFileName() {
        String online = TrackMemory.subtitleOnline("https://assrt.net/sub/1", "");
        assertEquals("https://assrt.net/sub/1", TrackMemory.onlineRelease(online));
        assertEquals("", TrackMemory.onlineFileName(online));
    }
}
