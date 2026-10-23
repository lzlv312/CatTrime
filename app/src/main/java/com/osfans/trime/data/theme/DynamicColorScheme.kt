/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.osfans.trime.data.theme.model.ColorScheme
import com.osfans.trime.util.appContext
import java.util.Locale

/**
 * The built-in "dynamic" colour scheme, derived from the Material You palettes the system
 * generates from the user's wallpaper (`android.R.color.system_accent*` and
 * `android.R.color.system_neutral*`).
 *
 * It deliberately is *not* a theme preset. [Theme.colorSchemes] only ever holds what
 * `preset_color_schemes` declares, so this scheme is appended to the selectable list at the
 * point of use (see [ColorManager.availableColorSchemes]) under the reserved id [ID]. That way
 * a theme can define whatever schemes it likes without hiding the dynamic one — and, because
 * the id is reserved, without shadowing it either.
 *
 * Two things set it apart from a theme scheme, both of them consequences of the same fact — it
 * is the *app* that owns the palette, not the theme author:
 *
 * 1. It is layered over a theme scheme rather than standing alone. A theme may name colour keys
 *    of its own and bind them per key in its keyboards — the 同文风 theme paints shift,
 *    backspace, return and space with `bgn`, `bbs`, `benter` and `bkg` — and a scheme that
 *    knows nothing about those names leaves the references resolving to nothing.
 * 2. Keys are painted by *kind* rather than through the theme's colour keys: everything that is
 *    not a letter key or the space bar is a function key and takes the same tint (the
 *    `off_key_*` family below, an accent tint rather than another neutral step, so that it reads
 *    as a group next to the letter keys). The theme cannot bind a colour to a key its author
 *    never saw, so this is the only rule that also covers a key the user adds to a custom layout.
 *    The keys sit in [KeyPalette] and the call site in `Key`.
 *
 * The system palettes exist from Android 12 on, so [isSupported] gates every entry point.
 */
object DynamicColorScheme {
    /** Reserved id. It is persisted in `normalModeColor`, so it must stay stable. */
    const val ID = "dynamic"

    /**
     * Annotated so lint can tell that guarding on this property also guards the
     * [android.R.color.system_accent1_600]-style reads below.
     */
    @get:ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private const val TONE_0 = 0
    private const val TONE_10 = 10
    private const val TONE_50 = 50
    private const val TONE_100 = 100
    private const val TONE_200 = 200
    private const val TONE_300 = 300
    private const val TONE_600 = 600
    private const val TONE_700 = 700
    private const val TONE_800 = 800
    private const val TONE_900 = 900

    /**
     * Builds the scheme for the current day/night state, layered on [base].
     *
     * The system palettes carry no day/night variants of their own — which tone is picked does
     * the work instead — so the caller passes the night state and the tones below mirror
     * Material 3's light and dark roles.
     *
     * @param base a scheme of the active theme, normally its own default for this day/night state.
     *   Whatever it defines is kept and only the roles below override it, which keeps the scheme
     *   complete for whatever resolves a key through the chain rather than reading one of the roles
     *   below: a colour key of the theme's own invention, or a built-in one this palette leaves to
     *   its fallback. Keys themselves do not need it — they ignore the theme's colour keys (see the
     *   class note) — so this is about everything drawn around them.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun create(
        isNight: Boolean,
        base: ColorScheme? = null,
    ): ColorScheme = ColorScheme(ID, merge(base, palette(isNight, ::systemColor)))

    /**
     * [material] wins over [base]: matching the system palette is the whole point of this scheme,
     * and the two day/night links have to point back here even where [base] declares its own.
     *
     * Pure, so the layering rule is unit-testable without the system palettes.
     */
    internal fun merge(
        base: ColorScheme?,
        material: Map<String, String>,
    ): Map<String, String> = (base?.colors ?: emptyMap()) + material

    @RequiresApi(Build.VERSION_CODES.S)
    private fun systemColor(
        palette: SystemPalette,
        tone: Int,
    ): String = argb(
        when (palette) {
            SystemPalette.NEUTRAL -> neutralRes(tone)
            SystemPalette.ACCENT1 -> accent1Res(tone)
            SystemPalette.ACCENT2 -> accent2Res(tone)
        },
    )

