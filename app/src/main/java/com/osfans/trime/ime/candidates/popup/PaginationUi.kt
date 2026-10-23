/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import com.osfans.trime.R
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.util.styledFloat
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.imageDrawable

class PaginationUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    private fun createIcon(
        @DrawableRes icon: Int,
    ) = imageView {
        imageTintList = ColorStateList.valueOf(ColorManager.getColor("key_text_color"))
        imageDrawable = drawable(icon)
        // 等比缩放：按钮盒与图标尺寸不一致时（横向 24×20、预编辑指示器 10×10）
        // 完整显示图标，既不裁切也不拉伸变形
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    val prevIcon = createIcon(R.drawable.ic_baseline_arrow_left_24)
    val nextIcon = createIcon(R.drawable.ic_baseline_arrow_right_24)

    private val disabledAlpha = ctx.styledFloat(android.R.attr.disabledAlpha)

    override val root =
        horizontalLayout {
            // splitties 本版本的 add 无默认 LayoutParams 重载，先给占位参数，
            // 真实尺寸由 setHeaderRowMode / setIndicatorMode 切换形态时设置。
            add(
                prevIcon,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            add(
                nextIcon,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

    /**
     * 预编辑行里的翻页按钮（横向布局用）：左右两个箭头并排，整组由 CandidatesView 钉在
     * 预编辑行右缘。每个按钮 24×20dp：宽度保留可点中的触摸区，高度不超过候选词行高。
     *
     * 与 [setIndicatorMode] 是同一个视图实例的两种形态，所以这里要把方向、旋转、
     * 内边距全部重设一遍，不能只改尺寸。
     */
    fun setHeaderRowMode() {
        val params = {
            LinearLayout.LayoutParams(ctx.dp(ICON_WIDTH_DP), ctx.dp(COMPACT_HEIGHT_DP))
        }
        prevIcon.layoutParams = params()
        nextIcon.layoutParams = params()
        prevIcon.rotation = 0f
        nextIcon.rotation = 0f
        root.orientation = LinearLayout.HORIZONTAL
        root.setPadding(ctx.dp(INDICATOR_START_MARGIN_DP), 0, 0, 0)
        root.gravity = Gravity.CENTER_VERTICAL
    }

    /**
     * 预编辑行的小指示器：图标缩到 [INDICATOR_ICON_DP]，两个箭头纵向叠放
     * （整组 10dp 宽、[INDICATOR_HEIGHT_DP] dp 高），由 CandidatesView 固定在预编辑
     * 行右缘，只提示本页是否还有可翻内容，不再占据候选列表的一行。
     *
     * 左右箭头各顺时针转 90°，叠放后成为 ▲/▼，与上下翻页对应。
     * 触摸与翻页处理保持不变（调用方仍绑定两个图标的点击），只是触摸区随图标一起变小。
     */
    fun setIndicatorMode() {
        val iconSize = ctx.dp(INDICATOR_ICON_DP)
        prevIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        nextIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
        prevIcon.rotation = 90f
        nextIcon.rotation = 90f
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(ctx.dp(INDICATOR_START_MARGIN_DP), 0, 0, 0)
        root.gravity = Gravity.CENTER_HORIZONTAL
    }

    fun update(paged: Candidates.Paged) {
        updateIcon(prevIcon, paged.hasPrevPage)
        updateIcon(nextIcon, paged.hasNextPage)
    }

    private fun updateIcon(
        icon: ImageView,
        enabled: Boolean,
    ) {
        icon.isEnabled = enabled
        icon.isClickable = enabled
        icon.alpha = if (enabled) 1f else disabledAlpha
    }

    companion object {
        /** Width of one arrow icon, in dp. */
        private const val ICON_WIDTH_DP = 24

        /** 横向模式的按钮高度，dp；与默认 20sp 候选词的行高相当，不会把预编辑行撑高。 */
        private const val COMPACT_HEIGHT_DP = 20

        /** 纵向模式的指示器图标尺寸，dp。 */
        private const val INDICATOR_ICON_DP = 10

        /** 两个箭头纵向叠放后的组高度，dp。 */
        const val INDICATOR_HEIGHT_DP = 2 * INDICATOR_ICON_DP

        /** 翻页按钮与预编辑文本之间的最小间距，dp。 */
        private const val INDICATOR_START_MARGIN_DP = 4

        /** 纵向指示器占用的整组宽度（最小间距 + 图标），dp。 */
        const val INDICATOR_WIDTH_DP = INDICATOR_START_MARGIN_DP + INDICATOR_ICON_DP

        /** The two arrows side by side, in dp. */
        private const val ARROWS_WIDTH_DP = 2 * ICON_WIDTH_DP

        /** 预编辑行放左右箭头时让给它的宽度：整组箭头 + 与预编辑的间距。 */
        const val HEADER_ARROWS_WIDTH_DP = ARROWS_WIDTH_DP + INDICATOR_START_MARGIN_DP

        /** 预编辑行放左右箭头时需要保有的高度，dp：预编辑不显示时也不让箭头压到候选列表上。 */
        const val HEADER_ARROWS_HEIGHT_DP = COMPACT_HEIGHT_DP
    }
}
