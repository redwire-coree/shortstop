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
    val canvas = Color.parseColor(if (dark) "#09090B" else "#FFFFFF")
    val ink = Color.parseColor(if (dark) "#FAFAFA" else "#000000")
    val muted = Color.parseColor(if (dark) "#A1A1AA" else "#666666")
    val surface = Color.parseColor(if (dark) "#141417" else "#F5F5F5")
    val border = Color.parseColor(if (dark) "#27272C" else "#E5E5E5")
    val track = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(36, 0, 0, 0)  // translucent so it blends with whatever is behind
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
