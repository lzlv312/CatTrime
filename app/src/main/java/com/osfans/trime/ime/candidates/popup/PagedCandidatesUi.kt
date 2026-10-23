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
    private val onPrevPage: () -> Unit,
    private val onNextPage: () -> Unit,
) : Ui {
    private var candidates = Candidates.Paged()

    private var isHorizontal = true

    /**
     * 纵向倒序（[PopupCandidatesLayout.VERTICAL_REVERSE]）用数据倒序实现，而不是
     * FlexDirection.COLUMN_REVERSE：flexbox 库的反向布局在 RecyclerView wrap_content
     * 测量下内容高度会算错（叠加条目 margin 后必然复现），条目被布局到可见区之外
     * （占位不显示、点击错位）。数据倒序让布局管理器走与正序完全相同的 COLUMN 路径。
     */
    private var isReversed = false

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

    sealed class UiHolder(
        open val ui: Ui,
    ) : RecyclerView.ViewHolder(ui.root) {
        class Candidate(
            override val ui: LabeledCandidateItemUi,
        ) : UiHolder(ui)

        class Pagination(
            override val ui: PaginationUi,
        ) : UiHolder(ui)
    }

    private val candidatesAdapter =
        object : BaseQuickAdapter<CandidateProto, UiHolder>() {
            init {
                // We must do this to avoid ArrayIndexOutOfBoundsException
                // https://github.com/google/flexbox-layout/issues/363#issuecomment-382949953
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = items.getOrNull(position).hashCode().toLong()

            // 纵向布局的翻页提示在窗口顶行（见 CandidatesView），不再作为列表条目；
            // 只有横向布局把翻页条放在候选行尾。
            override fun getItemCount(items: List<CandidateProto>): Int {
                val hasInlinePagination =
                    isHorizontal && (candidates.hasPrevPage || candidates.hasNextPage)
                return items.size + if (hasInlinePagination) 1 else 0
            }

            override fun getItemViewType(
                position: Int,
                list: List<CandidateProto>,
            ) = if (position < list.size) 0 else 1

            override fun onCreateViewHolder(
                context: Context,
                parent: ViewGroup,
                viewType: Int,
            ): UiHolder = when (viewType) {
                0 -> UiHolder.Candidate(LabeledCandidateItemUi(ctx, theme))
                else -> UiHolder.Pagination(PaginationUi(ctx, theme)).apply {
                    ui.prevIcon.setOnClickListener {
                        onPrevPage.invoke()
                    }
                    ui.nextIcon.setOnClickListener {
                        onNextPage.invoke()
                    }
                }
            }.apply {
                // assign default LayoutParams, otherwise updateLayoutParams won't work
                ui.root.layoutParams = FlexboxLayoutManager.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            }

            override fun onBindViewHolder(
                holder: UiHolder,
                position: Int,
                item: CandidateProto?,
            ) {
                when (holder) {
                    is UiHolder.Candidate -> {
                        val candidate = item ?: return
                        // 倒序是纯显示层的列表反转，回调必须映射回 Rime 的候选下标
                        val candidateIndex =
                            if (isReversed) items.size - 1 - position else position
                        holder.ui.root.setOnClickListener {
                            onCandidateClick.invoke(candidateIndex)
                        }
                        holder.ui.root.setOnLongClickListener { v ->
                            onCandidateAction.invoke(candidateIndex, candidate.text, v)
                            true
                        }
                        // 行数上限逐条应用：横向行放不下时由 flex 换行，绝不裁剪整页。
                        val rowLimit = rowLimitPref.getValue()
                        val scale =
                            holder.ui.update(
                                candidate,
                                candidateIndex == candidates.highlighted,
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
                    is UiHolder.Pagination -> {
                        holder.ui.update(candidates)
                        holder.ui.setInlineRowMode()
                        holder.ui.root.updateLayoutParams<FlexboxLayoutManager.LayoutParams> {
                            flexGrow = 1f
                            width = WRAP_CONTENT
                            alignSelf = AlignItems.CENTER
                        }
                    }
                }
            }
        }

    private val candidatesLayoutManager =
        FlexboxLayoutManager(ctx).apply {
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
        this.candidates = candidates
        // 翻页/换页即重建截断集合：截断与否是“当前页”的属性，跨页累积既无意义
        // 又会让菜单项在切页后误显示。
        truncatedTexts.clear()
        // 完整显示不跨页/跨输入记忆：新候选一到就收起。展开态以文本为 key，
        // 不清空的话下次输入命中相同文本仍会残留展开（展开状态残留问题）。
        fullDisplayText = null
        this.isHorizontal = resolvedHorizontalLayout(candidates, layout)
        this.isReversed = layout == PopupCandidatesLayout.VERTICAL_REVERSE
        this.rowWidth = rowWidth
        candidatesLayoutManager.apply {
            flexDirection = when (layout) {
                PopupCandidatesLayout.HORIZONTAL -> FlexDirection.ROW
                // 反向布局改由数据倒序承载（见 isReversed），COLUMN_REVERSE 的测量
                // 在 wrap_content + margin 下不可靠。
                PopupCandidatesLayout.VERTICAL_REVERSE -> FlexDirection.COLUMN
                PopupCandidatesLayout.AUTOMATIC ->
                    if (isHorizontal) FlexDirection.ROW else FlexDirection.COLUMN
                else -> FlexDirection.COLUMN
            }
            alignItems = if (isHorizontal) AlignItems.BASELINE else AlignItems.STRETCH
        }
        val displayList = candidates.candidates.toList()
        candidatesAdapter.submitList(
            if (isReversed) displayList.asReversed() else displayList,
        )
        // The gaps follow the axis, so a flip has to re-bind even when the page is unchanged.
        // submitList only dispatches when the contents differ, and Rime reports the same page
        // for both layouts, so without this the row would keep the previous orientation's margins.
        if (wasHorizontal != isHorizontal) {
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
    else -> layout == PopupCandidatesLayout.HORIZONTAL
}
