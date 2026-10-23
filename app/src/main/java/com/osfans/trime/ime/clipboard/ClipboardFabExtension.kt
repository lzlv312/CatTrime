/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.content.Context
import android.content.Intent
import androidx.core.view.isVisible
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.shape.ShapeAppearanceModel
import com.osfans.trime.R
import com.osfans.trime.ui.common.withMaterialTheme
import com.osfans.trime.ui.main.CollectionAddActivity
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.core.add

fun ClipboardLayout.setupCollectionFab(
    viewPager: ViewPager2,
    context: Context,
) {
    val fab =
        FloatingActionButton(context.withMaterialTheme()).apply {
            setCustomSize(context.dp(32))
            setImageResource(R.drawable.ic_baseline_add_24)
            contentDescription = context.getString(R.string.add_to_collection)
            // 32dp 迷你按钮沿用常规 FAB（56dp/16dp 圆角、24dp 图标）的数值会显得过圆、
            // 图标偏大，按 32/56 等比缩小。
            val ratio = 32f / 56f
            setShapeAppearanceModel(
                ShapeAppearanceModel.builder()
                    .setAllCornerSizes(context.dp(16) * ratio)
                    .build(),
            )
            setMaxImageSize((context.dp(24) * ratio).toInt())
        }

    add(
        fab,
        lParams(dp(32), dp(32)) {
            bottomOfParent(dp(12))
            endOfParent(dp(12))
        },
    )

    fab.setOnClickListener {
        context.startActivity(
            Intent(context, CollectionAddActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    viewPager.registerOnPageChangeCallback(
        object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                fab.isVisible = position == 1
            }
        },
    )

    fab.isVisible = viewPager.currentItem == 1
}
