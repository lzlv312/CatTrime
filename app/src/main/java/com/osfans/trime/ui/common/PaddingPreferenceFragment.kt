/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updatePadding
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.osfans.trime.R
import com.osfans.trime.util.applyNavBarInsetsBottomPadding
import splitties.dimensions.dp

/**
 * A fragment template that apply navigation bar window insets bottom padding
 *
 * Taken from fcitx5-android project.
 * Source: https://github.com/fcitx5-android/fcitx5-android/blob/dedfc18/app/src/main/java/org/fcitx/fcitx5/android/ui/common/PaddingPreferenceFragment.kt
 */
abstract class PaddingPreferenceFragment : PreferenceFragmentCompat() {
    override fun onCreateAdapter(
        preferenceScreen: PreferenceScreen,
    ): RecyclerView.Adapter<*> {
        applyMaterial3Layouts(preferenceScreen)
        return super.onCreateAdapter(preferenceScreen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.apply {
            removePreferenceDividers()
            setBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurface,
                ),
            )
            clipToPadding = false
            updatePadding(
                left = context.dp(8),
                top = context.dp(8),
                right = context.dp(8),
                bottom = context.dp(24),
            )
            applyNavBarInsetsBottomPadding()
        }
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // 兜底：不同 preference 库版本里 divider 装饰器可能在 onCreateView 之后才注册
        listView.removePreferenceDividers()
    }

    /**
     * 移除 androidx.preference 画在分类之间的分隔线。
     *
     * Material3 卡片布局本身已有分组视觉，原生分隔线紧贴上一张卡片底部，观感很差。
     * DividerDecoration 是库的私有实现，只能按索引移除 RecyclerView 上的全部装饰器
     * （本界面不会有其他装饰器，重复调用无副作用）。
     */
    private fun RecyclerView.removePreferenceDividers() {
        while (itemDecorationCount > 0) {
            removeItemDecorationAt(0)
        }
    }

    private fun applyMaterial3Layouts(group: PreferenceGroup) {
        for (index in 0 until group.preferenceCount) {
            val preference = group.getPreference(index)
            preference.layoutResource =
                when {
                    preference is PreferenceCategory && preference.title.isNullOrBlank() ->
                        R.layout.preference_category_spacer_material3
                    preference is PreferenceCategory -> R.layout.preference_category_material3
                    else -> R.layout.preference_material3
                }
            if (preference is PreferenceGroup) {
                applyMaterial3Layouts(preference)
            }
        }
    }
}
