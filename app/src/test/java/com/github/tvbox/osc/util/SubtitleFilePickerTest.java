package com.github.tvbox.osc.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SubtitleFilePickerTest {

    private static final String EP1 = "Show.S01E01.1080p.ass";
    private static final String EP2 = "Show.S01E02.1080p.ass";
    private static final String EP3 = "Show.S01E03.1080p.ass";

    @Test
    public void picksSameFileWhenUserPickedItBefore() {
        assertEquals(1, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第2集", EP2));
    }

    @Test
    public void picksByEpisodeNumberAcrossEpisodes() {
        assertEquals(2, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第3集", EP1));
        assertEquals(0, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2, EP3), "第1集", EP3));
    }

    @Test
    public void episodeNumberFormatsAreTolerated() {
        java.util.List<String> files = Arrays.asList("Show.S01E01.ass", "Show.S01E02.ass");
        assertEquals(1, SubtitleFilePicker.pick(files, "02", "Show.S01E01.ass"));
        assertEquals(1, SubtitleFilePicker.pick(files, "第2集", ""));
        assertEquals(1, SubtitleFilePicker.pick(files, "EP02", ""));
    }

    @Test
    public void sameEpisodeMultipleVariantsPicksMatchingVariant() {
        java.util.List<String> files = Arrays.asList("Show.S01E02.chs.ass", "Show.S01E02.cht.ass");
        assertEquals(0, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.chs.ass"));
        assertEquals(1, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.cht.ass"));
    }

    @Test
    public void sameEpisodeSameVariantReturnsMiss() {
        java.util.List<String> files = Arrays.asList("Show.S01E02.1080p.ass", "Show.S01E02.720p.ass");
        assertEquals(-1, SubtitleFilePicker.pick(files, "第2集", "Show.S01E01.1080p.ass"));
    }

    @Test
    public void sameEpisodeDuplicatedNameReturnsMiss() {
        java.util.List<String> files = Arrays.asList("Show.E02.ass", "Show.E02.ass");
        assertEquals(-1, SubtitleFilePicker.pick(files, "第2集", ""));
    }

    @Test
    public void singleFileReleaseIsWholeSeason() {
        java.util.List<String> files = Collections.singletonList("Show.S01.全集.ass");
        assertEquals(0, SubtitleFilePicker.pick(files, "正片", ""));
    }

    @Test
    public void singleFileWithOtherEpisodeNumberReturnsMiss() {
        java.util.List<String> files = Collections.singletonList(EP1);
        assertEquals(-1, SubtitleFilePicker.pick(files, "第3集", ""));
    }

    @Test
    public void noEpisodeNumberInSeriesNameReturnsMiss() {
        assertEquals(-1, SubtitleFilePicker.pick(Arrays.asList(EP1, EP2), "正片", ""));
    }

    @Test
    public void emptyInputsReturnMiss() {
        assertEquals(-1, SubtitleFilePicker.pick(Collections.<String>emptyList(), "第1集", EP1));
        assertEquals(-1, SubtitleFilePicker.pick(null, "第1集", EP1));
    }

    @Test
    public void extensionDifferenceIsTolerated() {
        assertEquals(0, SubtitleFilePicker.pick(Collections.singletonList("Show.S01E01.ass"), "正片", "Show.S01E01.srt"));
        assertEquals(-1, SubtitleFilePicker.pick(Arrays.asList("Show.S01E01.ass", "Show.S01E02.ass"), "正片", "Other.S01E01.srt"));
    }
}
