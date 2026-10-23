// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.symbol

import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizePx
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.keyboard.GestureFrame
import com.osfans.trime.ime.keyboard.toIconName
import com.osfans.trime.util.alpha
import com.osfans.trime.util.roundedRippleDrawable
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.gravityCenter
import splitties.views.recyclerview.horizontalLayoutManager

class LiquidTabsUi(
    override val ctx: Context,
    val theme: Theme,
) : Ui {
    // 整行只读一次候选色，不再每个 ViewHolder 各查一遍。
    private val textColor = ColorManager.getColor("candidate_text_color")

    private val hlTextColor = ColorManager.getColor("hilited_candidate_text_color")
    private val hlBackColor = ColorManager.getColor("hilited_candidate_back_color")
    private val cornerRadius = ctx.dp(theme.generalStyle.candidateCornerRadius)

    inner class TabUi : Ui {
        override val ctx = this@LiquidTabsUi.ctx

        val text =
            textView {
                textSize = theme.generalStyle.candidateTextSize
                typeface = FontManager.getTypeface("candidate_font")
                setTextColor(textColor)
            }

        /** 高亮底画在这一层：它只跟自己的内容高，bar 模式行被宿主钉成 40dp 时也不会跟着铺满。 */
        private val pill =
            view(::FrameLayout) {
                minimumWidth = dp(40)
                setPadding(
                    dp(theme.generalStyle.candidatePadding),
                    dp(4),
                    dp(theme.generalStyle.candidatePadding),
                    dp(4),
                )
                add(
                    text,
                    lParams {
                        gravity = gravityCenter
                    },
                )
                background = roundedRippleDrawable(hlBackColor, cornerRadius)
            }

        override val root =
            view(::GestureFrame) {
                add(
                    pill,
                    lParams(wrapContent, wrapContent) {
                        gravity = gravityCenter
                    },
                )
            }

        fun setText(str: String) {
            text.text = str
        }

        fun setActive(active: Boolean) {
            val color = if (active) hlTextColor else textColor
            val contentColor = if (active) hlBackColor else Color.TRANSPARENT
            text.setTextColor(color)
            pill.background = roundedRippleDrawable(hlBackColor, cornerRadius, contentColor)
        }
    }

    private var onTabClick: ((Int) -> Unit)? = null

    private class TabUiHolder(
        val ui: TabUi,
    ) : RecyclerView.ViewHolder(ui.root)

    private val adapter by lazy {
        object : BaseQuickAdapter<LiquidData.Tag, TabUiHolder>() {
            private var selected = -1

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ) = TabUiHolder(TabUi())

            override fun onBindViewHolder(
                holder: TabUiHolder,
                position: Int,
                item: LiquidData.Tag?,
            ) {
                holder.ui.apply {
                    setText(item!!.label)
                    setActive(position == selected)
                    root.run {
                        layoutParams = ViewGroup.LayoutParams(wrapContent, matchParent)
                    }
                }
            }

            override fun submitList(list: List<LiquidData.Tag>?, commitCallback: Runnable?) {
                selected = -1
                super.submitList(list, commitCallback)
            }

            fun activateTab(position: Int) {
                if (position == selected) return
                notifyItemChanged(selected)
                selected = position
                notifyItemChanged(position)
            }
        }.apply {
            setOnItemClickListener { _, _, position ->
                onTabClick?.invoke(position)
            }
        }
    }

    private val tabList =
        recyclerView {
            layoutManager = horizontalLayoutManager()
            adapter = this@LiquidTabsUi.adapter
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }

    private var onGroupLabelClick: (() -> Unit)? = null

    /** 组名是固定键那一格的语义，走键盘那套词汇。 */
    private val labelTextColor = ColorManager.getColor("key_text_color")

    /** 方框按像素给才和候选字号同尺度；sizeDp 会再乘一遍 density。 */
    private val iconSize = (theme.generalStyle.candidateTextSize * 1.2f).toInt()

    /** 返回上一层的暗示，取自随包的图标字体。 */
    private val backIcon =
        IconicsDrawable(ctx, "ic@chevron_left".toIconName()).apply {
            sizePx = iconSize
            colorFilter = PorterDuffColorFilter(labelTextColor, PorterDuff.Mode.SRC_IN)
        }

    /** 分类层的组名，不参与滚动，点击退回主 tab 层。 */
    private val groupLabel =
        textView {
            minimumWidth = dp(40)
            typeface = Typeface.create(FontManager.getTypeface("candidate_font"), Typeface.BOLD)
            textSize = theme.generalStyle.candidateTextSize
            setTextColor(labelTextColor)
            gravity = gravityCenter
            setPadding(
                dp(theme.generalStyle.candidatePadding),
                dp(4),
                dp(theme.generalStyle.candidatePadding),
                dp(4),
            )
            setCompoundDrawablesRelativeWithIntrinsicBounds(backIcon, null, null, null)
            compoundDrawablePadding = dp(4)
            // 水波和 tab 完全同参；差别只在不给实心填充——填色是选中态的语言，组名没有选中态。
            background = roundedRippleDrawable(hlBackColor, cornerRadius)
            visibility = View.GONE
            setOnClickListener { onGroupLabelClick?.invoke() }
        }

    /** 组名和子 tab 之间的短竖线，只在分类层出现。 */
    private val groupDivider =
        view(::View) {
            setBackgroundColor(labelTextColor.alpha(0.3f))
            visibility = View.GONE
        }

    /** 图标会把墨迹长边等比铺满方框，chevron-left 的长边是高：竖线要等高就取整个方框。 */
    private val dividerHeight = iconSize

    override val root =
        view(::LinearLayout) {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 组名按内容高：matchParent 的 TextView 会把 wrapContent 的行顶到可用高度
            add(groupLabel, LinearLayout.LayoutParams(wrapContent, wrapContent))
            add(groupDivider, LinearLayout.LayoutParams(dp(1), dividerHeight))
            add(tabList, LinearLayout.LayoutParams(0, matchParent, 1f))
        }

    fun setTags(tags: List<LiquidData.Tag>) {
        adapter.submitList(tags)
    }

    fun activateTab(index: Int) {
        adapter.activateTab(index)
        // scrollToPosition 会把点到的 tab 硬拽到最左边；平滑滚动只在它没完整露出来时才动。
        tabList.post { tabList.smoothScrollToPosition(index) }
    }

    /** Passing no name hides the group label and leaves the tab list alone. */
    fun setGroupLabel(
        name: String?,
        onClick: (() -> Unit)?,
    ) {
        val visible = name != null
        groupLabel.text = name.orEmpty()
        groupLabel.visibility = if (visible) View.VISIBLE else View.GONE
        groupDivider.visibility = if (visible) View.VISIBLE else View.GONE
        onGroupLabelClick = onClick
    }

    fun setOnTabClickListener(listener: ((Int) -> Unit)? = null) {
        onTabClick = listener
    }
}
