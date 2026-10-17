package com.github.tvbox.osc.ui.activity

internal const val FlatHeroMinSmallestWidthDp = 600

internal fun flatHeroLayout(smallestWidthDp: Int, widthDp: Int, heightDp: Int): Boolean =
    smallestWidthDp >= FlatHeroMinSmallestWidthDp && widthDp > heightDp
