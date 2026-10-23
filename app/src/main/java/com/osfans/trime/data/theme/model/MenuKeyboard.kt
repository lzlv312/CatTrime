/*
 * SPDX-FileCopyrightText: 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.theme.model

import android.os.Parcelable
import com.osfans.trime.util.yaml.Node
import com.osfans.trime.util.yaml.boolean
import com.osfans.trime.util.yaml.get
import com.osfans.trime.util.yaml.int
import com.osfans.trime.util.yaml.sequence
import com.osfans.trime.util.yaml.string
import kotlinx.parcelize.Parcelize

/**
 * 主题顶层 `menu_keyboard` 节点：定制应用面板（SwitchOptionWindow）。
 *
 * 配置后应用面板改为「方案开关 / 功能面板」两个 tab，并隐藏“眼睛”与静态四项；
 * 未配置时保持原有单页行为。
 */
@Parcelize
data class MenuKeyboard(
    /** 默认打开的 tab：0 方案开关，1 功能面板。 */
    val tab: Int = 0,
    /**
     * 面板字体：字体文件名列表（用户目录 `fonts/` 下），同时应用于功能面板与方案开关。
     * 字体内缺字时回落到系统字体。
     */
    val fonts: List<String> = emptyList(),
    /** 功能面板条目，注册顺序即显示顺序。 */
    val items: List<Item> = emptyList(),
) : Parcelable {
    @Parcelize
    data class Item(
        /** 网格文字；缺省时跟随预设键（双态取当前 state 文本，否则取预设键 label）。 */
        val label: String = "",
        /**
         * 图标背景列表：一项通用，两项为 `[浅色, 深色]`，按当前生效配色的深浅选择。
         * 只接受图片（`*.png`、`*.9.png` 等），直接铺成条目键面；非图片视同未配置。没有
         * 图片时图标位文字取自预设键（双态为当前 state，否则为预设键 label），最多两个字。
         * 渲染规则见 [com.osfans.trime.ime.switches.SwitchOptionEntryUi]。
         */
        val icons: List<String> = emptyList(),
        /** 指向的预设键；带 `toggle` 的预设键按双态开关处理。 */
        val action: String = "",
        /** 点击后自动关闭功能面板回到键盘；双态开关等需要停留的操作可关掉。 */
        val autoClose: Boolean = true,
    ) : Parcelable {
        companion object {
            fun decode(node: Node): Item? = when (node) {
                // 简写：`- one_hand_switch` 直接写预设键
                is Node.Scalar -> Item(action = node.string.orEmpty())
                is Node.Mapping -> Item(
                    label = node["label"]?.string ?: "",
                    icons = node["icons"]?.sequence?.mapNotNull(Node::string) ?: emptyList(),
                    action = node["action"]?.string ?: "",
                    autoClose = node["auto_close"]?.boolean ?: true,
                )
                else -> null
            }
        }
    }

    companion object {
        fun decode(node: Node.Mapping): MenuKeyboard = MenuKeyboard(
            tab = node["tab"]?.int ?: 0,
            fonts = node["fonts"]?.sequence?.mapNotNull(Node::string) ?: emptyList(),
            items = node["items"]?.sequence?.mapNotNull(Item::decode) ?: emptyList(),
        )
    }
}
