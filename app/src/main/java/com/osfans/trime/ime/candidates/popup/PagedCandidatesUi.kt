/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.candidates.popup

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.chad.library.adapter4.BaseQuickAdapter
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.osfans.trime.core.CandidateProto
import com.osfans.trime.core.Candidates
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.recyclerview.recyclerView

class PagedCandidatesUi(
    override val ctx: Context,
    val theme: Theme,
    private val onCandidateClick: (Int) -> Unit,
    private val onCandidateAction: (Int, String, View) -> Unit,
) : Ui {
    private var candidates = Candidates.Paged()

    private var isHorizontal = true

    /**
     * 两个倒序模式（[PopupCandidatesLayout.VERTICAL_REVERSE]、[PopupCandidatesLayout.HORIZONTAL_REVERSE]）
     * 共用一种写法：把整个候选列表上下镜像（[root] 与各条目同以自身中心 scaleY = -1，字身
     * 因此正着）。布局和数据都不动，条目下标就是 Rime 的候选下标，回调无需回映射。
     *
     * 本质是"从下往上长"：候选照旧 1→N 填，第 0 行落到窗口下缘；横向每行 2 个时从上往下
     * 读成 5,6 / 3,4 / 1,2，且没排满的那一行在最上面。这正是 `flex-wrap: wrap-reverse`
     * 的排布，库做不到（见 [candidatesLayoutManager] 的注释），镜像则是等价写法：排版路径
     * 与正序完全相同，只是画出来上下颠倒，每行的几何、行内顺序都和正序一字不差。
     *
     * 纵向曾经用数据倒序绕开 FlexDirection.COLUMN_REVERSE（反向主轴在 RecyclerView
     * wrap_content + 条目 margin 下测量会算错，条目溢出到可见区外），镜像不碰布局，COLUMN
     * 路径仍是正序那条，所以同样安全，还省掉了「显示位置→Rime 下标」的回映射。
     *
     * 横向试过 ROW_REVERSE + 数据倒序，不等价：填不满的行仍落在最下面，整页还是从上往下长。
     */
    private var isFlipped = false

    /** 上一次生效的布局，用于判断是否必须重绑（见 [update]）。 */
    private var appliedLayout: PopupCandidatesLayout? = null

    /** Read per bind rather than captured with `by`, which would freeze the value at construction
     * and leave the setting looking like it did nothing. */
    private val itemSpacingPref = AppPrefs.defaultInstance().candidates.itemSpacing

    /** 同上：行数上限每次绑定时读取，设置页改动后无需重建窗口即生效。 */
    private val rowLimitPref = AppPrefs.defaultInstance().candidates.candidateRowLimit

    /**
     * Width the window's candidate area may occupy, as the window measured it.
     *
     * An item stacks only as wide as the window in a vertical layout; a horizontal item keeps its
     * natural width and wraps to the next flex line when the row is full. Either way this is the
     * upper bound used when the item has to shrink a candidate that is wider than the window.
     * Kept because the row cannot work it out from its own bounds: those are only settled after
     * it has been laid out, by which time the items have already been bound and drawn.
     */
    private var rowWidth = 0

    private class CandidateHolder(
        val ui: LabeledCandidateItemUi,
    ) : RecyclerView.ViewHolder(ui.root)

    // 翻页按钮不是列表条目：横向和纵向都由窗口预编辑行里的那组箭头负责
    // （见 CandidatesView），这个列表只装候选本身。
    private val candidatesAdapter =
        object : BaseQuickAdapter<CandidateProto, CandidateHolder>() {
            init {
                // We must do this to avoid ArrayIndexOutOfBoundsException
                // https://github.com/google/flexbox-layout/issues/363#issuecomment-382949953
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ) = CandidateHolder(LabeledCandidateItemUi(ctx, theme)).apply {
                // assign default LayoutParams, otherwise updateLayoutParams won't work
                ui.root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            override fun onBindViewHolder(
                holder: CandidateHolder,
                position: Int,
                item: CandidateProto?,
            ) {
                val candidate = item ?: return
                // 倒序靠列表整体上下镜像实现，条目自己得再翻回来，否则字是倒的。
                holder.ui.root.scaleY = if (isFlipped) -1f else 1f
                holder.ui.root.setOnClickListener {
                    onCandidateClick.invoke(position)
                }
                holder.ui.root.setOnLongClickListener { v ->
                    onCandidateAction.invoke(position, candidate.text, v)
                    true
                }
                // 行数上限逐条应用：横向行放不下时由 flex 换行，绝不裁剪整页。
                val rowLimit = rowLimitPref.getValue()
                val scale =
                    holder.ui.update(
                        candidate,
                        position == candidates.highlighted,
                        rowWidth,
                        rowLimit,
                    )
                // 截断来源有二：缩放小于 1（单行整单元缩小后仍溢出）、注解被
                // 手工截断到剩余宽度（缩放恒为 1，见 bindSingleLine）——任一
                // 发生都记录到当前页的截断集合，长按菜单据此决定是否提供
                // 「完整显示」；每页重建集合，不做跨页累积，也无需额外测量。
                if (scale < 1f || holder.ui.lastBindTruncated) {
                    truncatedTexts.add(candidate.text)
                } else {
                    truncatedTexts.remove(candidate.text)
                }
                // 多行模式（行数上限 ≥ 2 或不限）下 scale 恒为 1，上面的缩放检测
                // 失明：布局完成后按各行 ellipsisCount 判定注解是否被截断。单行
                // 条目仍以缩放结果为准（缩后放得下的并不出省略号）。
                if (holder.ui.root.maxLines != 1) {
                    val itemRoot = holder.ui.root
                    itemRoot.doOnLayout {
                        val layout = itemRoot.layout ?: return@doOnLayout
                        var ellipsized = false
                        for (line in 0 until layout.lineCount) {
                            if (layout.getEllipsisCount(line) > 0) {
                                ellipsized = true
                                break
                            }
                        }
                        if (ellipsized) {
                            truncatedTexts.add(candidate.text)
                        } else {
                            truncatedTexts.remove(candidate.text)
                        }
                    }
                }
                // 完整显示：长按选中的候选按不限行展开注解，其余按行数上限
                // 排布；横向行里展开项独占若干行，其余候选照常换行。文本做
                // key（同页内定位展开项），展开态随每次更新重置（见 [update]）。
                holder.ui.setExpanded(candidate.text == fullDisplayText)
                holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                    width = if (isHorizontal) WRAP_CONTENT else MATCH_PARENT
                    // Without a gap the fills of two neighbouring candidates touch and the
                    // row reads as a single block, so the items stop looking individually
                    // selectable. The gap follows the axis the candidates run along: a
                    // horizontal row separates them left/right, a vertical one top/bottom.
                    val gap = ctx.dp(itemSpacingPref.getValue())
                    val horizontalGap = if (isHorizontal) gap else 0
                    marginStart = horizontalGap
                    marginEnd = horizontalGap
                    val verticalGap = if (isHorizontal) 0 else gap
                    topMargin = verticalGap
                    bottomMargin = verticalGap
                }
            }
        }

    private val candidatesLayoutManager =
        FlexboxLayoutManager(ctx).apply {
            // 只能是 WRAP：FlexboxLayoutManager.setFlexWrap 收到 WRAP_REVERSE 直接抛
            // UnsupportedOperationException("wrap_reverse is not supported in FlexboxLayoutManager")，
            // 而且它内部那个「行从下往上排」的开关在主轴水平的布局路径里根本没被读过。
            flexWrap = FlexWrap.WRAP
        }

    override val root =
        recyclerView {
            itemAnimator = null
            isFocusable = false
            adapter = candidatesAdapter
            layoutManager = candidatesLayoutManager
            overScrollMode = View.OVER_SCROLL_NEVER
        }

    fun update(
        candidates: Candidates.Paged,
        layout: PopupCandidatesLayout,
        rowWidth: Int,
    ) {
        val wasHorizontal = isHorizontal
        val wasLayout = appliedLayout
        this.candidates = candidates
        // 翻页/换页即重建截断集合：截断与否是“当前页”的属性，跨页累积既无意义
        // 又会让菜单项在切页后误显示。
        truncatedTexts.clear()
        // 完整显示不跨页/跨输入记忆：新候选一到就收起。展开态以文本为 key，
        // 不清空的话下次输入命中相同文本仍会残留展开（展开状态残留问题）。
        fullDisplayText = null
        this.isHorizontal = resolvedHorizontalLayout(candidates, layout)
        this.isFlipped =
            layout == PopupCandidatesLayout.VERTICAL_REVERSE ||
            layout == PopupCandidatesLayout.HORIZONTAL_REVERSE
        this.appliedLayout = layout
        this.rowWidth = rowWidth
        // 从下往上长不靠布局，靠列表自身的朝向：镜像后第 0 行落到窗口下缘，而每行的排版
        // 与正序完全一致（见 isFlipped）。
        root.scaleY = if (isFlipped) -1f else 1f
        candidatesLayoutManager.apply {
            flexDirection = when (layout) {
                PopupCandidatesLayout.HORIZONTAL, PopupCandidatesLayout.HORIZONTAL_REVERSE ->
                    FlexDirection.ROW
                // 倒序不用反向主轴（见 isFlipped），COLUMN_REVERSE 的测量在 wrap_content +
                // margin 下不可靠。
                PopupCandidatesLayout.VERTICAL_REVERSE -> FlexDirection.COLUMN
                PopupCandidatesLayout.AUTOMATIC ->
                    if (isHorizontal) FlexDirection.ROW else FlexDirection.COLUMN
                else -> FlexDirection.COLUMN
            }
            alignItems = if (isHorizontal) AlignItems.BASELINE else AlignItems.STRETCH
        }
        candidatesAdapter.submitList(candidates.candidates.toList())
        // The gaps follow the axis, so a flip has to re-bind even when the page is unchanged.
        // submitList only dispatches when the contents differ, and Rime reports the same page
        // for both layouts, so without this the row would keep the previous orientation's margins.
        // 同一轴向的正序↔倒序切换同理：内容一样、submitList 不派发，但条目要重设镜像朝向。
        if (wasHorizontal != isHorizontal || wasLayout != layout) {
            candidatesAdapter.notifyDataSetChanged()
        }
    }

    /** Re-binds visible rows so they re-render with the current scheme's colors. */
    fun refreshColors() {
        candidatesAdapter.notifyDataSetChanged()
    }

    /** 当前完整展开注解的候选文本；null=无。以文本为 key 在本页内命中，随
     * [update]（翻页/新输入）重置，不跨页/跨输入记忆。 */
    private var fullDisplayText: String? = null

    /** 当前页注解被截断的候选文本集合（单行缩放后仍出省略号、或多行超出上限
     * 出省略号），随每次翻页/更新重建。 */
    private val truncatedTexts = mutableSetOf<String>()

    /**
     * 长按菜单是否提供「完整显示」：仅当该候选注解在当前页确实被截断、且尚未展开
     * 时才显示（未截断没有可展开内容，已展开无需再展开——菜单项随之隐藏）。
     * 判断复用绑定时的缩放结果（见 onBindViewHolder），无需额外测量。横向与纵向
     * 同样适用：行数上限逐条生效，被截断的候选都可能需要完整显示。
     */
    fun canToggleFullDisplay(text: String): Boolean = text != fullDisplayText && text in truncatedTexts

    /**
     * 长按菜单的「完整显示」：把该候选注解多行展开。同时在窗口里只允许一个，
     * 对其他候选触发则顶替（用户规则）。展开后菜单项随之隐藏（见
     * [canToggleFullDisplay]），收起靠顶替、翻页或下一次输入自然重置。
     * 横向行里展开项独占若干行，其余候选照常换行。
     */
    fun toggleFullDisplay(text: String) {
        fullDisplayText = if (fullDisplayText == text) null else text
        candidatesAdapter.notifyDataSetChanged()
    }
}

/**
 * The row orientation the window resolves for [layout]: an explicit preference wins, while
 * [PopupCandidatesLayout.AUTOMATIC] defers to the layout Rime reports along with the page.
 *
 * The owner of the view needs the same answer to size its candidates against, so this is shared
 * rather than resolved twice.
 */
internal fun resolvedHorizontalLayout(
    candidates: Candidates.Paged,
    layout: PopupCandidatesLayout,
): Boolean = when (layout) {
    PopupCandidatesLayout.AUTOMATIC -> candidates.isHorizontalLayout
    PopupCandidatesLayout.HORIZONTAL, PopupCandidatesLayout.HORIZONTAL_REVERSE -> true
    else -> false
}
