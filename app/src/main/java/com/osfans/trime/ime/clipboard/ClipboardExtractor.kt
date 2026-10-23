/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.ArrayAdapter
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import com.osfans.trime.ui.common.withMaterialTheme
import com.osfans.trime.util.ExtractRule
import com.osfans.trime.util.extractAll
import splitties.dimensions.dp
import kotlin.text.Regex

class ClipboardExtractor(
    private val context: Context,
    private val service: TrimeInputMethodService,
    private val windowManager: BoardWindowManager,
) {
    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste

    private data class InvalidRule(
        val line: Int,
        val reason: String,
    )

    private data class ParsedRules(
        val rules: List<ExtractRule>,
        val invalidRules: List<InvalidRule>,
    )

    private val parsedRules: ParsedRules by lazy {
        val rawRules by prefs.clipboardExtractRules
        parseRules(rawRules)
    }

    private fun parseRules(rawRules: String): ParsedRules {
        val rules = mutableListOf<ExtractRule>()
        val invalidRules = mutableListOf<InvalidRule>()
        rawRules.split('\n').forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            val separator = line.indexOf('\t')
            val pattern = if (separator >= 0) line.substring(0, separator).trim() else line
            val template =
                if (separator >= 0) {
                    line.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            try {
                rules.add(ExtractRule(Regex(pattern), template))
            } catch (e: Exception) {
                val reason = e.message?.substringBefore('\n') ?: e.javaClass.simpleName
                invalidRules.add(InvalidRule(index + 1, reason))
            }
        }
        return ParsedRules(rules, invalidRules)
    }

    fun showExtractDialog(text: String) {
        val (rules, invalidRules) = parsedRules
        val extracted = text.extractAll(rules)
        when {
            extracted.isNotEmpty() -> showExtractedContentDialog(extracted, invalidRules)
            invalidRules.isNotEmpty() -> showInvalidRulesDialog(invalidRules)
            else -> showNoResultDialog()
        }
    }

    private fun showExtractedContentDialog(
        extracted: List<String>,
        invalidRules: List<InvalidRule>,
    ) {
        val dialogContext = context.withMaterialTheme()
        val adapter =
            ArrayAdapter(
                dialogContext,
                R.layout.ime_extract_result_item,
                R.id.extract_result_text,
                extracted,
            )
        val builder =
            dialogContext.materialAlertDialogBuilder()
                .setTitle(R.string.select_extracted_content)
                .setAdapter(adapter) { _, which ->
                    service.commitText(extracted[which])
                    if (clipboardReturnAfterPaste) {
                        windowManager.attachWindow(KeyboardWindow)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
        if (invalidRules.isNotEmpty()) {
            builder.setNeutralButton(R.string.invalid_extract_rules) { _, _ ->
                showInvalidRulesDialog(invalidRules)
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.listView?.apply {
                divider = ColorDrawable(listDividerColor(dialogContext))
                dividerHeight = dialogContext.dp(1)
            }
        }
        service.showDialog(dialog)
    }

    private fun listDividerColor(dialogContext: Context): Int = MaterialColors.getColor(
        dialogContext,
        com.google.android.material.R.attr.colorOutlineVariant,
        Color.TRANSPARENT,
    )

    private fun showNoResultDialog() {
        val dialog =
            context.withMaterialTheme().materialAlertDialogBuilder()
                .setMessage(R.string.no_extraction_results)
                .setPositiveButton(R.string.ok, null)
                .create()
        service.showDialog(dialog)
    }

    private fun showInvalidRulesDialog(invalidRules: List<InvalidRule>) {
        val message =
            invalidRules.joinToString("\n") {
                context.getString(R.string.invalid_rule_line, it.line, it.reason)
            }
        val dialog =
            context.withMaterialTheme().materialAlertDialogBuilder()
                .setTitle(R.string.invalid_extract_rules)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .create()
        service.showDialog(dialog)
    }
}
