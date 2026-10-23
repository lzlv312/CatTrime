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
import androidx.annotation.Keep
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
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.dependency.InputDependencyManager
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.util.isLandscape
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
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Height of the floating keyboard surface, whose drop shadow renders at this Z.
 */
internal const val FLOATING_KEYBOARD_ELEVATION_DP = 8f

/** 双指开始驱动缩放前，间距至少要移动的距离（dp）——小于它的移动视为手抖。 */
internal const val PINCH_ARM_THRESHOLD_DP = 12f

/** 缩放驱动的死区：小于它的两指间距变化不驱动尺寸（过滤“按住轻移”的抖动）。 */
internal const val PINCH_DEAD_ZONE_DP = 2f

/** 缩放的触控阻尼：间距每变化 2px，尺寸百分比走 1px 的当量，进一步压抖动。 */
internal const val PINCH_DAMPING = 0.5f

/**
 * Z of the key popup layer (popup preview + popup keyboard).
 *
 * MUST stay strictly above [FLOATING_KEYBOARD_ELEVATION_DP]: the popup layer and the
 * keyboard are siblings, and Android paints siblings in ascending elevation order, so
 * an equal or lower value would let the floating keyboard cover the popups.
 */
internal const val POPUP_LAYER_ELEVATION_DP = 24f

/**
 * Z of the floating preedit layer.
 *
 * Must sit strictly above [FLOATING_KEYBOARD_ELEVATION_DP] (so the vertical preedit
 * offset can push it over the drag bar -- while composing dragging is not needed) and
 * strictly below [POPUP_LAYER_ELEVATION_DP] (key popups stay on top). The preedit root
 * has no background, so this Z adds no shadow of its own.
 */
internal const val PREEDIT_LAYER_ELEVATION_DP = 12f

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
 * 与 [floatingScaleX] 对称的绝对档位换算：scaleY = 高度档位 / 基准档位。曾经用
 * 「窗口高度/键盘内容高度」作放大系数（1 + ref/cont·(h-base)/base），但悬浮模式下窗口
 * 比键盘内容高得多，一档高度变化被放大数倍，表现为内容纵向过度拉伸、底边大幅溢出、
 * 卡片被补偿逻辑一路推到屏幕顶部。档位对档位的绝对换算与宽度行为一致，两个模式
 * （等比/非等比）因此共用同一套双指捏合数理。
 */
internal fun floatingScaleY(
    heightPercent: Int,
    baseHeightPercent: Int,
): Float {
    if (baseHeightPercent <= 0) return 1f
    return heightPercent.toFloat() / baseHeightPercent
}

