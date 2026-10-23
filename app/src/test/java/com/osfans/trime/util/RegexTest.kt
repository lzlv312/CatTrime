// SPDX-FileCopyrightText: 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RegexTest :
    StringSpec({
        "without template returns the whole match" {
            val rules = listOf(ExtractRule(Regex("""\d{4}-(\d{2})-(\d{2})""")))
            "date 2026-10-29".extractAll(rules) shouldBe listOf("2026-10-29")
        }

        "template joins capture groups into one result" {
            val rules = listOf(ExtractRule(Regex("""(\d{4})-(\d{2})-(\d{2})"""), "\$1\$2\$3"))
            "2026-10-29".extractAll(rules) shouldBe listOf("20261029")
        }

        "template supports named groups" {
            val rules = listOf(ExtractRule(Regex("""(?<y>\d{4})-(?<m>\d{2})"""), "\${m}/\${y}"))
            "2026-10".extractAll(rules) shouldBe listOf("10/2026")
        }

        "unknown group references expand to empty" {
            val rules = listOf(ExtractRule(Regex("""(\d+)"""), "\${missing}x"))
            "1".extractAll(rules) shouldBe listOf("x")
        }

        "lookaround still narrows the match" {
            val rules = listOf(ExtractRule(Regex("""(?<!\d)\d{6}(?!\d)""")))
            "187333333 code 123456".extractAll(rules) shouldBe listOf("123456")
        }

        "empty matches are skipped" {
            val rules = listOf(ExtractRule(Regex("""z*""")))
            "abc".extractAll(rules) shouldBe emptyList()
        }

        "results follow rule order then match order, deduplicated" {
            val rules = listOf(ExtractRule(Regex("""\d""")), ExtractRule(Regex("""[a-c]""")))
            "1a2b1".extractAll(rules) shouldBe listOf("1", "2", "a", "b")
        }
    })
