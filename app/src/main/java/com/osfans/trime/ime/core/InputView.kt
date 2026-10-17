/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Height of the floating keyboard surface, whose drop shadow renders at this Z.
 */
internal const val FLOATING_KEYBOARD_ELEVATION_DP = 10f

/**
 * Z of the key popup layer (popup preview + popup keyboard).
 *
 * MUST stay strictly above [FLOATING_KEYBOARD_ELEVATION_DP]: the popup layer and the
 * keyboard are siblings, and Android paints siblings in ascending elevation order, so
 * an equal or lower value would let the floating keyboard cover the popups.
 */
internal const val POPUP_LAYER_ELEVATION_DP = 24f

internal fun resizedPercent(startPercent: Int, startPixels: Int, deltaPixels: Float, min: Int, max: Int): Int = (startPercent * (startPixels + deltaPixels) / startPixels).roundToInt().coerceIn(min, max)

/**
 * Narrowest the floating keyboard is allowed to get, in dp.
 *
 * The width preference is a share, and the share is taken against a reference that survives a
 * rotation (see [widthPercentForLayout]). On a device whose window is 1200px wide in portrait and
 * 2670px in landscape, a share of the *window* would make the same setting mean 1116px one way and
 * 2483px the other -- "too big in landscape, tiny in portrait". Pinning the floor to an absolute
 * size as well keeps the keyboard usable no matter how wide the window gets.
 */
internal const val FLOATING_KEYBOARD_MIN_WIDTH_DP = 160

/**
 * Lower bound of the width setting, mirroring `AppPrefs.Keyboard.floatingKeyboardWidth`'s slider.
 *
 * The absolute dp minimum above usually lands above this on a phone, so this only matters on very
 * narrow screens -- but it must never allow a width the settings slider cannot represent.
 */
internal const val MIN_FLOATING_KEYBOARD_WIDTH_PERCENT = 40

/**
 * Turns a width *share of [referenceWidthPx]* into the share of [windowWidthPx] the layout needs.
 *
 * This is what makes a chosen width survive a rotation: the reference is the screen's short side,
 * which is the same in both orientations, so the same setting produces the same physical width
 * instead of following the window around. Returns 100 when the geometry is not known yet (before
 * the first layout), which leaves the keyboard full width rather than guessing.
 */
internal fun widthPercentForLayout(
    widthPercent: Int,
    referenceWidthPx: Int,
    windowWidthPx: Int,
): Int {
    if (referenceWidthPx <= 0 || windowWidthPx <= 0) return 100
    val share = (widthPercent.toLong() * referenceWidthPx / windowWidthPx).toInt()
    return share.coerceIn(1, 100)
}

/**
 * Smallest share of [referenceWidthPx] that still leaves [minWidthDp] pixels of keyboard.
 *
 * Only bites on very wide windows (landscape, tablets); on a narrow one the ordinary lower bound
 * of the setting is already the larger of the two.
 */
internal fun minWidthPercentFor(
    minWidthDp: Int,
    referenceWidthPx: Int,
    density: Float,
): Int {
    if (referenceWidthPx <= 0 || density <= 0f) return 1
    val minPx = minWidthDp * density
    return (minPx * 100f / referenceWidthPx).roundToInt().coerceAtLeast(1)
}

/**
 * Horizontal scale that makes the composed width equal [widthPercent] percent of the width floor.
 */
internal fun floatingScaleX(widthPercent: Int, baseWidthPercent: Int): Float {
    if (baseWidthPercent <= 0) return 1f
    return widthPercent.toFloat() / baseWidthPercent
}

/**
 * Horizontal translation that keeps the floating keyboard within the window.
 *
 * The obvious `coerceIn(margin - left, window - margin - left - width)` collapses once the
 * keyboard is as wide as the window: the upper bound goes negative while the lower one stays
 * positive, and guarding it with `coerceAtLeast` pins the card to the left *margin* -- shoving it
 * 8dp to the right with its right edge hanging off the screen. That is not a corner case either:
 * 100% is the default width, and a card that cannot sit inside both margins has no such position,
 * so it is placed flush with the left edge instead. That is the only side the user can still
 * reach, and the right edge is where a full-width card has nothing left to give.
 */
