/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.ColorScheme
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The two rules of the built-in dynamic scheme.
 *
 * 1. It is a layer over a theme scheme, not a replacement for it. A theme is free to name colour
 *    keys of its own — the 同文风 theme paints shift, backspace, return and space with `bgn` /
 *    `bbs` / `benter` / `bkg` — and those names exist only in the theme's own
 *    `preset_color_schemes`. A dynamic scheme defining nothing but the built-in keys would leave
 *    every one of those references resolving to nothing.
 * 2. Keys are painted by kind, not through the theme's colour keys: everything that is not a
 *    letter key or the space bar takes the `off_key_*` family, an accent tint rather than another
 *    neutral step, so the function keys are told apart from the letter keys by hue and not only by
 *    luminance. That is the only rule that also covers a key the user adds to a custom layout,
 *    which binds no colour key at all. The accent family is picked by day/night state — see
 *    `palette`'s `function` — because a muted tint next to near-white keys reads as grey.
 *
 * The system palettes themselves need Android resources, so the tones are read through the
 * injected resolver instead of from `ContextCompat`.
 */
class DynamicColorSchemeTest :
    BehaviorSpec({
        val theme =
            ColorScheme(
                "default",
                mapOf(
                    "bgn" to "#FFACB2C2",
                    "benter" to "#FF3266A0",
                    "key_back_color" to "#FFEEEEEE",
                ),
            )
        val roles = mapOf("key_back_color" to "#FFFBFE")

        Given("the Material roles layered on a theme scheme") {
            When("the theme defines colour keys of its own") {
                Then("they are kept") {
                    val merged = DynamicColorScheme.merge(theme, roles)
                    merged["bgn"] shouldBe "#FFACB2C2"
                    merged["benter"] shouldBe "#FF3266A0"
                }
            }
            When("the roles cover a key the theme also defines") {
                Then("the role wins") {
                    DynamicColorScheme.merge(theme, roles)["key_back_color"] shouldBe "#FFFBFE"
                }
            }
            When("the theme scheme declares its own day/night links") {
                Then("the roles still overwrite them") {
                    val linked =
                        ColorScheme(
                            "default",
                            mapOf(
                                "light_scheme" to "dawn",
                                "dark_scheme" to "dusk",
                            ),
                        )
                    val merged =
                        DynamicColorScheme.merge(
                            linked,
                            mapOf(
                                "light_scheme" to "dynamic",
                                "dark_scheme" to "dynamic",
                            ),
                        )
                    merged["light_scheme"] shouldBe "dynamic"
                    merged["dark_scheme"] shouldBe "dynamic"
                }
            }
        }
        Given("nothing to layer on") {
            Then("the roles stand alone") {
                DynamicColorScheme.merge(null, roles) shouldBe roles
            }
        }

        Given("the palette itself") {
            When("the day tones are read") {
                Then("the function keys are tinted from the primary palette") {
                    day.family("off_key_back_color") shouldBe "ACCENT1"
                    day.tone("off_key_back_color") shouldBe 100
                    day.family("off_key_text_color") shouldBe "ACCENT1"
                    day.tone("off_key_symbol_color") shouldBe 700
                    day.tone("off_key_border_color") shouldBe 300
                    day.tone("hilited_off_key_back_color") shouldBe 200
                }
                Then("and the letter keys are neutral, away from the function ones") {
                    day.family("key_back_color") shouldBe "NEUTRAL"
                    day.tone("key_back_color") shouldBe 50
                    day.family("key_back_color") shouldNotBe day.family("off_key_back_color")
                }
                Then("the letter keys sit one step in from the keyboard background") {
                    day.tone("key_back_color") shouldBeGreaterThan day.tone("back_color")
                    day.tone("back_color") shouldBe 10
                }
                Then("the preedit shares the key surface") {
                    day.tone("text_back_color") shouldBe day.tone("key_back_color")
                    night.tone("text_back_color") shouldBe night.tone("key_back_color")
                }
                Then("a key that is toggled on is solid primary") {
                    day.tone("on_key_back_color") shouldBe day.tone("hilited_key_back_color")
                    day.family("on_key_back_color") shouldBe "ACCENT1"
                    day.tone("on_key_back_color") shouldBe 600
                }
            }
            When("the night tones are read") {
                Then("function keys switch to the quieter secondary palette") {
                    night.family("off_key_back_color") shouldBe "ACCENT2"
                    night.tone("off_key_back_color") shouldBe 700
                    night.tone("off_key_text_color") shouldBe 100
                    night.family("on_key_back_color") shouldBe "ACCENT1"
                }
                Then("the letter keys sit one step in from the keyboard background again") {
                    night.tone("key_back_color") shouldBeLessThan night.tone("back_color")
                    night.tone("back_color") shouldBe 900
                    night.tone("key_back_color") shouldBe 800
                }
            }
            Then("both day/night states define exactly the same keys") {
                day.keys shouldBe night.keys
            }
            Then("and both links point back at the reserved id") {
                day["light_scheme"] shouldBe DynamicColorScheme.ID
                day["dark_scheme"] shouldBe DynamicColorScheme.ID
            }
            Then("the toolbar button highlight matches the candidate highlight it is pressed with") {
                // ToolButton draws hilited_candidate_text_color (onPrimaryContainer) on it.
                day["hilited_candidate_button_color"] shouldBe day["hilited_candidate_back_color"]
                night["hilited_candidate_button_color"] shouldBe night["hilited_candidate_back_color"]
            }
            Then("the legacy key preview follows the schema default, the key surface") {
                day["preview_back_color"] shouldBe day["key_back_color"]
                day["preview_text_color"] shouldBe day["key_text_color"]
                night["preview_back_color"] shouldBe night["key_back_color"]
                night["preview_text_color"] shouldBe night["key_text_color"]
            }
        }
    })

/**
 * The palette with the system colours stubbed out as `FAMILY:tone`, so that the *assignment* can
 * be asserted without the Android resources behind the real ones.
 */
private fun paletteOf(isNight: Boolean): Map<String, String> = palette(isNight) { systemPalette, tone -> "${systemPalette.name}:$tone" }

private val day = paletteOf(false)
private val night = paletteOf(true)

private fun Map<String, String>.family(key: String): String = getValue(key).substringBefore(':')

private fun Map<String, String>.tone(key: String): Int = getValue(key).substringAfter(':').toInt()