    @ColorRes
    private fun accent1Res(tone: Int): Int = when (tone) {
        TONE_0 -> android.R.color.system_accent1_0
        TONE_100 -> android.R.color.system_accent1_100
        TONE_200 -> android.R.color.system_accent1_200
        TONE_300 -> android.R.color.system_accent1_300
        TONE_600 -> android.R.color.system_accent1_600
        TONE_700 -> android.R.color.system_accent1_700
        TONE_900 -> android.R.color.system_accent1_900
        else -> error("No accent1 tone $tone")
    }

    @ColorRes
    private fun accent2Res(tone: Int): Int = when (tone) {
        TONE_100 -> android.R.color.system_accent2_100
        TONE_200 -> android.R.color.system_accent2_200
        TONE_300 -> android.R.color.system_accent2_300
        TONE_600 -> android.R.color.system_accent2_600
        TONE_700 -> android.R.color.system_accent2_700
        TONE_900 -> android.R.color.system_accent2_900
        else -> error("No accent2 tone $tone")
    }

    @ColorRes
    private fun neutralRes(tone: Int): Int = when (tone) {
        TONE_10 -> android.R.color.system_neutral1_10
        TONE_50 -> android.R.color.system_neutral1_50
        TONE_100 -> android.R.color.system_neutral1_100
        TONE_200 -> android.R.color.system_neutral1_200
        TONE_300 -> android.R.color.system_neutral1_300
        TONE_600 -> android.R.color.system_neutral1_600
        TONE_700 -> android.R.color.system_neutral1_700
        TONE_800 -> android.R.color.system_neutral1_800
        TONE_900 -> android.R.color.system_neutral1_900
        else -> error("No neutral1 tone $tone")
    }

    private fun argb(
        @ColorRes id: Int,
    ): String = String.format(Locale.ROOT, "#%08X", ContextCompat.getColor(appContext, id))
}

/** One of the three system palettes this scheme paints from. */
internal enum class SystemPalette { NEUTRAL, ACCENT1, ACCENT2 }

/**
 * The colour keys of the dynamic scheme, each mapped to the system palette role it stands for.
 *
 * [color] resolves a (palette, tone) pair to a colour string; the tones are picked per day/night
 * state here, which is what lets the whole assignment be asserted in a unit test without the
 * Android resources behind [DynamicColorScheme.systemColor].
 *
 * The tones are not free invention. They were read back from the Material You scheme the
 * 同文动态配色 app (`com.lanlanderui.trimedynamiccolors`) writes into the theme on this device,
 * by matching each of its colours against the system palettes on (L\*, C\*): that identifies tone
 * and palette family without knowing the wallpaper the hue came from. What it showed is the
 * ladder below — in particular that this scheme had been a tone too dark on every neutral role in
 * the day state, and that the function keys want *chroma*, not just a lighter neutral (see
 * [palette]'s function keys).
 *
 * The three key families are what the scheme is about:
 *
 * - `key_*` — the letter keys and the space bar, plus the chrome around them.
 * - `off_key_*` — *every* function key. Not "the keys the theme marked `functional`": the theme
 *   is not consulted at all, and the call site in `Key` routes every key that is neither a letter
 *   nor the space bar here.
 * - `on_key_*` — a key that is currently toggled on (shift held, caps on), which gets the solid
 *   primary colour: a stronger signal than either idle state, and one the `hilited_*` family can
 *   still deepen.
 */