/**
 * 悬浮键盘的容器，唯一职责是识别双指缩放手势。
 *
 * 第二指落下时**不拦截**：事件照常分发给按键，真正的双指输入（两指各按一键）不受任何
 * 影响。只有间距变化超过 [PINCH_ARM_THRESHOLD_DP] 才判定为缩放意图——此刻才拦截手势
 * （已按下的键收到 CANCEL）、触发 [onPinchStart]（震动）并开始驱动尺寸；驱动后的间距差
 * 经 [PINCH_DAMPING] 阻尼（1:2）换算压手抖。任一手指离开（包括两指变一指）立即结束。
 * 未超过阈值就抬手 = 一次普通输入，全程无震动、无尺寸变化。移动窗口只走拖动条。
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

    /** 两指都在屏幕上（尚未判定为输入或缩放）。 */
    private var pinching = false

    /** 已判定为缩放并拦截手势；此前的移动属于正常输入，不驱动尺寸。 */
    private var armed = false
    private var pinchSpanX0 = 0f
    private var pinchSpanY0 = 0f
    private val armThreshold = context.dp(PINCH_ARM_THRESHOLD_DP)

    /** 0/1 两指的横向与纵向间距（张开为正、捏合为负的差值基准）。 */
    private fun spans(event: MotionEvent): Pair<Float, Float> = abs(event.getX(0) - event.getX(1)) to abs(event.getY(0) - event.getY(1))

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!floating) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                if (!pinching && event.pointerCount == 2) {
                    pinching = true
                    armed = false
                    val (spanX, spanY) = spans(event)
                    pinchSpanX0 = spanX
                    pinchSpanY0 = spanY
                    // 不拦截：让两个按键正常收事件，先当作一次双指输入观察。
                    return false
                }

            MotionEvent.ACTION_MOVE ->
                if (pinching && !armed) {
                    val (spanX, spanY) = spans(event)
                    if (hypot(spanX - pinchSpanX0, spanY - pinchSpanY0) >= armThreshold) {
                        // 判定为缩放：此刻才拦截 + 震动，并把基准挪到当前位置。
                        armed = true
                        pinchSpanX0 = spanX
                        pinchSpanY0 = spanY
                        onPinchStart()
                        return true
                    }
                    return false
                } else if (pinching) {
                    return true
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (pinching) {
                    val wasArmed = armed
                    pinching = false
                    armed = false
                    if (wasArmed) {
                        onPinchEnd()
                        return false // 已拦截过，让系统结束本手势序列
                    }
                }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!armed) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE ->
                if (event.pointerCount >= 2) {
                    val (spanX, spanY) = spans(event)
                    onPinch(
                        (spanX - pinchSpanX0) * PINCH_DAMPING,
                        (spanY - pinchSpanY0) * PINCH_DAMPING,
                    )
                } else {
                    // 两指变一指：立即结束，避免剩余单指继续改变尺寸。
                    endPinch()
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPinch()
        }
        return true
    }

    private fun endPinch() {
        if (!armed) return
        armed = false
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
        line.alpha = 204
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

    // 「有效悬浮」（KeyboardPrefs.isFloatingEffective）：横屏回落普通键盘时为
    // false，恢复原始 landscape_keyboard 行为。构造期一次性求值；横屏悬浮偏好
    // 变化经 recreateInputViewPrefs 重建输入视图生效。
    var isFloatingKeyboardEnabled = service.isFloatingEffective()
        private set

    /**
     * 宽/高档位偏好镜像（双指缩放松手落盘时更新）。
     *
     * 缩放的「基准」不在这里：网格的像素几何由 Keyboard 对象构造时按当次档位烘焙死，
     * 基准跟随当前 Keyboard 的烘焙值（见 [floatingKeyboardBaseWidth]）——宽度变化触发的
     * refreshKeyboards 会按新偏好重新构造 Keyboard，基准随新烘焙值走、缩放立即归一，
     * 布局与 scaleY 不会把同一档位施加两次。
     */
    private var floatingKeyboardWidth = keyboardPrefs.floatingKeyboardWidth.getValue()
    private var floatingKeyboardHeight = keyboardPrefs.floatingKeyboardHeight.getValue()

    /** 缩放基准 = 当前键盘构造时烘焙的档位；键盘尚未挂载时退回偏好镜像。 */
    private val floatingKeyboardBaseWidth: Int
        get() = keyboardWindow.currentBakedFloatingWidthPercent ?: floatingKeyboardWidth

    private val floatingKeyboardBaseHeight: Int
        get() = keyboardWindow.currentBakedFloatingHeightPercent ?: floatingKeyboardHeight
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

    private val oneHandMode = AppPrefs.defaultInstance().keyboard.oneHandMode

    @Keep
    private val oneHandModeListener =
        PreferenceDelegate.OnChangeListener<AppPrefs.Keyboard.OneHandMode> { _, _ ->
            ContextCompat.getMainExecutor(service).execute {
                updateKeyboardSize()
                keyboardWindow.refreshKeyboards(true)
            }
        }

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
    private var resizeStartTranslationX = 0f
    private var resizeStartTranslationY = 0f
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
        // init 阶段 InputView 尚未布局（width=0），传 view 宽度会让 widthPercentForLayout
        // 退化为 100，键盘从此贴满屏幕宽度、只有缩放手势才能纠正。屏幕宽度在构建时
        // 即可用，且与布局完成后的实际值一致（IME 窗口是全屏宽）。
        windowWidthPx = resources.displayMetrics.widthPixels,
    )

    /** 屏幕短边的像素数：横屏时即屏幕可显示高度的上限（竖屏时键盘远够不到它）。 */
    private fun screenShortSidePx(): Int = with(resources.displayMetrics) { minOf(heightPixels, widthPixels) }

    /** 双指按下时记录缩放基线（当前尺寸/缩放/百分比/位置锚点）。 */
    private fun beginFloatingResize() {
        resizeStartWidth = keyboardView.width
        resizeStartHeight = windowManager.view.height
        resizeStartScaleX = keyboardView.scaleX
        resizeStartScaleY = keyboardView.scaleY
        resizeStartWidthPercent = floatingKeyboardWidth
        resizeStartHeightPercent = floatingKeyboardHeight
        resizedWidth = resizeStartWidthPercent
        resizedHeight = resizeStartHeightPercent
        // 位置锚点：手势期间每帧从锚点重算位置（而不是在上移补偿上累加），
        // 缩小时卡片自动回到原位，“放大跑向屏幕顶部”不会再发生。
        resizeStartTranslationX = keyboardView.translationX
        resizeStartTranslationY = keyboardView.translationY
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
    }

    /** 两指间距变化驱动尺寸：等比模式任意方向驱动同一档位，非等比两轴各自驱动。 */
    private fun updateFloatingResize(spanDeltaX: Float, spanDeltaY: Float) {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        // 死区：小于 2dp 的间距抖动不驱动，避免“按住轻移”时尺寸来回抖。
        if (abs(spanDeltaX) < dp(PINCH_DEAD_ZONE_DP) && abs(spanDeltaY) < dp(PINCH_DEAD_ZONE_DP)) return
        val keepRatio = keyboardPrefs.floatingKeyboardKeepRatio.getValue()
        // 等比模式取主导轴的间距差（对角线两轴相当，取哪轴都一样）；
        // 宽高偏好是同一个值：宽度是唯一的“缩放档位”，高度只是它的显示副本。
        val widthDelta = if (keepRatio) {
            if (abs(spanDeltaX) >= abs(spanDeltaY)) spanDeltaX else spanDeltaY
        } else {
            spanDeltaX
        }
        resizedWidth = resizedPercent(
            resizeStartWidthPercent,
            (resizeStartWidth * resizeStartScaleX).roundToInt(),
            widthDelta,
            minWidthPercent(),
            100,
        )
        resizedHeight =
            if (keepRatio) {
                resizedWidth
            } else {
                resizedPercent(
                    resizeStartHeightPercent,
                    (resizeStartHeight * resizeStartScaleY).roundToInt(),
                    spanDeltaY,
                    40,
                    100,
                )
            }
        keyboardView.scaleX = floatingScaleX(resizedWidth, floatingKeyboardBaseWidth)
        // 等比：Y 直接复制 X，内容严格不变形。非等比：Y 用与 X 对称的绝对档位换算
        // （档位/基准档位），两轴共用同一套捏合数理，不再被窗口/内容高度比放大。
        // 屏幕容纳量不在这里硬 clamp（clamp 的职责在 applyFloatingScale/
        // clampedMaxScaleY），超出窗口底边的部分由 applyFloatingPosition 的
        // maxTranslationY 上移钳制兜底。
        keyboardView.scaleY =
            if (keepRatio) {
                keyboardView.scaleX
            } else {
                floatingScaleY(resizedHeight, floatingKeyboardBaseHeight)
            }
        // 每帧从手势起始锚点重算位置：卡片保持在原地，放大超出底边时由
        // applyFloatingPosition 最小幅度上移钳制，缩小时自动回落——
        // 之前单调累加的上移补偿只会把卡片一路推到屏幕顶部。
        applyFloatingPosition(
            resizeStartTranslationX,
            resizeStartTranslationY,
            persist = false,
        )
    }

    /** 缩放/恢复时 Y 方向的上限：卡片缩放后不得超出 IME 窗口（横屏高度超限的修复）。 */
    private fun clampedMaxScaleY(): Float {
        if (keyboardView.height == 0) return Float.MAX_VALUE
        val top = keyboardView.top
        // 卡片底部锚定在窗口底边，top 之上的空隙就是可上移余量（applyFloatingPosition
        // 的 minTranslationY 允许卡片顶到窗口顶边下 16dp）。上限计入这块余量，否则
        // 底锚定代数下 available 恒等于“内容高+8dp”，上限恒为 ~1.0x。
        val lift = (top - dp(16)).coerceAtLeast(0)
        val available = (height - dp(8) - top + lift).toFloat()
        return if (available <= 0) Float.MAX_VALUE else available / keyboardView.height
    }

    /** 手指离开（两指变一指也算）时把当前尺寸落盘，作为下次缩放的基线。 */
    private fun endFloatingResize() {
        if (resizeStartWidth == 0 || resizeStartHeight == 0) return
        floatingKeyboardWidth = resizedWidth
        floatingKeyboardHeight = resizedHeight
        // 落盘新偏好后 scale 平滑落在新的绝对值上（floatingScaleX/Y = 档位/当前烘焙基准），
        // 手势期间看到多大，松手后就保持多大。宽度自此由布局 percent 承载（scaleX 归一）；
        // 高度先由 scaleY 短暂承载——宽度变化触发的 refreshKeyboards 会按新偏好重新构造
        // Keyboard（重新烘焙网格高度），新键盘挂载后缩放基准随新烘焙值归一（见
        // updateWindowViewHeightJob 收集器），网格视觉与手势结束时逐像素一致。
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
     * The width is carried by the layout ([`matchConstraintPercentWidth`]), so its scale is
     * normally identity and only differs while the container still measures at the previous
     * percentage. The height rides `scaleY` only until the grid is (re)baked at the current
     * preference — a rebuilt `Keyboard` bakes the new tier into its geometry, the base (the
     * Keyboard's bake) then catches up and this scale settles at 1. The base always being the
     * current Keyboard's bake is what keeps the layout bake and this scale from applying the
     * same tier twice (which read as "the keyboard gets squashed after every release").
     */
    private fun applyFloatingScale() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0 || keyboardView.height == 0) return
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
        keyboardView.scaleX = 1f
        keyboardView.scaleY =
            if (keyboardPrefs.floatingKeyboardKeepRatio.getValue()) {
                // 等比：宽度偏好是唯一的缩放档位，Y 复制 X 的期望缩放（宽度本身由
                // 布局承载，scaleX≈1，所以这里取的是“档位缩放”而非实时 scaleX）。
                floatingScaleX(floatingKeyboardWidth, floatingKeyboardBaseWidth)
            } else {
                floatingScaleY(floatingKeyboardHeight, floatingKeyboardBaseHeight)
            }.coerceAtMost(clampedMaxScaleY())
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    /**
     * 键盘（重新）挂载后的落位：缩放按当前烘焙基准归一，位置回退到用户拖拽持久化的值。
     *
     * 归一时卡片几何已随新烘焙网格变化：手势期间为贴合底边强制上移的 translation 是
     * 尺寸钳制的临时产物而非用户意图（persist=false，从未写入偏好），若沿用会让卡片
     * 在新几何下悬空——回退到 floatingKeyboardOffset 持久值（拖拽松手时才落盘），
     * 与重建路径（构造时的 post）行为一致。
     */
    private fun restoreFloatingPlacement() {
        applyFloatingScale()
        applyFloatingPosition(
            keyboardPrefs.floatingKeyboardOffsetX.getValue().toFloat(),
            keyboardPrefs.floatingKeyboardOffsetY.getValue().toFloat(),
            persist = false,
        )
    }

    private fun syncFloatingPreeditPosition() {
        if (!isFloatingKeyboardEnabled || keyboardView.width == 0) return
        val preeditRoot = preedit.ui.root
        // 水平偏移同时作为左右两侧外边距：宽度按 2*offset 收缩，预编辑右缘始终与
        // 悬浮卡片对齐——旧行为只平移不收缩，右移时右缘会超出卡片宽度（用户实测）。
        // 负偏移（左移）保持根宽不变：根与卡片左缘对齐，左缘随平移探出卡片左侧，
        // 与旧行为一致。24dp 是收缩后的宽度下限，防止极端偏移把预编辑挤没了。
        val preeditOffsetX = dp(floatingPreeditOffsetX)
        val desiredWidth =
            if (preeditOffsetX > 0) {
                (keyboardView.width - 2 * preeditOffsetX).coerceAtLeast(dp(24))
            } else {
                keyboardView.width
            }
        if (preeditRoot.layoutParams.width != desiredWidth) {
            preeditRoot.updateLayoutParams {
                width = desiredWidth
            }
        }
        preeditRoot.translationX =
            keyboardView.left + keyboardView.translationX - preeditRoot.left + preeditOffsetX
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        // 与卡片顶边齐平，不加防接缝重叠：任何重叠都会压住顶部拖动条或底部拖动条
        // 布局下的输入栏，且拖动条在上/下两种位置需保持一致。
        preeditRoot.translationY = keyboardView.translationY + dp(floatingPreeditOffsetY)
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
                ?.let { it.top + dp(floatingPreeditOffsetY) }
                ?: keyboardView.top
        val minTranslationY = -(floatingTop - dp(16)).coerceAtLeast(0).toFloat()
        val maxTranslationY =
            (height - dp(8) - keyboardView.top - keyboardView.height * keyboardView.scaleY)
                .coerceAtLeast(minTranslationY)
        val resolvedY = requestedY.coerceIn(minTranslationY, maxTranslationY)

        keyboardView.translationX = resolvedX
        keyboardView.translationY = resolvedY
        syncFloatingPreeditPosition()
        // The candidate window is docked against the floating layer (see
        // TrimeInputMethodService.refreshCandidatesGeometry); hand it the new position now
        // instead of waiting for the insets round-trip, so it follows the finger.
        service.refreshCandidatesGeometry()
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
                cornerRadius = dp(keyboardPrefs.floatingKeyboardCornerRadius.getValue().toFloat())
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
        // Clickable implies focusable-in-touch-mode; after a rotation the recreated IME window
        // hands focus to the first focusable view, which would be this whole surface -- and its
        // default focus highlight paints a dim overlay over the entire keyboard.
        surface.isFocusable = false
        surface.isFocusableInTouchMode = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            surface.defaultFocusHighlightEnabled = false
        }

        titleBar.apply {
            visibility = if (enabled) View.VISIBLE else View.GONE
            isClickable = enabled
            isFocusable = false
            isFocusableInTouchMode = false
            contentDescription =
                if (enabled) context.getString(R.string.floating_keyboard_drag_handle) else null
            setGripColor(backColor)
        }
    }

    init {
        // MUST call before any operation
        inputDepMgr.start()
        oneHandMode.registerOnChangeListener(oneHandModeListener)

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
                        if (keyboardPrefs.floatingKeyboardBarPosition.getValue() == AppPrefs.Keyboard.FloatingBarPosition.BOTTOM) {
                            topOfParent()
                        } else {
                            below(titleBar)
                        }
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
                        if (keyboardPrefs.floatingKeyboardBarPosition.getValue() == AppPrefs.Keyboard.FloatingBarPosition.BOTTOM) {
                            above(titleBar)
                        } else {
                            bottomOfParent()
                        }
                    },
                )
                // 拖动横线必须画在键盘背景图之上：keyboardBackground 的图片按内在尺寸
                // 居中铺开，会盖住拖动条区域——最后添加才能保证 titleBar 在最顶层。
                // 位置偏好决定它在卡片顶边还是底边；其余约束随位置成对反转。
                val barAtBottom =
                    keyboardPrefs.floatingKeyboardBarPosition.getValue() ==
                        AppPrefs.Keyboard.FloatingBarPosition.BOTTOM
                add(
                    titleBar,
                    lParams(matchParent, dp(16)) {
                        if (barAtBottom) bottomOfParent() else topOfParent()
                    },
                )
            }

        configureFloatingSurface(isFloatingKeyboardEnabled)

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    if (windowManager.isAttached(keyboardWindow)) {
                        // 指定了横屏键盘但未开启横屏模式时，横屏下仍用竖屏键盘（原始设计），
                        // 其自然高度（悬浮模式还会乘高度档位）可能超过横屏可显示高度，
                        // 窗口必须钳到屏幕内，否则底部被系统裁掉；悬浮卡片随后由
                        // applyFloatingScale 的 clampedMaxScaleY 等比缩小适配。
                        windowManager.view.updateLayoutParams {
                            height = it.coerceAtMost(screenShortSidePx())
                        }
                        // 键盘（重新）挂载意味着网格几何已按当前档位重新烘焙：宽度变化触发的
                        // refreshKeyboards、键盘切换重建都会走到这里。缩放基准随新烘焙值走，
                        // 布局完成后立即归一——否则布局已烘焙新档位而 scaleY 仍持旧比例，
                        // 同一档位被施加两次，松手后高度会被额外压扁（宽度由布局承载、
                        // scaleX 恒归一，所以只有高度露馅）。doOnLayout 保证读到的是
                        // 新布局的尺寸；无布局变化时立即执行，幂等无害。
                        keyboardView.doOnLayout { restoreFloatingPlacement() }
                    }
                }
            }

        updateKeyboardSize()

        if (isFloatingKeyboardEnabled) {
            // 悬浮卡片 8dp、按键弹层 24dp，预编辑取中间档：编码区垂直偏移可把它压到
            // 顶部拖动条之上（输入时通常不需要拖动），又不遮挡按键弹层。预编辑容器
            // 无背景，不产生额外阴影；无编码文本时整体 GONE，触摸穿透，拖动条照常可拖。
            preedit.ui.root.translationZ = dp(PREEDIT_LAYER_ELEVATION_DP)
        }

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
                    bottomMargin = dp(16)
                }
            },
        )

        keyboardView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            syncFloatingPreeditPosition()
            // The candidate window docks against the keyboard's top edge. onComputeInsets may
            // run before this layout pass, so report the settled geometry from here.
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                service.refreshCandidatesGeometry()
            }
        }
        if (isFloatingKeyboardEnabled) {
            keyboardView.post {
                // Re-apply the persisted size and placement: the key grid's geometry comes from
                // the theme plus the tier baked into Keyboard at construction, so nothing else
                // would carry them across this rebuild.
                restoreFloatingPlacement()
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
        // 悬浮键盘忽略主题键盘四周 padding 与系统底部边距：keyboard_padding* 是给
        // 贴底的普通键盘用的，悬浮卡片下它们只会无谓挤占可用空间（四边留白）。
        // isFloatingKeyboardEnabled 为构造期定值且本方法仅构造期调用，分支安全。
        if (isFloatingKeyboardEnabled) {
            bottomPaddingSpace.visibility = View.GONE
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = LayoutParams.UNSET
                endToStart = LayoutParams.UNSET
                startOfParent()
                endOfParent()
            }
            preedit.ui.root.setPadding(0, 0, 0, 0)
            // 悬浮键盘不参与左右留白，工具栏保持卡片整宽。
            inputBar.view.setPadding(0, 0, 0, 0)
            inputBar.view.updateLayoutParams<LayoutParams> {
                marginStart = 0
                marginEnd = 0
            }
            return
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }

        val state = oneHandMode.getValue()
        val isPortrait = !context.resources.configuration.isLandscape()

        // 单手边距叠加在 keyboard_padding 之上，而非替换它：
        // keyboard_padding 为曲面屏等场景预留，任何模式下都必须保留。
        val extraLeft = if (isPortrait && state == AppPrefs.Keyboard.OneHandMode.RIGHT) {
            dp(theme.generalStyle.keyboardPaddingLeft.coerceAtLeast(0))
        } else {
            0
        }
        val extraRight = if (isPortrait && state == AppPrefs.Keyboard.OneHandMode.LEFT) {
            dp(theme.generalStyle.keyboardPaddingRight.coerceAtLeast(0))
        } else {
            0
        }
        val leftSidePadding = keyboardSidePaddingPx + extraLeft
        val rightSidePadding = keyboardSidePaddingPx + extraRight

        val unset = LayoutParams.UNSET
        if (leftSidePadding == 0 && rightSidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
            inputBar.view.updateLayoutParams<LayoutParams> {
                marginStart = 0
                marginEnd = 0
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = leftSidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = rightSidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(leftSidePadding, 0, rightSidePadding, 0)
        // 工具栏按同样的左右留白收窄（用 margin 而不是 padding，背景也一起收），
        // 否则整宽的工具栏会比预编辑和键盘都长。悬浮模式已在上面清零。
        inputBar.view.setPadding(0, 0, 0, 0)
        inputBar.view.updateLayoutParams<LayoutParams> {
            marginStart = leftSidePadding
            marginEnd = rightSidePadding
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        // 悬浮键盘忽略系统底部边距：卡片由拖拽/缩放自行定位，不随导航栏避让。
        if (!isFloatingKeyboardEnabled) {
            bottomPaddingSpace.updateLayoutParams<LayoutParams> {
                bottomMargin = getNavBarBottomInset(insets)
            }
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
        oneHandMode.unregisterOnChangeListener(oneHandModeListener)
        popup.root.removeAllViews()
        inputDepMgr.stop()
        super.onDetachedFromWindow()
    }
}
