package com.github.tvbox.osc.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

public class VodInfoReverseTest {

    @Test
    public void reverse_withoutSeriesIsNoop() {
        VodInfo info = new VodInfo();
        info.reverse();
        assertNull(info.seriesMap);
    }

    @Test
    public void reverse_reversesEveryLineList() {
        VodInfo info = new VodInfo();
        info.seriesMap = new LinkedHashMap<>();
        info.seriesMap.put("线路1", new ArrayList<>(Arrays.asList(
                new VodInfo.VodSeries("1", "http://a/1"), new VodInfo.VodSeries("2", "http://a/2"))));

        info.reverse();

        List<VodInfo.VodSeries> list = info.seriesMap.get("线路1");
        assertEquals("2", list.get(0).name);
        assertEquals("1", list.get(1).name);
    }
}