internal fun palette(
    isNight: Boolean,
    color: (SystemPalette, Int) -> String,
): Map<String, String> {
    fun neutral(light: Int, dark: Int) = color(SystemPalette.NEUTRAL, if (isNight) dark else light)
    fun accent1(light: Int, dark: Int) = color(SystemPalette.ACCENT1, if (isNight) dark else light)

    /**
     * A function-key role, and the one place where the palette family is picked by day/night too.
     *
     * The two states want opposite things here. In the day state a *neutral* tone or a muted
     * accent next to near-white letter keys reads as grey — the keyboard looks faded rather than
     * tinted — so the functions take the primary family, whose tone 100 is the only light tone
     * with chroma to spare. In the night state the same treatment would shout, and the secondary
     * family separates the functions from the neutral letter keys just as well at a fraction of
     * the chroma. This is what the 同文动态配色 scheme does on this device.
     */
    fun function(
        light: Int,
        dark: Int,
    ) = color(if (isNight) SystemPalette.ACCENT2 else SystemPalette.ACCENT1, if (isNight) dark else light)

    // The window is near-white (tone 10) and the letter keys one step in from it, in both states,
    // so keys never merge into the background they sit on.
    val background = neutral(10, 900)
    val keySurface = neutral(50, 800)
    val onKeySurface = neutral(900, 100)
    val onKeySurfaceVariant = neutral(700, 200)
    val outline = neutral(200, 700)

    val primary = accent1(600, 200)
    val onPrimary = accent1(0, 900)
    val primaryContainer = accent1(100, 700)
    val onPrimaryContainer = accent1(900, 100)
    val primaryPressed = accent1(700, 300)

    val functionSurface = function(100, 700)
    val onFunctionSurface = function(900, 100)
    val functionSymbol = function(700, 200)
    val functionOutline = function(300, 600)
    val functionSurfacePressed = function(200, 600)
    val functionOutlinePressed = function(600, 300)

    return mapOf(
        // Both links point back at this scheme. With "follow system day/night" enabled, a scheme
        // declaring neither link would defer to the theme default; the dynamic palette already
        // tracks day/night by itself (see `create`), so it points at itself to survive that branch.
        "light_scheme" to DynamicColorScheme.ID,
        "dark_scheme" to DynamicColorScheme.ID,
        // Backgrounds.
        "back_color" to background,
        "root_background" to background,
        "keyboard_back_color" to background,
        "candidate_background" to background,
        "liquid_keyboard_background" to background,
        "key_back_color" to keySurface,
        "text_back_color" to keySurface,
        "long_text_back_color" to keySurface,
        "popup_back_color" to keySurface,
        // Key press bubble. A legacy name (the live popup paints with `popup_*`), kept for the
        // theme's own references; the schema default points it at the key surface.
        "preview_back_color" to keySurface,
        "preview_text_color" to onKeySurface,
        // Function keys: shift, backspace, return, the symbol and number rows, the keys that only
        // carry a command -- and anything else the user adds to a layout of their own.
        "off_key_back_color" to functionSurface,
        "off_key_text_color" to onFunctionSurface,
        "off_key_symbol_color" to functionSymbol,
        "off_key_border_color" to functionOutline,
        "hilited_off_key_back_color" to functionSurfacePressed,
        "hilited_off_key_text_color" to onFunctionSurface,
        "hilited_off_key_symbol_color" to functionSymbol,
        "hilited_off_key_border_color" to functionOutlinePressed,
        // Letter keys and the space bar.
        "key_text_color" to onKeySurface,
        "key_symbol_color" to onKeySurfaceVariant,
        "key_border_color" to outline,
        "hilited_key_back_color" to primary,
        "hilited_key_text_color" to onPrimary,
        "hilited_key_symbol_color" to onPrimary,
        "hilited_key_border_color" to primary,
        // Toggled keys.
        "on_key_back_color" to primary,
        "on_key_text_color" to onPrimary,
        "on_key_symbol_color" to onPrimary,
        "on_key_border_color" to primary,
        "hilited_on_key_back_color" to primaryPressed,
        "hilited_on_key_text_color" to onPrimary,
        "hilited_on_key_symbol_color" to onPrimary,
        "hilited_on_key_border_color" to primaryPressed,
        // Lines.
        "border_color" to outline,
        "candidate_separator_color" to outline,
        // Text and symbols.
        "candidate_text_color" to onKeySurface,
        "comment_text_color" to onKeySurfaceVariant,
        "long_text_color" to onKeySurface,
        "popup_text_color" to onKeySurface,
        "label_color" to primary,
        "text_color" to primary,
        // Highlighted rows and keys.
        "hilited_back_color" to primaryContainer,
        "hilited_text_color" to onPrimaryContainer,
        "hilited_candidate_back_color" to primaryContainer,
        "hilited_candidate_text_color" to onPrimaryContainer,
        "hilited_comment_text_color" to onPrimaryContainer,
        "hilited_label_color" to onPrimaryContainer,
        // The toolbar's pressed background. ToolButton pairs it with
        // `hilited_candidate_text_color`, which is onPrimaryContainer, so this has to be the
        // container too; primary would leave that text at roughly its own tone.
        "hilited_candidate_button_color" to primaryContainer,
        "hilited_popup_back_color" to primary,
        "hilited_popup_text_color" to onPrimary,
    )
}
