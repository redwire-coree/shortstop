package com.shortstop.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/** Black-and-white palette (light and dark) following the Easlo design file. No accent colour. */
class Palette(ctx: Context) {
    val dark: Boolean = when (Store.theme(ctx)) {
        1 -> false
        2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    val canvas = Color.parseColor(if (dark) "#000000" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#FFFFFF" else "#000000")
    val muted = Color.parseColor(if (dark) "#9A9A9A" else "#666666")
    val surface = Color.parseColor(if (dark) "#121212" else "#F5F5F5")
    val border = Color.parseColor(if (dark) "#2A2A2A" else "#E5E5E5")
    val track = Color.parseColor(if (dark) "#3A3A3A" else "#D4D4D4")
}

/** Inter in four weights, bundled in assets/fonts, with system fallbacks. */
class Fonts(ctx: Context) {
    private fun load(ctx: Context, file: String, fallback: Typeface): Typeface =
        try { Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (e: Exception) { fallback }

    val regular: Typeface = load(ctx, "Inter-Regular.otf", Typeface.DEFAULT)
    val medium: Typeface = load(ctx, "Inter-Medium.otf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val semi: Typeface = load(ctx, "Inter-SemiBold.otf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
    val bold: Typeface = load(ctx, "Inter-Bold.otf", Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
}
