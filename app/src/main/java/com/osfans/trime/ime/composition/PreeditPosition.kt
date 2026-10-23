/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

/**
 * 预编辑容器在候选窗口内的位置。
 *
 * 与 [PopupPosition]（候选窗口在屏幕上的位置）无关：后者决定窗口本身落在哪儿，这里只决定
 * 窗口内部预编辑行与候选列表谁在上、谁在下。
 */
enum class PreeditPosition(
    override val stringRes: Int,
) : PreferenceDelegateEnum {
    TOP(R.string.preedit_position_top), // 默认
    BOTTOM(R.string.preedit_position_bottom),
}
