/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.symbol

import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.LiquidKeyboard

object LiquidData {
    enum class Type {
        SINGLE,
        SYMBOL,

        /** Keys are rime symbol sequences; each one opens a category of symbols. */
        TABS,
        HISTORY,
        GROUPS,
        VAR_LENGTH,
    }

    data class Tag(val label: String = "", val type: Type)

    private val data = arrayListOf<Pair<Tag, Array<LiquidKeyboard.KeyItem>>>()

    /**
     * Symbols read from rime for a category. Keyed by the tab, the category and
     * the schema, because the very same sequence resolves differently elsewhere.
     */
    private val groupSymbols = hashMapOf<Triple<Int, Int, String>, List<LiquidKeyboard.KeyItem>>()

    fun init(theme: Theme) {
        data.clear()
        groupSymbols.clear()

        val transformed = theme.liquidKeyboard.keyboards.map {
            Tag(it.name, it.type) to
                it.keys.toTypedArray()
        }
        data.addAll(transformed)
    }

    fun getTagList() = data.map { it.first }

    fun getGroupSymbols(
        tab: Int,
        group: Int,
        schemaId: String,
    ): List<LiquidKeyboard.KeyItem>? = groupSymbols[Triple(tab, group, schemaId)]

    fun putGroupSymbols(
        tab: Int,
        group: Int,
        schemaId: String,
        symbols: List<LiquidKeyboard.KeyItem>,
    ) {
        groupSymbols[Triple(tab, group, schemaId)] = symbols
    }

    fun getDataByIndex(index: Int): List<LiquidKeyboard.KeyItem> {
        val item = data[index]
        val tag = item.first
        return if (tag.type == Type.TABS) {
            data.mapNotNull {
                if (it.first.type != Type.TABS) LiquidKeyboard.KeyItem(it.first.label) else null
            }
        } else {
            item.second.toList()
        }
    }

    fun isVarLengthType(type: Type): Boolean = type == Type.VAR_LENGTH || type == Type.GROUPS || type == Type.HISTORY || type == Type.TABS
}
