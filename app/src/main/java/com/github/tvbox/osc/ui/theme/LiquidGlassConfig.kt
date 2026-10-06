package com.github.tvbox.osc.ui.theme

const val GLASS_BACKDROP_BAND_MARGIN_DP = 64

data class LiquidGlassConfig(
    val navbarEnabled: Boolean,
    val controlsEnabled: Boolean,
    val blurDp: Float,
    val distortionDp: Float,
    val translucency: Float,
    val dispersion: Boolean,
) {
    val containerAlphaScale: Float get() = 2f - translucency * 2f

    val contentBrightness: Float get() = (0.5f - translucency) * 0.24f

    val contentContrast: Float get() = 1f + (translucency - 0.5f) * 0.5f
}
