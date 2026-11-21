package com.shortstop.app

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface

/** Warm paper-and-citrus palette shared by every tab, with a restrained espresso dark theme. */
class Palette(ctx: Context) {
    val dark: Boolean = when (Store.theme(ctx)) {
        1 -> false
        2 -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }
    val canvas = Color.parseColor(if (dark) "#21170F" else "#FFF8F0")
    val ink = Color.parseColor(if (dark) "#F8EBDD" else "#4A2A12")
    val muted = Color.parseColor(if (dark) "#C2AA94" else "#8A725F")
    val surface = Color.parseColor(if (dark) "#302116" else "#FFFCF8")
    val border = Color.parseColor(if (dark) "#493323" else "#F0E2D2")
    val track = Color.parseColor(if (dark) "#59402D" else "#F4E5D4")
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
