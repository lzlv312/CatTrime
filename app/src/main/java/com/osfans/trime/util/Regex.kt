// SPDX-FileCopyrightText: 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.util

private val templateTokenRegex = Regex("""\$\{([A-Za-z][A-Za-z0-9_]*)\}|\$(\d+)""")

/**
 * @property template 为空时输出整条匹配，否则按 `$0`、`$1`、`${name}` 展开
 */
data class ExtractRule(
    val regex: Regex,
    val template: String? = null,
)

fun String.removeRegexSet(regexSet: Set<Regex>): String {
    regexSet.forEach { replace(it, String.EMPTY) }
    return this
}

fun String.matchesAny(regexSet: Set<Regex>): Boolean = regexSet.any { it.matches(this) }

fun String.extractAll(rules: List<ExtractRule>): List<String> {
    val results = mutableSetOf<String>()
    rules.forEach { rule ->
        rule.regex.findAll(this).forEach { match ->
            val extracted = rule.template?.let { match.expandTemplate(it) } ?: match.value
            if (extracted.isNotEmpty()) {
                results.add(extracted)
            }
        }
    }
    return results.toList()
}

private fun MatchResult.expandTemplate(template: String): String = templateTokenRegex.replace(template) { token ->
    val groupName = token.groups[1]?.value
    val groupIndex = token.groups[2]?.value?.toIntOrNull()
    when {
        groupName != null -> runCatching { groups[groupName] }.getOrNull()?.value.orEmpty()
        groupIndex != null -> groupValues.getOrNull(groupIndex).orEmpty()
        else -> ""
    }
}