internal fun floatingTranslationX(
    requestedX: Float,
    cardLeft: Int,
    visualWidth: Float,
    windowWidth: Int,
    margin: Float,
): Float {
    if (visualWidth >= windowWidth - 2 * margin) return 0f
    val min = margin - cardLeft
    val max = (windowWidth - margin - cardLeft - visualWidth).coerceAtLeast(min)
    return requestedX.coerceIn(min, max)
}

/**
 * Vertical scale for the floating keyboard.
 *
 * The toolbar keeps its themed height while the key area absorbs the whole delta. The delta is
 * expressed over [referenceHeight] (the IME window height, i.e. the space the keyboard can grow
 * into) and mapped onto [containerHeight] (the keyboard's own measured height), which is the
 * geometry the drag has always used.
 */
internal fun floatingScaleY(
    heightPercent: Int,
    baseHeightPercent: Int,
    containerHeight: Int,
    referenceHeight: Int,
): Float {
    if (baseHeightPercent <= 0 || containerHeight <= 0) return 1f
    return 1f +
        referenceHeight.toFloat() / containerHeight *
        (heightPercent - baseHeightPercent) / baseHeightPercent.toFloat()
}

/**
 * 悬浮键盘的容器，唯一职责是识别双指缩放手势。
 *
 * 缩放只由双指触发：两指横向间距的变化调宽度，纵向间距的变化调高度，对角线方向则
 * 两者同时生效；任一手指离开（包括两指变一指）立即结束并落盘。移动窗口只走顶部
 * 拖动条，键盘按键区域的单指触摸不再有任何窗口手势——按键紧贴卡片边缘，边缘热区
 * 的方案已验证会吞掉外圈按键的点击。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class FloatingKeyboardContainer(
    context: Context,
    floating: Boolean,
    private val onPinchStart: () -> Unit,
    private val onPinch: (spanDeltaX: Float, spanDeltaY: Float) -> Unit,
    private val onPinchEnd: () -> Unit,
) : ConstraintLayout(context) {
    var floating: Boolean = floating
        private set

    private var pinching = false
    private var pinchSpanX0 = 0f
    private var pinchSpanY0 = 0f

    /** 0/1 两指的横向与纵向间距（张开为正、捏合为负的差值基准）。 */
    private fun spans(event: MotionEvent): Pair<Float, Float> = abs(event.getX(0) - event.getX(1)) to abs(event.getY(0) - event.getY(1))

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!floating) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                if (!pinching && event.pointerCount == 2) {
                    pinching = true
                    val (spanX, spanY) = spans(event)
                    pinchSpanX0 = spanX
                    pinchSpanY0 = spanY
                    // 拦截整段手势：进入缩放后按键不再收到任何事件（已按下的键收到 CANCEL）。
                    onPinchStart()
                    return true
                }

            MotionEvent.ACTION_MOVE -> if (pinching) return true

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pinching) endPinch()
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!pinching) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (event.pointerCount >= 2) {
                    val (spanX, spanY) = spans(event)
                    onPinch(spanX - pinchSpanX0, spanY - pinchSpanY0)
                } else {
                    // 两指变一指：立即结束，避免剩余单指继续改变尺寸。
                    endPinch()
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPinch()
        }
        return true
    }

    private fun endPinch() {
        if (!pinching) return
        pinching = false
        onPinchEnd()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Slim bar across the top of a floating keyboard: dragging it moves the window, the way a
 * system floating window's top strip does. Draws only a centred horizontal line so the card's
 * own background keeps showing through.
 */
@SuppressLint("ViewConstructor")
private class FloatingTitleBar(
    context: Context,
    private val onDrag: (MotionEvent) -> Boolean,
    private val onDragMode: (Boolean) -> Unit,
) : View(context) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val lineWidth = context.dp(32f)
    private val lineHeight = context.dp(3.5f)

    fun setGripColor(backgroundColor: Int) {
        line.color = if (ColorUtils.calculateLuminance(backgroundColor) > 0.5) Color.DKGRAY else Color.WHITE
        line.alpha = 230
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = (width - lineWidth) / 2f
        val top = (height - lineHeight) / 2f
        canvas.drawRoundRect(left, top, left + lineWidth, top + lineHeight, lineHeight / 2f, lineHeight / 2f, line)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                onDragMode(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragMode(false)
        }
        onDrag(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    theme: Theme,
) : BaseInputView(service, rime, theme) {
    private val keyboardPrefs = AppPrefs.defaultInstance().keyboard

    var isFloatingKeyboardEnabled = keyboardPrefs.floatingKeyboard.getValue()
        private set

    /**
     * Percentage the surface would occupy with no extra scaling, i.e. the value the scale
     * factors are expressed against.
     *
     * Mutable on purpose: after a resize the drag *persists* new percentages, and the surface
     * is then rebuilt at those percentages with `scale = 1`. If these stayed pinned to the
     * values captured at construction, the next resize would apply the new ratio on top of an
     * already-scaled surface and the size would run away. See [updateFloatingResize].
     */
    private var floatingKeyboardBaseWidth = keyboardPrefs.floatingKeyboardWidth.getValue()
    private var floatingKeyboardBaseHeight = keyboardPrefs.floatingKeyboardHeight.getValue()
    private var floatingKeyboardWidth = floatingKeyboardBaseWidth
    private var floatingKeyboardHeight = floatingKeyboardBaseHeight
    private val floatingPreeditOffsetX = keyboardPrefs.floatingPreeditOffsetX.getValue()
    private val floatingPreeditOffsetY = keyboardPrefs.floatingPreeditOffsetY.getValue()

    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job

    private val inputDepMgr = InputDependencyManager.initialize(this, themedContext, theme, service, rime)
    private val di = inputDepMgr.di
    private val broadcaster: InputBroadcaster by di.instance()
    private val popup: PopupDelegate by di.instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by di.instance()
    private val preedit: PreeditDelegate by di.instance()
    private val windowManager: BoardWindowManager by di.instance()
    private val inputBar: InputBarDelegate by di.instance()
    private val keyboardWindow: KeyboardWindow by di.instance()
    private val liquidWindow: LiquidWindow by di.instance()

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.generalStyle.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.generalStyle.keyboardPaddingLand
    private val keyboardBottomPadding = theme.generalStyle.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.generalStyle.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        // Read through the cached accessors rather than rime.run { }: this runs on every rime
        // message, and a keystroke produces several of them, so three runBlocking round trips
        // per message added up to over a dozen stalls of the UI thread per keypress. The cached
        // properties read @Volatile snapshots and never touch the native layer.
        val current = Triple(rime.status.isComposing, rime.hasMenu, rime.paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    val keyboardView: View

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartTranslationX = 0f
    private var dragStartTranslationY = 0f
    private var dragMoved = false
    private var lastHandleTapTime = 0L
    private val dragTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var resizeStartWidth = 0
    private var resizeStartHeight = 0
    private var resizeStartScaleX = 1f
    private var resizeStartScaleY = 1f
    private var resizeStartWidthPercent = floatingKeyboardWidth
    private var resizeStartHeightPercent = floatingKeyboardHeight
    private var resizedWidth = floatingKeyboardWidth
    private var resizedHeight = floatingKeyboardHeight

    /**
     * Window-space bounds of the floating layer as last reported to the IME window.
     *
     * Guarded by [hasLastFloatingBounds] because the bounds are unknown (and the whole
     * floating layout is inactive) until the keyboard is actually laid out in floating mode.
     */
    private val lastFloatingBounds = Rect()
    private var hasLastFloatingBounds = false

    private val titleBar =
        FloatingTitleBar(context, { handleFloatingDrag(it) }) { onFloatingDragMode(it) }

    /**
     * The width the floating width percentage is measured against: the screen's **short** side.
     *
     * A rotation swaps the window's width and height, so a share of the window itself would mean
     * two different physical sizes for one setting — the keyboard ends up filling a landscape
     * screen and looking tiny in portrait. The short side is the one dimension a rotation leaves
     * alone, which is what makes the chosen width hold still across the turn.
     */
    private fun referenceWidthPx(): Int {
        val metrics = resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** The share [minWidthPercent] of the reference width, floored by an absolute size. */
    private fun minWidthPercent(): Int = minWidthPercentFor(
        minWidthDp = FLOATING_KEYBOARD_MIN_WIDTH_DP,
        referenceWidthPx = referenceWidthPx(),
        density = resources.displayMetrics.density,
    ).coerceAtLeast(MIN_FLOATING_KEYBOARD_WIDTH_PERCENT)

    /** Converts the stored width share into the share the layout has to use. */
    private fun layoutWidthPercent(widthPercent: Int): Int = widthPercentForLayout(
        widthPercent = widthPercent,
        referenceWidthPx = referenceWidthPx(),
        windowWidthPx = width,
    )

    /** 双指按下时记录缩放基线（当前尺寸/缩放/百分比）。 */
    private fun beginFloatingResize() {
        resizeStartWidth = keyboardView.width
        resizeStartHeight = windowManager.view.height
        resizeStartScaleX = keyboardView.scaleX
        resizeStartScaleY = keyboardView.scaleY
        resizeStartWidthPercent = floatingKeyboardWidth
        resizeStartHeightPercent = floatingKeyboardHeight
        resizedWidth = resizeStartWidthPercent
        resizedHeight = resizeStartHeightPercent
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
    }

    /** 两指间距变化驱动尺寸：横向间距差调宽、纵向间距差调高，对角线两者同时生效。 */
    private fun updateFloatingResize(spanDeltaX: Float, spanDeltaY: Float) {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        if (keyboardPrefs.floatingKeyboardKeepRatio.getValue()) {
            // 等比模式：任一方向的捏合都驱动同一个缩放因子（取主导轴的间距差），
            // 高度由 scaleX == scaleY 的几何关系从宽度反解，键盘内容永不变形。
            val spanDelta = if (abs(spanDeltaX) >= abs(spanDeltaY)) spanDeltaX else spanDeltaY
            resizedWidth = resizedPercent(
                resizeStartWidthPercent,
                (resizeStartWidth * resizeStartScaleX).roundToInt(),
                spanDelta,
                minWidthPercent(),
                100,
            )
            resizedHeight = keepRatioHeightFor(resizedWidth).coerceIn(70, 130)
        } else {
            resizedWidth = resizedPercent(
                resizeStartWidthPercent,
                (resizeStartWidth * resizeStartScaleX).roundToInt(),
                spanDeltaX,
                minWidthPercent(),
                100,
            )
            resizedHeight = resizedPercent(
                resizeStartHeightPercent,
                (resizeStartHeight * resizeStartScaleY).roundToInt(),
                spanDeltaY,
                70,
                130,
            )
        }
        keyboardView.scaleX = floatingScaleX(resizedWidth, floatingKeyboardBaseWidth)
        // The toolbar height is fixed, while the key area follows the height preference.
        keyboardView.scaleY =
            floatingScaleY(
                resizedHeight,
                floatingKeyboardBaseHeight,
                keyboardView.height,
                resizeStartHeight,
            )
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    /**
     * 等比模式下与 [widthPercent] 联动的高度百分比：令 [floatingScaleY] 与 [floatingScaleX]
     * 相等，即键盘内容横纵向同步缩放（不变形）。参考高度未知（从未开始过缩放手势）时两者
     * 同参考，公式退化为百分比空间的等比——设置页滑条联动走的就是这条路径。
     */
    fun keepRatioHeightFor(widthPercent: Int): Int {
        val scaleX = floatingScaleX(widthPercent, floatingKeyboardBaseWidth)
        val containerH = keyboardView.height
        val refH = resizeStartHeight.takeIf { it > 0 } ?: containerH
        val base = floatingKeyboardBaseHeight
        if (base <= 0 || containerH <= 0 || refH <= 0) return base
        return (base + (scaleX - 1f) * base * containerH.toFloat() / refH).roundToInt()
    }

    /** 等比模式下与 [heightPercent] 联动的宽度百分比（[keepRatioHeightFor] 的逆运算）。 */
    fun keepRatioWidthFor(heightPercent: Int): Int {
        val containerH = keyboardView.height
        val refH = resizeStartHeight.takeIf { it > 0 } ?: containerH
        val scaleY = floatingScaleY(heightPercent, floatingKeyboardBaseHeight, containerH, refH)
        return (floatingKeyboardBaseWidth * scaleY).roundToInt().coerceAtLeast(minWidthPercent())
    }

    /** 手指离开（两指变一指也算）时把当前尺寸落盘，作为下次缩放的基线。 */
    private fun endFloatingResize() {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        floatingKeyboardWidth = resizedWidth
        floatingKeyboardHeight = resizedHeight
        // The surface is now persisted at these percentages, so they *are* the baseline:
        // the width reaches them through the layout (the preference drives the measured
        // width), and the height has no layout carrier at all, so it still has to be
        // expressed as a scale. Pinning the baseline here is what keeps a second resize
        // from compounding onto the previous one.
        floatingKeyboardBaseWidth = resizedWidth
        floatingKeyboardBaseHeight = resizedHeight
        resizeStartWidth = 0
        resizeStartHeight = 0
        keyboardView.updateLayoutParams<LayoutParams> {
            matchConstraintPercentWidth = layoutWidthPercent(resizedWidth) / 100f
        }
        keyboardView.doOnLayout { applyFloatingScale() }
        service.updateFloatingKeyboardSize(resizedWidth, resizedHeight)
        service.requestInputInsetsUpdate()
    }

    /**
     * Pushes the persisted size preferences onto the surface as `scaleX` / `scaleY`.
     *
     * The width is also carried by the layout ([`matchConstraintPercentWidth`]), so its scale is
     * normally identity and only differs while the container still measures at the previous
     * percentage. The height has no layout carrier -- the key grid's height comes from the theme,
     * not from a preference -- so this scale is the *only* thing that keeps the height preference
     * alive across a rebuild. Without it a rebuild silently drops back to the baseline height
     * while the width is honoured, which reads as "the keyboard's proportions are wrong".
     */
    private fun applyFloatingScale() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0 || keyboardView.height == 0) return
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
        keyboardView.scaleX = 1f
        keyboardView.scaleY =
            floatingScaleY(
                floatingKeyboardHeight,
                floatingKeyboardBaseHeight,
                keyboardView.height,
                windowManager.view.height,
            )
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    private fun syncFloatingPreeditPosition() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0) return
        val preeditRoot = preedit.ui.root
        if (preeditRoot.layoutParams.width != keyboardView.width) {
            preeditRoot.updateLayoutParams {
                width = keyboardView.width
            }
        }
        preeditRoot.translationX =
            keyboardView.left + keyboardView.translationX - preeditRoot.left + dp(floatingPreeditOffsetX)
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        // The extra 1 dp overlap hides fractional-pixel seams between the square
        // preedit bottom edge and the floating keyboard's rounded top edge.
        preeditRoot.translationY = keyboardView.translationY + dp(floatingPreeditOffsetY + 1)
        preedit.updateTouchReceiverPosition()
    }

    private fun applyFloatingPosition(
        requestedX: Float,
        requestedY: Float,
        persist: Boolean,
    ) {
        if (!isFloatingKeyboardEnabled || width == 0 || keyboardView.width == 0) return

        val horizontalMargin = dp(8).toFloat()
        val visualWidth = keyboardView.width * keyboardView.scaleX
        val resolvedX =
            floatingTranslationX(
                requestedX = requestedX,
                cardLeft = keyboardView.left,
                visualWidth = visualWidth,
                windowWidth = width,
                margin = horizontalMargin,
            )
        val floatingTop =
            preedit.ui.root
                .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                ?.let { it.top + dp(floatingPreeditOffsetY + 1) }
                ?: keyboardView.top
        val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
        val maxTranslationY =
            (height - dp(8) - keyboardView.top - keyboardView.height * keyboardView.scaleY)
                .coerceAtLeast(minTranslationY)
        val resolvedY = requestedY.coerceIn(minTranslationY, maxTranslationY)

        keyboardView.translationX = resolvedX
        keyboardView.translationY = resolvedY
        syncFloatingPreeditPosition()
        requestInsetsUpdateIfFloatingBoundsChanged()

        if (persist) {
            keyboardPrefs.floatingKeyboardOffsetX.setValue(resolvedX.roundToInt())
            keyboardPrefs.floatingKeyboardOffsetY.setValue(resolvedY.roundToInt())
        }
    }

    /**
     * Re-specify the IME window's touchable region, but only when it actually moved.
     *
     * [TrimeInputMethodService.requestInputInsetsUpdate] lays the *entire* input view out
     * again and makes the framework call `onComputeInsets`, which pushes a new touchable
     * region to the window manager. Floating mode re-runs [applyFloatingPosition] for every
     * rime composition message -- that is, once per keystroke -- so an unconditional call
     * here means a full input-view measure/layout plus a window manager transaction per key.
     * The UI thread then trails the input queue and the keyboard appears to freeze.
     *
     * The region is a pure function of the floating layer's position and size, so an
     * unchanged region needs no update. Only [applyFloatingPosition] is throttled this way:
     * drags must keep the region in step with the finger, otherwise the framework would
     * send the rest of the gesture to the app behind the keyboard.
     */
    private fun requestInsetsUpdateIfFloatingBoundsChanged() {
        val bounds = Rect()
        if (getFloatingKeyboardBoundsInWindow(bounds)) {
            if (hasLastFloatingBounds && bounds == lastFloatingBounds) return
            lastFloatingBounds.set(bounds)
            hasLastFloatingBounds = true
        } else {
            // Not measurable yet; report unconditionally and stop comparing.
            hasLastFloatingBounds = false
        }
        service.requestInputInsetsUpdate()
    }

    private fun resetFloatingPosition() {
        lastHandleTapTime = 0L
        applyFloatingPosition(0f, 0f, persist = true)
    }

    private fun handleFloatingDrag(event: MotionEvent, allowTap: Boolean = true): Boolean {
        if (!isFloatingKeyboardEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDownRawX = event.rawX
                dragDownRawY = event.rawY
                dragStartTranslationX = keyboardView.translationX
                dragStartTranslationY = keyboardView.translationY
                dragMoved = false
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - dragDownRawX
                val deltaY = event.rawY - dragDownRawY
                if (abs(deltaX) > dragTouchSlop || abs(deltaY) > dragTouchSlop) {
                    dragMoved = true
                }
                applyFloatingPosition(
                    dragStartTranslationX + deltaX,
                    dragStartTranslationY + deltaY,
                    persist = false,
                )
            }

            MotionEvent.ACTION_UP -> {
                if (dragMoved || !allowTap) {
                    applyFloatingPosition(
                        keyboardView.translationX,
                        keyboardView.translationY,
                        persist = true,
                    )
                } else if (event.eventTime - lastHandleTapTime <= ViewConfiguration.getDoubleTapTimeout()) {
                    resetFloatingPosition()
                } else {
                    lastHandleTapTime = event.eventTime
                    keyboardView.performClick()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                applyFloatingPosition(
                    keyboardView.translationX,
                    keyboardView.translationY,
                    persist = true,
                )
            }
        }
        return true
    }

    fun getFloatingKeyboardBoundsInWindow(outBounds: Rect): Boolean {
        if (!isFloatingKeyboardEnabled || !keyboardView.isShown || keyboardView.width == 0) return false
        keyboardView.getLocationInWindow(inputViewLocation)
        val visualWidth = (keyboardView.width * keyboardView.scaleX).roundToInt()
        val visualHeight = (keyboardView.height * keyboardView.scaleY).roundToInt()
        outBounds.set(
            inputViewLocation[0],
            inputViewLocation[1],
            inputViewLocation[0] + visualWidth,
            inputViewLocation[1] + visualHeight,
        )
        val preeditRoot = preedit.ui.root
        if (preeditRoot.visibility == View.VISIBLE && preeditRoot.width > 0 && preeditRoot.height > 0) {
            preeditRoot.getLocationInWindow(preeditViewLocation)
            outBounds.union(
                preeditViewLocation[0],
                preeditViewLocation[1],
                preeditViewLocation[0] + preeditRoot.width,
                preeditViewLocation[1] + preeditRoot.height,
            )
        }
        return true
    }

    private val inputViewLocation = intArrayOf(0, 0)
    private val preeditViewLocation = intArrayOf(0, 0)

    fun refreshHideInputBar() {
        inputBar.onHideInputBarChanged()
    }

    /**
     * 窗口手势模式切换的通知：进入时给一次长按触感震动，松手自动结束。
     * 触发点两处——顶部拖动条按下（[FloatingTitleBar]）、双指进入缩放
     * （[FloatingKeyboardContainer.onPinchStart]）。
     */
    private fun onFloatingDragMode(active: Boolean) {
        if (active) {
            keyboardView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** 悬浮卡片的实际底色，由 [configureFloatingSurface] 刷新，拖动横线的对比色取自它。 */
    private var floatingBackColor: Int = Color.WHITE

    /**
     * 悬浮卡片的底色。
     *
     * keyboard_back_color 在不少主题里是图片引用（默认 trime 主题就是 `@background`），而
     * [ColorManager.getColor] 遇到图片值会把键名当颜色字符串解析，直接抛异常——所以这里
     * 必须走 [ColorManager.getDrawable]：纯色（GradientDrawable）取其色值；图片无法提炼
     * 单色，改用跟随夜间的 Material 兜底色，悬浮卡片本来就是系统气泡风格的纯色面板。
     */
    private fun resolveFloatingBackColor(): Int = when (val drawable = ColorManager.getDrawable("keyboard_back_color")) {
        is GradientDrawable -> drawable.color?.defaultColor ?: fallbackFloatingBackColor()
        else -> fallbackFloatingBackColor()
    }

    private fun fallbackFloatingBackColor(): Int = if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) {
        0xFF1C1B1F.toInt()
    } else {
        0xFFFFFBFE.toInt()
    }

    /** Styles the floating card and the drag title bar; docked mode strips both. */
    private fun configureFloatingSurface(enabled: Boolean) {
        val surface = keyboardView as FloatingKeyboardContainer
        val backColor = resolveFloatingBackColor()
        floatingBackColor = backColor
        if (enabled) {
            surface.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f)
                setColor(backColor)
            }
            surface.clipToOutline = true
            surface.elevation = dp(FLOATING_KEYBOARD_ELEVATION_DP)
            surface.isClickable = true
            surface.contentDescription = context.getString(R.string.floating_keyboard_drag_handle)
            surface.setOnClickListener { }
        } else {
            surface.background = null
            surface.clipToOutline = false
            surface.elevation = 0f
            surface.isClickable = false
            surface.contentDescription = null
            surface.setOnClickListener(null)
        }

        titleBar.apply {
            visibility = if (enabled) View.VISIBLE else View.GONE
            isClickable = enabled
            contentDescription =
                if (enabled) context.getString(R.string.floating_keyboard_drag_handle) else null
            setGripColor(backColor)
        }
    }

    init {
        // MUST call before any operation
        inputDepMgr.start()

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = ColorManager.getDrawable("keyboard_background")

        keyboardView =
            FloatingKeyboardContainer(
                context,
                isFloatingKeyboardEnabled,
                onPinchStart = {
                    beginFloatingResize()
                    onFloatingDragMode(true)
                },
                onPinch = ::updateFloatingResize,
                onPinchEnd = {
                    endFloatingResize()
                    onFloatingDragMode(false)
                },
            ).apply {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        below(titleBar)
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
                // 拖动横线必须画在键盘背景图之上：keyboardBackground 的图片按内在尺寸
                // 居中铺开，会盖住顶部 16dp 区域——最后添加才能保证 titleBar 在最顶层。
                add(
                    titleBar,
                    lParams(matchParent, dp(16)) {
                        topOfParent()
                    },
                )
            }

        configureFloatingSurface(isFloatingKeyboardEnabled)

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    if (windowManager.isAttached(keyboardWindow)) {
                        windowManager.view.updateLayoutParams {
                            height = it
                        }
                    }
                }
            }

        updateKeyboardSize()

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        add(
            keyboardView,
            lParams(if (isFloatingKeyboardEnabled) matchConstraints else matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
                if (isFloatingKeyboardEnabled) {
                    matchConstraintPercentWidth = layoutWidthPercent(floatingKeyboardWidth) / 100f
                    bottomMargin = dp(18)
                }
            },
        )

        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            syncFloatingPreeditPosition()
        }
        if (isFloatingKeyboardEnabled) {
            keyboardView.post {
                // Re-apply the persisted height percentage: the key grid's height comes from the
                // theme, so nothing else would carry it across this rebuild.
                applyFloatingScale()
                applyFloatingPosition(
                    keyboardPrefs.floatingKeyboardOffsetX.getValue().toFloat(),
                    keyboardPrefs.floatingKeyboardOffsetY.getValue().toFloat(),
                    persist = false,
                )
            }
        }

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
        // [popup.root] and [keyboardView] are siblings under this ConstraintLayout.
        // A ViewGroup that holds any child with a non-zero Z re-orders its children
        // by elevation, so once the floating keyboard raises itself with
        // [FLOATING_KEYBOARD_ELEVATION_DP] it would be painted *after* the popup
        // layer and hide the key popup preview / popup keyboard. Keeping the popup
        // layer above that keeps the popup on top in both floating and docked layouts.
        popup.root.elevation = dp(POPUP_LAYER_ELEVATION_DP)
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }
            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                broadcaster.onCompositionUpdate(data)
                if (isFloatingKeyboardEnabled) {
                    preedit.ui.root.post {
                        applyFloatingPosition(
                            keyboardView.translationX,
                            keyboardView.translationY,
                            persist = false,
                        )
                    }
                }
            }
            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }
            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        inputDepMgr.stop()
        super.onDetachedFromWindow()
    }
}
