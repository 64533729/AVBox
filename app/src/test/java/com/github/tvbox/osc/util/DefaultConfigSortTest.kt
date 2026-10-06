package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.MovieSort
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultConfigSortTest {

    private fun sort(name: String) = MovieSort.SortData(name, name)

    @Test
    fun pickByCategories_keepsWhitelistOrder() {
        val list = listOf(sort("A"), sort("B"), sort("C"))
        val picked = DefaultConfig.pickByCategories(list, listOf("C", "A"))
        assertEquals(listOf("C", "A"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_unmatchedFallsBackToAll() {
        val list = listOf(sort("A"), sort("B"))
        val picked = DefaultConfig.pickByCategories(list, listOf("X", "Y"))
        assertEquals(listOf("A", "B"), picked.map { it.name })
    }

    @Test
    fun pickByCategories_emptyListStaysEmpty() {
        val picked = DefaultConfig.pickByCategories(emptyList(), listOf("X"))
        assertEquals(0, picked.size)
    }
}
