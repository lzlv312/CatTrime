/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
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
import com.osfans.trime.ime.keyboard.floatingContentScale
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

/** 双指捏合开始驱动前，两指间距至少要移动的距离（dp）——小于它的移动视为手抖。 */
internal const val FLOATING_PINCH_ARM_DP = 12f

/**
 * 双指拖动开始驱动前，两指中点至少要移动的距离（dp）。
 *
 * 比捏合阈值大一倍：两根手指平行移动（快速换键时两拇指一前一后地挪）同样会移动
 * 中点，只有明确的“整只手把键盘推走”才该判定为拖动。平行移动不改变两指间距，
 * 所以捏合仍可用更小的阈值而不误伤打字。
 */
internal const val FLOATING_PAN_ARM_DP = 24f

/** 手势驱动的死区：小于它的间距/中点变化不驱动尺寸与位置（过滤“按住轻移”的抖动）。 */
internal const val FLOATING_GESTURE_DEAD_ZONE_DP = 2f

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
 * Must sit strictly above [FLOATING_KEYBOARD_ELEVATION_DP] (the preedit is flush with the
 * card's top edge, where the card's own drop shadow would otherwise paint over it) and
 * strictly below [POPUP_LAYER_ELEVATION_DP] (key popups stay on top). The preedit
 * root has no background, so this Z adds no shadow of its own.
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
 * 悬浮键盘的容器，唯一职责是识别双指手势：捏合缩放、双指拖动移动。
 *
 * 第二指落下时**不拦截**：事件照常分发给按键，真正的双指输入（两指各按一键）不受任何
 * 影响。只有两指间距（[FLOATING_PINCH_ARM_DP]）或两指中点（[FLOATING_PAN_ARM_DP]）
 * 移动超过阈值才判定为手势意图——此刻才拦截手势（已按下的键收到 CANCEL）、触发
 * [onGestureStart]（震动）并开始驱动尺寸与位置；驱动后的间距差经 [PINCH_DAMPING]
 * 阻尼（1:2）换算压手抖。任一手指离开（包括两指变一指）立即结束。未超过阈值就抬手 =
 * 一次普通输入，全程无震动、无变化。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class FloatingKeyboardContainer(
    context: Context,
    floating: Boolean,
    private val onGestureStart: () -> Unit,
    // 间距差与中点位移，都是相对手势判定点的累计值（屏幕像素）。
    private val onGesture: (spanX: Float, spanY: Float, panX: Float, panY: Float) -> Unit,
    private val onGestureEnd: () -> Unit,
) : ConstraintLayout(context) {
    var floating: Boolean = floating
        private set

    /** 两指都在屏幕上（尚未判定为输入还是手势）。 */
    private var twoFingersDown = false

    /** 已判定为手势并拦截事件；此前的移动属于正常输入，不驱动尺寸与位置。 */
    private var armed = false
    private var spanX0 = 0f
    private var spanY0 = 0f
    private var centerX0 = 0f
    private var centerY0 = 0f
    private val pinchArmThreshold = context.dp(FLOATING_PINCH_ARM_DP)
    private val panArmThreshold = context.dp(FLOATING_PAN_ARM_DP)

    /** 0/1 两指的横向与纵向间距（张开为正、捏合为负的差值基准）。 */
    private fun spans(event: MotionEvent): Pair<Float, Float> = abs(event.getX(0) - event.getX(1)) to abs(event.getY(0) - event.getY(1))

    /**
     * 两指中点的**屏幕**坐标。
     *
     * 不能直接用局部坐标的平均值：卡片绕左上角缩放时，同一个屏幕点对应的局部坐标会随
     * 缩放漂移，局部中点差会把“缩放”误读成“拖动”。0 号指针的 raw 坐标不受视图变换
     * 影响，再加上两指局部距离按当前缩放换算回屏幕的一半，就得到真实中点。此处读到的
     * scale 正是分发本事件时用的那个（缩放要等本事件处理完才更新）。
     */
    private fun center(event: MotionEvent): Pair<Float, Float> {
        val halfX = (event.getX(1) - event.getX(0)) / 2f * scaleX
        val halfY = (event.getY(1) - event.getY(0)) / 2f * scaleY
        return (event.rawX + halfX) to (event.rawY + halfY)
    }

    /** 把间距与中点的差值基准挪到当前位置（手势起点/判定点）。 */
    private fun rebase(event: MotionEvent) {
        val (spanX, spanY) = spans(event)
        spanX0 = spanX
        spanY0 = spanY
        val (centerX, centerY) = center(event)
        centerX0 = centerX
        centerY0 = centerY
    }

    /** 间距张开/捏合，或两指一起平移，都算手势；两者都不够就是一次普通双指输入。 */
    private fun movedEnough(event: MotionEvent): Boolean {
        val (spanX, spanY) = spans(event)
        if (hypot(spanX - spanX0, spanY - spanY0) >= pinchArmThreshold) return true
        val (centerX, centerY) = center(event)
        return hypot(centerX - centerX0, centerY - centerY0) >= panArmThreshold
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!floating) return super.onInterceptTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN ->
                if (!twoFingersDown && event.pointerCount == 2) {
                    twoFingersDown = true
                    armed = false
                    rebase(event)
                    // 不拦截：让两个按键正常收事件，先当作一次双指输入观察。
                    return false
                }

            MotionEvent.ACTION_MOVE ->
                if (twoFingersDown && !armed) {
                    if (movedEnough(event)) {
                        // 判定为手势：此刻才拦截 + 震动，并把基准挪到当前位置。
                        armed = true
                        rebase(event)
                        onGestureStart()
                        return true
                    }
                    return false
                } else if (twoFingersDown) {
                    return true
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (twoFingersDown) {
                    val wasArmed = armed
                    twoFingersDown = false
                    armed = false
                    if (wasArmed) {
                        onGestureEnd()
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
                    val (centerX, centerY) = center(event)
                    onGesture(
                        (spanX - spanX0) * PINCH_DAMPING,
                        (spanY - spanY0) * PINCH_DAMPING,
                        centerX - centerX0,
                        centerY - centerY0,
                    )
                } else {
                    // 两指变一指：立即结束，避免剩余单指继续改变尺寸与位置。
                    endGesture()
                }

            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun endGesture() {
        if (!armed) return
        armed = false
        twoFingersDown = false
        onGestureEnd()
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

    /** 悬浮编码区左右边距（dp），卡片左右各留出的空白。 */
    private val floatingPreeditInsetX = keyboardPrefs.floatingPreeditInsetX.getValue()

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

    private var gestureStartWidth = 0
    private var gestureStartHeight = 0
    private var gestureStartScaleX = 1f
    private var gestureStartScaleY = 1f
    private var gestureStartTranslationX = 0f
    private var gestureStartTranslationY = 0f
    private var gestureStartWidthPercent = floatingKeyboardWidth
    private var gestureStartHeightPercent = floatingKeyboardHeight
    private var resizedWidth = floatingKeyboardWidth
    private var resizedHeight = floatingKeyboardHeight

    /** 本次手势是否真的改过尺寸（纯拖动时为 false，松手后跳过尺寸落盘）。 */
    private var gestureResized = false

    /**
     * Window-space bounds of the floating layer as last reported to the IME window.
     *
     * Guarded by [hasLastFloatingBounds] because the bounds are unknown (and the whole
     * floating layout is inactive) until the keyboard is actually laid out in floating mode.
     */
    private val lastFloatingBounds = Rect()
    private var hasLastFloatingBounds = false

    /**
     * 工具栏的缩放外层，见 [syncFloatingInputBarScale]。
     *
     * 工具栏属于键盘内容，必须与键区按同一档位收缩；但它的字号/图标是各套 bar UI
     * 直接读主题的固定 sp/dp，逐一下沉档位侵入面太大，所以用一层包装做统一变换：
     * 外层高度随之收缩，内层输入栏整体缩放后正好铺满外层。
     */
    private val barFrame = FrameLayout(context)

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

    /** 双指手势开始时记录基线（当前尺寸/缩放/百分比/位置锚点）。 */
    private fun beginFloatingGesture() {
        gestureStartWidth = keyboardView.width
        gestureStartHeight = windowManager.view.height
        gestureStartScaleX = keyboardView.scaleX
        gestureStartScaleY = keyboardView.scaleY
        gestureStartWidthPercent = floatingKeyboardWidth
        gestureStartHeightPercent = floatingKeyboardHeight
        resizedWidth = gestureStartWidthPercent
        resizedHeight = gestureStartHeightPercent
        gestureResized = false
        // 位置锚点：手势期间每帧从锚点重算位置（而不是在上移补偿上累加），
        // 缩小时卡片自动回到原位，“放大跑向屏幕顶部”不会再发生。
        gestureStartTranslationX = keyboardView.translationX
        gestureStartTranslationY = keyboardView.translationY
        keyboardView.pivotX = 0f
        keyboardView.pivotY = 0f
    }

    /**
     * 双指手势驱动：间距变化驱动尺寸（等比模式任意方向驱动同一档位，非等比两轴各自驱动），
     * 两指中点位移驱动位置——都是相对手势判定点（rebase 点）的累计值。
     */
    private fun updateFloatingGesture(
        spanDeltaX: Float,
        spanDeltaY: Float,
        panDeltaX: Float,
        panDeltaY: Float,
    ) {
        if (gestureStartWidth == 0 || gestureStartHeight == 0) return
        // 死区：小于 2dp 的抖动不驱动，避免“按住轻移”时尺寸/位置来回抖。
        val deadZone = dp(FLOATING_GESTURE_DEAD_ZONE_DP)
        val spanX = spanDeltaX.takeIf { abs(it) >= deadZone } ?: 0f
        val spanY = spanDeltaY.takeIf { abs(it) >= deadZone } ?: 0f
        // 中点位移来自屏幕坐标，卡片缩放不参与换算：手指跟着卡片走，卡片就跟手指走。
        val panX = panDeltaX.takeIf { abs(it) >= deadZone } ?: 0f
        val panY = panDeltaY.takeIf { abs(it) >= deadZone } ?: 0f
        val sizeChanged = spanX != 0f || spanY != 0f
        if (!sizeChanged && panX == 0f && panY == 0f) return
        if (sizeChanged) {
            gestureResized = true
            val keepRatio = keyboardPrefs.floatingKeyboardKeepRatio.getValue()
            // 等比模式取主导轴的间距差（对角线两轴相当，取哪轴都一样）；
            // 宽高偏好是同一个值：宽度是唯一的“缩放档位”，高度只是它的显示副本。
            val widthDelta = if (keepRatio) {
                if (abs(spanX) >= abs(spanY)) spanX else spanY
            } else {
                spanX
            }
            resizedWidth = resizedPercent(
                gestureStartWidthPercent,
                (gestureStartWidth * gestureStartScaleX).roundToInt(),
                widthDelta,
                minWidthPercent(),
                100,
            )
            resizedHeight =
                if (keepRatio) {
                    resizedWidth
                } else {
                    resizedPercent(
                        gestureStartHeightPercent,
                        (gestureStartHeight * gestureStartScaleY).roundToInt(),
                        spanY,
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
        }
        // 每帧从手势起始锚点 + 累计位移重算位置：拖动与缩放共用同一次落位，放大超出
        // 底边时由 applyFloatingPosition 最小幅度上移钳制，缩小时自动回落——
        // 之前单调累加的上移补偿只会把卡片一路推到屏幕顶部。
        applyFloatingPosition(
            gestureStartTranslationX + panX,
            gestureStartTranslationY + panY,
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

    /**
     * 手指离开（两指变一指也算）时把尺寸与位置落盘，作为下次手势的基线。
     *
     * 纯拖动不改尺寸：跳过尺寸落盘，否则每次拖动结束都会重设布局百分比、重走一遍
     * 缩放归一（无意义的整卡重新布局）。
     */
    private fun endFloatingGesture() {
        if (gestureStartWidth == 0 || gestureStartHeight == 0) return
        gestureStartWidth = 0
        gestureStartHeight = 0
        if (gestureResized) {
            gestureResized = false
            floatingKeyboardWidth = resizedWidth
            floatingKeyboardHeight = resizedHeight
            // 落盘新偏好后 scale 平滑落在新的绝对值上（floatingScaleX/Y = 档位/当前烘焙基准），
            // 手势期间看到多大，松手后就保持多大。宽度自此由布局 percent 承载（scaleX 归一）；
            // 高度先由 scaleY 短暂承载——宽度变化触发的 refreshKeyboards 会按新偏好重新构造
            // Keyboard（重新烘焙网格高度），新键盘挂载后缩放基准随新烘焙值归一（见
            // updateWindowViewHeightJob 收集器），网格视觉与手势结束时逐像素一致。
            keyboardView.updateLayoutParams<LayoutParams> {
                matchConstraintPercentWidth = layoutWidthPercent(resizedWidth) / 100f
            }
            keyboardView.doOnLayout { applyFloatingScale() }
            service.updateFloatingKeyboardSize(resizedWidth, resizedHeight)
            service.requestInputInsetsUpdate()
        }
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = true,
        )
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
        if (syncFloatingInputBarScale()) {
            // 外层高度变了会改变卡片整体几何，等新布局落定后按新高度重新归一。
            keyboardView.doOnLayout { applyFloatingScale() }
        }
        applyFloatingPosition(
            keyboardView.translationX,
            keyboardView.translationY,
            persist = false,
        )
    }

    /**
     * 工具栏跟随档位缩放，与键区保持贴底键盘时的同一份比例。
     *
     * 键区把档位烘焙进网格几何、字号乘 `contentScale`（见 Keyboard.contentScale）；
     * 工具栏的候选栏、开关栏、Tab 栏各自读主题的固定 sp/dp，逐一穿透系数代价太大，
     * 改用外层包装 + 统一 view scale 得到同样的视觉结果：外层高度 = themedHeight ×
     * 高度档位，内层按 `contentScale` 缩放后正好铺满外层（内层宽度取 外层宽 / 系数，
     * 缩放后与卡片等宽，候选数量与贴底时一致）。
     *
     * 档位取 [floatingKeyboardBaseWidth] / [floatingKeyboardBaseHeight]（当前 Keyboard
     * 的烘焙值）而非偏好镜像：捏合期间由卡片整体的 scaleY 预览，松手重烘焙后基准才
     * 追上，与网格归一保持同步。返回值表示外层高度是否变化，调用方据此重新归一缩放。
     */
    private fun syncFloatingInputBarScale(): Boolean {
        if (!isFloatingKeyboardEnabled) return false
        val contentScale = floatingContentScale(floatingKeyboardBaseWidth, floatingKeyboardBaseHeight)
        if (contentScale <= 0f) return false
        val barHeight = dp(inputBar.themedHeight)
        val frameHeight =
            (barHeight * floatingKeyboardBaseHeight / 100f).roundToInt().coerceAtLeast(1)
        val heightChanged = barFrame.layoutParams.height != frameHeight
        if (heightChanged) barFrame.updateLayoutParams { height = frameHeight }
        val frameWidth = barFrame.width
        if (frameWidth == 0) return heightChanged
        val bar = inputBar.view
        val barWidth = (frameWidth / contentScale).roundToInt().coerceAtLeast(frameWidth)
        if (bar.layoutParams.width != barWidth || bar.layoutParams.height != barHeight) {
            bar.updateLayoutParams {
                width = barWidth
                height = barHeight
            }
        }
        bar.pivotX = 0f
        bar.pivotY = 0f
        bar.scaleX = contentScale
        bar.scaleY = contentScale
        // 内容缩放后通常比外层矮一点（非等比 + 宽度更窄时），垂直居中。
        bar.translationY = (frameHeight - barHeight * contentScale) / 2f
        return heightChanged
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
        // 左右边距：宽度按 2*inset 收缩后再整体内缩 inset，编码行左右缘都落在卡片内。
        val preeditInset = dp(floatingPreeditInsetX)
        val desiredWidth = (keyboardView.width - 2 * preeditInset).coerceAtLeast(dp(24))
        if (preeditRoot.layoutParams.width != desiredWidth) {
            preeditRoot.updateLayoutParams {
                width = desiredWidth
            }
        }
        preeditRoot.translationX =
            keyboardView.left + keyboardView.translationX - preeditRoot.left + preeditInset
        preeditRoot.pivotX = 0f
        preeditRoot.scaleX = keyboardView.scaleX
        // 与卡片顶边齐平：卡片顶边就是工具栏的上沿，编码区落在卡片上方、不压工具栏。
        preeditRoot.translationY = keyboardView.translationY
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
        // 编码区与卡片顶边齐平（已无垂直偏移），可上移余量按它的顶边算。
        val floatingTop =
            preedit.ui.root
                .takeIf { it.visibility == View.VISIBLE && it.height > 0 }
                ?.top
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

    /** Styles the floating card; docked mode strips it. */
    private fun configureFloatingSurface(enabled: Boolean) {
        val surface = keyboardView as FloatingKeyboardContainer
        val backColor = resolveFloatingBackColor()
        if (enabled) {
            surface.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(keyboardPrefs.floatingKeyboardCornerRadius.getValue().toFloat())
                setColor(backColor)
            }
            surface.clipToOutline = true
            surface.elevation = dp(FLOATING_KEYBOARD_ELEVATION_DP)
            surface.isClickable = true
            surface.contentDescription = context.getString(R.string.floating_keyboard_gesture_hint)
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

        // 工具栏的显隐由 bar 状态机直接写输入栏，包装层在这里同步，见 [barFrame]。
        inputBar.onBarVisibilityChanged = { barFrame.visibility = it }

        keyboardView =
            FloatingKeyboardContainer(
                context,
                isFloatingKeyboardEnabled,
                onGestureStart = {
                    beginFloatingGesture()
                    // 手势已被判定、按键已收到 CANCEL：给一次长按触感，确认进入了移动/缩放模式。
                    keyboardView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                },
                onGesture = ::updateFloatingGesture,
                onGestureEnd = { endFloatingGesture() },
            ).apply {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                add(
                    barFrame,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        topOfParent()
                        centerHorizontally()
                    },
                )
                barFrame.addView(
                    inputBar.view,
                    FrameLayout.LayoutParams(matchParent, dp(inputBar.themedHeight)),
                )
                // 隐藏工具栏时首帧就与输入栏同显隐，见 [barFrame] 与 init 里的回调。
                barFrame.visibility = inputBar.view.visibility
                add(
                    leftPaddingSpace,
                    lParams {
                        below(barFrame)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(barFrame)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(barFrame)
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
        // 首次布局前就把工具栏落到持久化档位，避免闪一帧满尺寸。
        syncFloatingInputBarScale()

        if (isFloatingKeyboardEnabled) {
            // 悬浮卡片 8dp、按键弹层 24dp，预编辑取中间档：它贴着卡片顶边（也就叠在
            // 工具栏之上），又不遮挡按键弹层。预编辑容器无背景，不产生额外阴影；
            // 无编码文本时整体 GONE，触摸穿透到底下的工具栏与按键。
            preedit.ui.root.translationZ = dp(PREEDIT_LAYER_ELEVATION_DP)
        }

        add(
            preedit.ui.root,
            lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            },
        )

        if (isFloatingKeyboardEnabled) {
            // 编码区出现/折行会改变卡片上方必须留出的空间：卡片被拖到窗口顶边时，位置是按
            // “编码区不可见”钳的，若不按新高度重新钳位，刚产生编码的那一下编码区会被窗口
            // 上边界裁掉，要等下一次编码消息才自愈。
            preedit.ui.root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (top != oldTop || bottom != oldBottom) {
                    applyFloatingPosition(
                        keyboardView.translationX,
                        keyboardView.translationY,
                        persist = false,
                    )
                }
            }
        }

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
            // 工具栏外层的宽度/高度都由卡片几何决定（宽度档位、重建后的新档位）；高度变了
            // 会改变卡片的整体几何，需要按新高度重新归一 scaleY 与位置。
            if (syncFloatingInputBarScale()) keyboardView.doOnLayout { applyFloatingScale() }
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
            barFrame.updateLayoutParams<LayoutParams> {
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
            barFrame.updateLayoutParams<LayoutParams> {
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
        barFrame.updateLayoutParams<LayoutParams> {
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
