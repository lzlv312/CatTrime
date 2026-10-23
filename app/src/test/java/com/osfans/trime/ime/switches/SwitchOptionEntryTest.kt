/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import com.osfans.trime.core.RimeSchema
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Which schema switches the menu shows, and in what shape.
 *
 * The shape matters beyond the label: a switch that carries `options` is the one whose states the
 * menu has to offer as a list, and that list is what used to tear the window down (see
 * `SwitchOptionWindow`). Keeping the rule under test is what keeps that path visible.
 */
class SwitchOptionEntryTest :
    BehaviorSpec({
        fun entry(
            switch: RimeSchema.Switch,
            vararg on: String,
        ) = SwitchOptionEntry.buildEntry(switch) { it in on }

        Given("a switch that names a single on/off option") {
            val toggle =
                RimeSchema.Switch(
                    name = "ascii_mode",
                    states = listOf("中文", "英文"),
                )

            When("the option is off") {
                Then("the label reads off → on") {
                    val built = entry(toggle)!!
                    built.label shouldBe "中文 → 英文"
                    built.selectedIndex shouldBe 0
                }
            }
            When("the option is on") {
                Then("the label reads on → off") {
                    val built = entry(toggle, "ascii_mode")!!
                    built.label shouldBe "英文 → 中文"
                    built.selectedIndex shouldBe 1
                }
            }
        }

        Given("a switch that lists options instead of naming one") {
            // The shape the 万象 schema uses for 简体/通繁/港繁/臺繁.
            val group =
                RimeSchema.Switch(
                    options = listOf("s2s", "s2t", "s2hk", "s2tw"),
                    states = listOf("简体", "通繁", "港繁", "臺繁"),
                )

            When("the first option is on") {
                Then("the label is that state") {
                    val built = entry(group, "s2s")!!
                    built.label shouldBe "简体"
                    built.selectedIndex shouldBe 0
                }
            }
            When("a later option is on") {
                Then("the label follows it") {
                    val built = entry(group, "s2hk")!!
                    built.label shouldBe "港繁"
                    built.selectedIndex shouldBe 2
                }
            }
            When("no listed option is on") {
                Then("the first state is shown and marked, whatever reset says") {
                    val built = entry(group)!!
                    built.label shouldBe "简体"
                    built.selectedIndex shouldBe 0
                    // librime applies `reset` at schema load, so this all-off state only
                    // happens when reset is undeclared or out of range; 0 is its default.
                    entry(group.copy(reset = 2))!!.selectedIndex shouldBe 0
                    entry(group.copy(reset = 9))!!.selectedIndex shouldBe 0
                }
            }
            Then("its options are kept, because they are what the menu lists") {
                entry(group, "s2s")!!.switch.options shouldBe listOf("s2s", "s2t", "s2hk", "s2tw")
            }
        }

        Given("a switch the menu cannot show") {
            Then("a single state is skipped") {
                entry(RimeSchema.Switch(name = "abbrev", states = listOf("简码开"))) shouldBe null
            }
            Then("no states at all is skipped") {
                entry(RimeSchema.Switch(name = "abbrev")) shouldBe null
            }
            Then("a named switch with more than two states is skipped") {
                entry(RimeSchema.Switch(name = "abbrev", states = listOf("a", "b", "c"))) shouldBe null
            }
            Then("options that do not line up with the states are skipped") {
                entry(
                    RimeSchema.Switch(
                        options = listOf("a", "b"),
                        states = listOf("x", "y", "z"),
                    ),
                ) shouldBe null
            }
        }
    })
