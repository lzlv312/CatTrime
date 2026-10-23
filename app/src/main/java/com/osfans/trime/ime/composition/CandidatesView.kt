/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.annotation.SuppressLint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver.OnGlobalLayoutListener
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.WindowInsets
import android.widget.LinearLayout
import androidx.annotation.Size
import androidx.core.graphics.component1
import androidx.core.graphics.component2
import androidx.core.graphics.component3
import androidx.core.graphics.component4
import com.osfans.trime.core.Candidates
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.candidates.popup.PagedCandidatesUi
import com.osfans.trime.ime.candidates.popup.PaginationUi
import com.osfans.trime.ime.candidates.popup.resolvedHorizontalLayout
import com.osfans.trime.ime.core.BaseInputView
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.bottomToBottomOf
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.withTheme
import splitties.views.dsl.core.wrapContent
import splitties.views.horizontalPadding
import splitties.views.setPaddingDp
import splitties.views.verticalPadding
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class CandidatesView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    theme: Theme,
) : BaseInputView(service, rime, theme) {
    private val ctx = context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private val layout by AppPrefs.defaultInstance().candidates.layout
    private val position by AppPrefs.defaultInstance().candidates.position
    private val preeditPosition by AppPrefs.defaultInstance().candidates.preeditPosition

    /**
     * Opacity of this window's own background, in percent. Text and the highlighted item are
     * drawn by the children, so they stay readable while the background fades.
     */
    private val backgroundAlpha by AppPrefs.defaultInstance().candidates.backgroundAlpha

    private var candidates = Candidates.Paged()
    private var composition = CompositionProto()

    private val anchorPosition = RectF()
    private val parentSize = floatArrayOf(0f, 0f)

    /**
     * A region of this view's parent that the window must stay clear of, in the parent's
     * coordinate space. Set while a floating keyboard is on screen, empty otherwise.
     */
    private val obstruction = RectF()

    private var shouldUpdatePosition = false

    /**
     * layout update may or may not cause [CandidatesView]'s size [onSizeChanged],
     * in either case, we should reposition it
     */
    private val layoutListener =
        OnGlobalLayoutListener {
            shouldUpdatePosition = true
        }

    /**
     * [CandidatesView]'s position is calculated based on it's size,
     * so we need to recalculate the position after layout,
     * and before any actual drawing to avoid flicker
     */
    private val preDrawListener =
        OnPreDrawListener {
            if (shouldUpdatePosition) {
                updatePosition()
            }
            true
        }

    private val preeditUi =
        PreeditUi(
            ctx,
            theme,
            setupPreeditView = { setPaddingDp(3, 1, 3, 1) },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        )

    /**
     * 预编辑行的翻页按钮：贴在预编辑行最右缘，是候选窗口里唯一的翻页入口（候选列表不再
     * 装它）。点击直接让 Rime 换页。横向（含倒序）用 24×20dp 的左右箭头，纵向用 10dp 的
     * ▲▼ 小指示器，形态随列表方向切换（见 [PaginationUi.setHeaderRowMode]）。
     */
    private val paginationIndicator =
        PaginationUi(ctx, theme).apply {
            setIndicatorMode()
            prevIcon.setOnClickListener { turnPage(forward = false) }
            nextIcon.setOnClickListener { turnPage(forward = true) }
            root.visibility = GONE
        }

    /**
     * 预编辑行：容器本身，靠窗口上缘还是下缘由「候选预编辑位置」决定（见
     * [applyPreeditPosition]）。翻页按钮不放在这里，而是作为同级视图固定在窗口右缘
     * （见 [init]），这样候选很长、预编辑很短时按钮也贴着右边缘，不会停在中间。
     */
    private val headerUi =
        object : LinearLayout(ctx) {
            init {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    preeditUi.root,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }

    // 显式类型：onCandidateAction 回调里引用了 candidatesUi 自身（toggleFullDisplay），
    // 省略类型会让编译器陷入递归推断。
    private val candidatesUi: PagedCandidatesUi =
        PagedCandidatesUi(
            ctx,
            theme,
            onCandidateClick = { index -> rime.launchOnReady { it.selectCandidate(index, global = false) } },
            onCandidateAction = { index, text, view ->
                // 仅当注解确实被截断且尚未展开时才提供「完整显示」：未截断的候选
                // 没有可展开内容，已展开的无需再展开，菜单项都不出现。
                val toggle: (() -> Unit)? =
                    if (candidatesUi.canToggleFullDisplay(text)) {
                        { candidatesUi.toggleFullDisplay(text) }
                    } else {
                        null
                    }
                showCandidateActionMenu(index, text, view, global = false, toggle)
            },
        )

    private val touchEventReceiverWindow = TouchEventReceiverWindow(this)

    private var bottomInsets = 0

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CompositionMessage -> {
                composition = it.data
                updateUi()
            }
            is RimeMessage.PagedCandidatesMessage -> {
                candidates = it.data
                updateUi()
            }
            else -> {}
        }
    }

    private fun evaluateVisibility(): Boolean = !composition.preedit.isNullOrEmpty() ||
        candidates.candidates.isNotEmpty()

    private fun updateUi() {
        applyPreeditPosition()
        preeditUi.update(composition)
        preeditUi.root.visibility = if (preeditUi.visible) VISIBLE else GONE
        val horizontal = resolvedHorizontalLayout(candidates, layout)
        // 翻页按钮不进候选列表，一律由预编辑行负责；列表的方向只决定它的形态：
        // 横向是 24×20dp 的左右箭头，纵向是 10dp 的 ▲▼ 小指示器。
        val showIndicator = candidates.hasPrevPage || candidates.hasNextPage
        updatePreeditMaxWidth(reserveIndicator = showIndicator, arrowsInHeader = horizontal)
        // the candidate layout is queried natively with the page itself
        candidatesUi.update(candidates, layout, usableRowWidth().toInt())
        updatePaginationIndicator(candidates, showIndicator, arrowsInHeader = horizontal)
        visibility = if (evaluateVisibility()) {
            VISIBLE
        } else {
            // RecyclerView won't update its items when ancestor view is GONE
            INVISIBLE
        }
    }

    private var preeditMaxWidth = -1

    /**
     * 预编辑最多能用窗口可用宽度减去翻页提示的宽度，超出部分换行。
     * 这样预编码再长也不会把指示器顶出窗口右侧。
     *
     * 让出多少取决于提示的形态：预编辑行里的整组箭头比纵向的 ▲▼ 小指示器宽得多。
     */
    private fun updatePreeditMaxWidth(
        reserveIndicator: Boolean,
        arrowsInHeader: Boolean,
    ) {
        val reserveDp =
            when {
                !reserveIndicator -> 0f
                arrowsInHeader -> PaginationUi.HEADER_ARROWS_WIDTH_DP.toFloat()
                else -> PaginationUi.INDICATOR_WIDTH_DP.toFloat()
            }
        val maxWidth = usableRowWidth() - dp(reserveDp)
        if (maxWidth <= 0f) return
        val px = maxWidth.toInt()
        if (preeditMaxWidth != px) {
            preeditMaxWidth = px
            preeditUi.preedit.maxWidth = px
        }
    }

    /** Width the row may occupy: the parent, less the window's own spacing budget and padding. */
    private fun usableRowWidth(): Float {
        val parentWidth = parentSize[0]
        if (parentWidth <= 0f) return 0f
        return parentWidth - 2 * dp(SPACING) - paddingLeft - paddingRight
    }

    /**
     * 翻页：候选窗口整页显示，箭头直接让 Rime 换页。每页显示多少条由用户的
     * 页面大小（`menu/page_size`）决定，窗口不再按行数二次切片。
     */
    private fun turnPage(forward: Boolean) {
        rime.launchOnReady { it.changeCandidatePage(backward = !forward) }
    }

    /** 翻页提示当前的形态：true=预编辑行里的整组左右箭头，false=纵向那种 ▲▼ 小指示器。 */
    private var paginationArrowsInHeader = false

    /**
     * 翻页按钮只在确有可翻内容时出现：预编辑行是它唯一的位置，候选列表里没有它
     * （见 [PagedCandidatesUi]），所以横向纵向都靠这里，只是形态不同。
     */
    private fun updatePaginationIndicator(
        page: Candidates.Paged,
        show: Boolean,
        arrowsInHeader: Boolean,
    ) {
        paginationIndicator.update(page)
        paginationIndicator.root.visibility = if (show) VISIBLE else GONE
        // 两种形态共用一个视图，只有布局真的换了方向才重设，免得每次按键都多一趟布局。
        if (paginationArrowsInHeader != arrowsInHeader) {
            paginationArrowsInHeader = arrowsInHeader
            if (arrowsInHeader) {
                paginationIndicator.setHeaderRowMode()
            } else {
                paginationIndicator.setIndicatorMode()
            }
        }
        // 预编辑不显示时这一行高度为 0，翻页组会压到候选行上；至少给它留出自身高度。
        val reserveDp =
            if (show) {
                if (arrowsInHeader) {
                    PaginationUi.HEADER_ARROWS_HEIGHT_DP.toFloat()
                } else {
                    PaginationUi.INDICATOR_HEIGHT_DP.toFloat()
                }
            } else {
                0f
            }
        val minHeaderHeight = dp(reserveDp).toInt()
        if (headerUi.minimumHeight != minHeaderHeight) {
            headerUi.minimumHeight = minHeaderHeight
        }
    }

    /** 已经写进约束布局的预编辑位置。每次按键都会走 [updateUi]，值没变就不必重排。 */
    private var appliedPreeditPosition: PreeditPosition? = null

    /**
     * 预编辑行贴窗口上缘还是下缘，候选列表因此排在它下方还是上方。
     *
     * 翻页提示约束在预编辑行的上下两端（见 [init]），跟着这一行一起移动，无需单独处理。
     */
    private fun applyPreeditPosition() {
        val current = preeditPosition
        if (appliedPreeditPosition == current) return
        appliedPreeditPosition = current
        headerUi.layoutParams = headerLayoutParams()
        candidatesUi.root.layoutParams = candidatesLayoutParams()
    }

    private fun preeditAtBottom() = preeditPosition == PreeditPosition.BOTTOM

    private fun headerLayoutParams() = lParams(wrapContent, wrapContent) {
        if (preeditAtBottom()) bottomOfParent() else topOfParent()
        startOfParent()
        // 预编辑最多只能用到翻页组以左，超长时在剩余宽度内换行，
        // 翻页组因此不会像以前那样被顶出窗口。
        // 两端都有约束时默认居中，必须显式靠左，短预编辑才会贴着窗口左边。
        horizontalBias = 0f
        endToStartOf(paginationIndicator.root)
    }

    private fun candidatesLayoutParams() = lParams(matchConstraints, wrapContent) {
        matchConstraintMinWidth = wrapContent
        if (preeditAtBottom()) {
            topOfParent()
            above(headerUi)
        } else {
            below(headerUi)
            bottomOfParent()
        }
        centerHorizontally()
    }

    /** This window's background: the theme's own decoration, faded by the opacity setting. */
    private fun windowBackground(): Drawable? = ColorManager.getDecorDrawable(
        // 整窗一张图/色，与编码区色块分开
        colorKey = "candidate_window_background",
        borderColorKey = "candidate_border_color",
        borderPx = dp(theme.window.border),
        cornerRadius = dp(theme.window.cornerRadius),
        alpha = backgroundAlpha * 255 / 100,
    )

    private fun updatePosition() {
        if (visibility != VISIBLE) return
        val (parentWidth, parentHeight) = parentSize
        if (parentWidth <= 0 || parentHeight <= 0) {
            translationX = 0f
            translationY = 0f
            return
        }
        val (horizontal, top, _, bottom) = anchorPosition
        val w = width
        val h = height
        val selfWidth = w.toFloat()
        val selfHeight = h.toFloat()
        val spacingDp = dp(SPACING)
        val anchorTop = top - spacingDp
        val anchorBottom = bottom + spacingDp
        val bottomLimit = parentHeight - bottomInsets
        val bottomSpace = bottomLimit - anchorBottom

        val tX: Float
        val positionedY: Float

        val minX = spacingDp
        val minY = spacingDp
        val maxX = parentWidth - selfWidth - spacingDp
        val flipAbove = anchorBottom + selfHeight > bottomLimit && // bottom space is not enough
            anchorTop > bottomSpace // top space is larger than bottom
        val maxY = if (flipAbove) anchorTop - selfHeight else bottomLimit - selfHeight - spacingDp
        when (position) {
            PopupPosition.TOP_RIGHT -> {
                tX = maxX
                positionedY = minY
            }
            PopupPosition.TOP_LEFT -> {
                tX = minX
                positionedY = minY
            }
            PopupPosition.BOTTOM_RIGHT -> {
                tX = maxX
                positionedY = maxY
            }
            PopupPosition.BOTTOM_LEFT -> {
                tX = minX
                positionedY = maxY
            }
            PopupPosition.FOLLOW -> {
                tX =
                    if (layoutDirection == LAYOUT_DIRECTION_RTL) {
                        val rtlOffset = parentWidth - horizontal
                        if (rtlOffset + selfWidth > parentWidth - spacingDp) {
                            selfWidth - parentWidth + spacingDp
                        } else {
                            -rtlOffset
                        }
                    } else {
                        if (horizontal + selfWidth > parentWidth - spacingDp) {
                            parentWidth - selfWidth - spacingDp
                        } else {
                            horizontal
                        }
                    }
                positionedY = if (flipAbove) anchorTop - selfHeight else anchorBottom
            }
        }
        // The vertical axis is overridden while an obstruction is on screen (see
        // [setObstruction]); the chosen [position] still drives the horizontal one.
        val tY =
            candidatesWindowTranslationY(
                positionedY = positionedY,
                obstructionTop = obstruction.top.takeIf { !obstruction.isEmpty() },
                selfHeight = selfHeight,
                spacing = spacingDp,
                minY = minY,
            )
        translationX = tX
        translationY = tY
        // A modal IME dialog takes every tap on the screen (its scrim covers the input view).
        // This receiver is a window of its own, above the input view, so it has to step aside
        // or it would keep feeding taps to the candidate row through the dialog.
        if (service.isDialogShowing) {
            touchEventReceiverWindow.dismiss()
        } else {
            // update touchEventReceiverWindow's position after CandidatesView's
            touchEventReceiverWindow.showAt(tX.roundToInt(), tY.roundToInt(), w, h)
        }
        shouldUpdatePosition = false
    }

    /** Re-evaluates the touch receiver after a modal IME dialog appeared or went away. */
    fun onDialogVisibilityChanged() {
        if (service.isDialogShowing) {
            touchEventReceiverWindow.dismiss()
        } else {
            shouldUpdatePosition = true
            invalidate()
        }
    }

    fun updateCursorAnchor(
        anchorPosition: RectF,
        @Size(2) parent: FloatArray,
    ) {
        this.anchorPosition.set(anchorPosition)
        val (parentWidth, parentHeight) = parent
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        // The row is measured against the parent, so a new parent size re-lays it out as well.
        updateUi()
        updatePosition()
    }

    /**
     * Declares a region of the parent that this window must not cover, in the parent's
     * coordinate space, or `null` to clear it.
     *
     * A floating keyboard is the reason this exists: it overlays the editor, so it can appear
     * right underneath the cursor anchor, and the window would then be drawn on top of the
     * keys the user is aiming at.
     *
     * Relocation is deferred to the pre-draw pass -- callers include a layout callback, where
     * moving a window would re-enter layout.
     */
    fun setObstruction(rect: RectF?) {
        val next = rect?.takeIf { !it.isEmpty() }
        if (next == null) {
            if (obstruction.isEmpty()) return
            obstruction.setEmpty()
        } else {
            if (obstruction == next) return
            obstruction.set(next)
        }
        shouldUpdatePosition = true
        invalidate()
    }

    /**
     * Refreshes the area this window is placed against, without touching the cursor anchor.
     *
     * The height is the keyboard's top edge (or the whole content view while it floats), so it
     * changes whenever the keyboard changes height. That usually happens while the window is
     * hidden, and the value captured when it was last shown would then describe a keyboard that
     * no longer exists -- the window would be docked to the old edge and land on the keys.
     */
    fun updateParentSize(parent: FloatArray) {
        if (parentSize[0] == parent[0] && parentSize[1] == parent[1]) return
        val widthChanged = parentSize[0] != parent[0]
        parentSize[0] = parent[0]
        parentSize[1] = parent[1]
        // The row is measured against the parent's width, so only a new width re-splits it.
        if (widthChanged) updateUi()
        shouldUpdatePosition = true
        invalidate()
    }

    /**
     * Anchor candidates view to bottom-left corner, takes navbar bottom insets into consideration.
     * Should only be used when [CursorAnchorInfo][android.view.inputmethod.CursorAnchorInfo] is invalid
     */
    fun updateCursorAnchor(@Size(2) parent: FloatArray) {
        val (parentWidth, parentHeight) = parent
        val bottom = parentHeight - bottomInsets
        anchorPosition.set(0f, bottom, 0f, bottom)
        parentSize[0] = parentWidth
        parentSize[1] = parentHeight
        updateUi()
        updatePosition()
    }

    init {
        visibility = INVISIBLE

        minWidth = dp(theme.window.minWidth)
        verticalPadding = dp(theme.window.insets.vertical)
        horizontalPadding = dp(theme.window.insets.horizontal)
        alpha = theme.window.alpha
        background = windowBackground()
        clipToOutline = true
        outlineProvider = ViewOutlineProvider.BACKGROUND
        elevation = dp(theme.window.shadow)
        add(headerUi, headerLayoutParams())
        add(
            paginationIndicator.root,
            lParams(wrapContent, wrapContent) {
                // 与预编辑行上下对齐后居中：这一行比 20dp 的箭头组略高，
                // 只贴顶会让箭头看起来比文字更靠上。
                topToTopOf(headerUi)
                bottomToBottomOf(headerUi)
                endOfParent()
            },
        )
        add(candidatesUi.root, candidatesLayoutParams())
        appliedPreeditPosition = preeditPosition

        isFocusable = false
        layoutParams = ViewGroup.LayoutParams(wrapContent, wrapContent)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Reserve SPACING on both sides so that when updatePosition() docks the
        // window to a parent edge, its own spacing budget (minX/maxX = spacingDp)
        // is always achievable. Only cap when the spec constrains the width —
        // an UNSPECIFIED spec has no parent edges to reserve spacing from.
        val newWidthMeasureSpec =
            if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                widthMeasureSpec
            } else {
                val maxWidth = MeasureSpec.getSize(widthMeasureSpec) -
                    dp(2 * SPACING).roundToInt()
                MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
            }
        super.onMeasure(newWidthMeasureSpec, heightMeasureSpec)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            bottomInsets = getNavBarBottomInset(insets)
        }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
    }

    override fun setVisibility(visibility: Int) {
        if (visibility != VISIBLE) {
            touchEventReceiverWindow.dismiss()
        } else if (this.visibility != VISIBLE) {
            // Recalibrate on every show: while hidden, the keyboard may have changed height or
            // been dragged away, and reusing the translation from the previous show would leave
            // the window at a position that its current anchor/size no longer imply.
            shouldUpdatePosition = true
            invalidate()
        }
        super.setVisibility(visibility)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        touchEventReceiverWindow.dismiss()
        super.onDetachedFromWindow()
    }

    companion object {
        /**
         * Spacing in density-independent pixels (dp) kept between the candidate
         * window and the parent edges whenever the window docks to them.
         */
        private const val SPACING = 5f
    }
}

/**
 * Vertical translation of the candidate window.
 *
 * [positionedY] is the placement the chosen [PopupPosition] asks for. When [obstructionTop]
 * is given -- the top edge of a floating keyboard -- the window is pinned just above it
 * instead, so it never hides the keys. Pinning rather than merely nudging also keeps the
 * window still while the cursor moves around underneath the keyboard.
 *
 * A keyboard dragged all the way up leaves no room; the window is then clamped to [minY],
 * which is the top of the parent, rather than being pushed off screen.
 */
internal fun candidatesWindowTranslationY(
    positionedY: Float,
    obstructionTop: Float?,
    selfHeight: Float,
    spacing: Float,
    minY: Float,
): Float = if (obstructionTop == null) {
    positionedY
} else {
    (obstructionTop - spacing - selfHeight).coerceAtLeast(minY)
}
