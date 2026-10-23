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
    // 32dp 迷你按钮按常规 FAB 的 32/56 等比缩小：圆角、图标、边距都跟着缩，
    // 视觉上和“用户词典”页的 56dp/16dp 悬浮按钮是同一套比例。
    val ratio = 32f / 56f
    val fab =
        FloatingActionButton(context.withMaterialTheme()).apply {
            setCustomSize(context.dp(32))
            setImageResource(R.drawable.ic_baseline_add_24)
            contentDescription = context.getString(R.string.add_to_collection)
            setShapeAppearanceModel(
                ShapeAppearanceModel.builder()
                    .setAllCornerSizes(context.dp(16) * ratio)
                    .build(),
            )
            setMaxImageSize((context.dp(24) * ratio).toInt())
        }
    val margin = (context.dp(16) * ratio).toInt()

    add(
        fab,
        lParams(dp(32), dp(32)) {
            bottomOfParent(margin)
            endOfParent(margin)
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
