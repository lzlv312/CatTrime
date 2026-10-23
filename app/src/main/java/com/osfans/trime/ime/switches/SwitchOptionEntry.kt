/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import androidx.annotation.DrawableRes
import com.osfans.trime.core.RimeSchema
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.theme.model.MenuKeyboard

sealed class SwitchOptionEntry(
    val label: String,
    @param:DrawableRes
    val icon: Int,
) {
    class Static(label: String, icon: Int, val type: Type) : SwitchOptionEntry(label, icon) {
        enum class Type {
            SchemaList,
            UpdateConfig,
            Keyboard,
            ThemeList,
        }
    }

    class Custom(
        val switch: RimeSchema.Switch,
        label: String,
        icon: Int,
        /**
         * Index into [RimeSchema.Switch.states] that the menu marks as current. For an
         * `options` switch it follows the option that is on; 0 otherwise — librime applies
         * `reset` at schema load, so an all-off radio group only means `reset` was undeclared
         * or out of range, whose default state is 0 (switches.cc `Reset`).
         */
        val selectedIndex: Int,
    ) : SwitchOptionEntry(label, icon)

    /**
     * 功能面板条目，来自主题 `menu_keyboard/items`。
     *
     * [iconStyle] 是图标位的背景图片，已按生效配色的深浅解析好；非图片的 icons 视同未配置。
     * [iconText] 是图标位的文字，取自预设键（双态为当前 state，否则为预设键 label），与
     * 条目 label 无关；为空时图标位回落显示条目 label。渲染规则见 [SwitchOptionEntryUi]。
     *
     * [isOn] 仅双态条目为真：当前 Rime 选项开启，label 用高亮色显示。
     */
    class Menu(
        val item: MenuKeyboard.Item,
        label: String,
        val iconStyle: String = "",
        val iconText: String = "",
        val isOn: Boolean = false,
    ) : SwitchOptionEntry(label, 0)

    companion object {
        fun fromSwitch(
            rime: RimeSession,
            it: RimeSchema.Switch,
        ): Custom? = buildEntry(it) { name -> rime.run { getRuntimeOption(name) } }

        /**
         * The entry-building rules, expressed over a plain option lookup so they can be tested
         * without a Rime session.
         *
         * A switch that has a `name` is an on/off toggle and needs two states to read as one; a
         * switch without a `name` toggles a group of `options` instead, and then one state per
         * option. Anything else has nothing the menu could show, so it yields null and is skipped.
         */
        internal fun buildEntry(
            it: RimeSchema.Switch,
            isOptionOn: (String) -> Boolean,
        ): Custom? {
            val labels = it.states
            if (labels.size <= 1) return null
            return if (it.name.isNotEmpty()) {
                if (labels.size != 2) return null
                val (disabledText, enabledText) = labels
                val value = isOptionOn(it.name)
                val label = if (value) "$enabledText → $disabledText" else "$disabledText → $enabledText"
                Custom(it, label, 0, if (value) 1 else 0)
            } else {
                val options = it.options
                if (options.size != labels.size) return null
                val index = options.indexOfFirst(isOptionOn)
                val selectedIndex = if (index >= 0) index else 0
                Custom(it, labels[selectedIndex], 0, selectedIndex)
            }
        }
    }
}
