package com.github.tvbox.osc.net;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class SearchHelperTest {

    private static Set<String> keys(String... keys) {
        return new HashSet<>(java.util.Arrays.asList(keys));
    }

    private static HashMap<String, String> checked(String... keys) {
        HashMap<String, String> map = new HashMap<>();
        for (String key : keys) map.put(key, "1");
        return map;
    }

    @Test
    public void nullOrEmptySelection_isNeverStale() {
        assertFalse(SearchHelper.isSelectionStale(null, keys("a", "b")));
        assertFalse(SearchHelper.isSelectionStale(new HashMap<>(), keys("a", "b")));
        assertFalse(SearchHelper.isSelectionStale(null, keys()));
    }

    @Test
    public void selectionMatchesCurrentSources_isNotStale() {
        assertFalse(SearchHelper.isSelectionStale(checked("a", "b"), keys("a", "b", "c")));
    }

    @Test
    public void selectionFromAnotherSourceSet_isStale() {
        assertTrue(SearchHelper.isSelectionStale(checked("old1", "old2"), keys("new1", "new2")));
    }

    @Test
    public void partiallyMatchingSelection_isStale() {
        assertTrue(SearchHelper.isSelectionStale(checked("shared", "oldOnly"), keys("shared", "newOnly")));
    }

    @Test
    public void selectionWithAllKeysUnknown_isStale() {
        assertTrue(SearchHelper.isSelectionStale(checked("x"), keys()));
    }
}
