package com.github.tvbox.osc.ui.activity

internal object SearchHits {

    internal fun sources(results: List<SearchViewModel.SourceResult>): List<SearchViewModel.SourceResult> =
        results.filter { it.videos.isNotEmpty() }.sortedBy { it.arrivedAt }
}
