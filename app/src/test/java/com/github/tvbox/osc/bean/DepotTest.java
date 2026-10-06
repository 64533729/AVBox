package com.github.tvbox.osc.bean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;

import org.junit.Test;

import java.util.List;

public class DepotTest {

    private static JsonArray arr(String text) {
        return new Gson().fromJson(text, JsonArray.class);
    }

    @Test
    public void arrayFrom_readsUrlNameOptional() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"仓A\",\"url\":\"http://a/1\"},{\"url\":\"http://b/2\"}]"));

        assertEquals(2, items.size());
        assertEquals("仓A", items.get(0).getName());
        assertEquals("http://a/1", items.get(0).getUrl());
        assertEquals("http://b/2", items.get(1).getName());
    }

    @Test
    public void arrayFrom_supportsApiFieldAndBareString() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"x\",\"api\":\"http://d/4\"},\"http://c/3\"]"));

        assertEquals(2, items.size());
        assertEquals("x", items.get(0).getName());
        assertEquals("http://d/4", items.get(0).getUrl());
        assertEquals("http://c/3", items.get(1).getUrl());
    }

    @Test
    public void arrayFrom_skipsEntriesWithoutUrl() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[{\"name\":\"empty\",\"url\":\"\"},{\"name\":\"noUrl\"},{\"url\":\"http://ok/1\"}]"));

        assertEquals(1, items.size());
        assertEquals("http://ok/1", items.get(0).getUrl());
    }

    @Test
    public void arrayFrom_toleratesGarbageEntries() {
        List<Depot> items = Depot.arrayFrom(arr(
                "[null,123,[\"http://nested\"],{\"url\":123},{\"url\":\"http://ok/1\"}]"));

        assertEquals(1, items.size());
        assertEquals("http://ok/1", items.get(0).getUrl());
    }

    @Test
    public void arrayFrom_nonStringNameFallsBackToUrl() {
        List<Depot> items = Depot.arrayFrom(arr("[{\"url\":\"http://a/1\",\"name\":123}]"));

        assertEquals(1, items.size());
        assertEquals("http://a/1", items.get(0).getName());
    }

    @Test
    public void arrayFrom_nullArrayIsEmptyNotCrash() {
        assertTrue(Depot.arrayFrom(null).isEmpty());
        assertTrue(Depot.arrayFrom(arr("[]")).isEmpty());
    }

    @Test
    public void getUrl_trimsWhitespace() {
        List<Depot> items = Depot.arrayFrom(arr("[{\"name\":\" x \",\"url\":\"  http://a/1  \"}]"));

        assertEquals(1, items.size());
        assertEquals("http://a/1", items.get(0).getUrl());
        assertEquals("x", items.get(0).getName());
    }
}
