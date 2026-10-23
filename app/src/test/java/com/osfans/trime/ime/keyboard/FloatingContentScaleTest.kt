/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe

class FloatingContentScaleTest :
    BehaviorSpec({
        Given("a keyboard at its natural size") {
            Then("content keeps the theme's own sizes") {
                floatingContentScale(widthPercent = 100, heightPercent = 100) shouldBe 1f
            }
        }

        Given("a floating keyboard scaled by the same share in both directions") {
            Then("content follows that share") {
                floatingContentScale(widthPercent = 80, heightPercent = 80) shouldBe 0.8f
            }
        }

        Given("a floating keyboard whose width and height shares differ") {
            Then("the shorter side decides") {
                floatingContentScale(widthPercent = 40, heightPercent = 100) shouldBe 0.4f
                floatingContentScale(widthPercent = 100, heightPercent = 40) shouldBe 0.4f
                floatingContentScale(widthPercent = 70, heightPercent = 55) shouldBe 0.55f
            }

            Then("content never grows past the theme's own size") {
                floatingContentScale(widthPercent = 150, heightPercent = 120) shouldBe 1f
                floatingContentScale(widthPercent = 120, heightPercent = 90) shouldBe 0.9f
            }
        }

        Given("two sizes of the same floating keyboard") {
            Then("the smaller card gets the smaller content") {
                floatingContentScale(widthPercent = 80, heightPercent = 80) shouldBeGreaterThan
                    floatingContentScale(widthPercent = 50, heightPercent = 60)
            }

            Then("content always shrinks together with the card, never past it") {
                val scale = floatingContentScale(widthPercent = 40, heightPercent = 90)
                scale shouldBeLessThan 1f
                scale shouldBeGreaterThan 0f
            }
        }
    })
