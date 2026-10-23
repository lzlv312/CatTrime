/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.view.KeyEvent
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.model.TextKeyboard
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isFloatingEffective
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 悬浮键区内容的统一缩放系数：宽/高档位中较小者（短边的百分比）。
 *
 * 键背景随卡片档位收缩，字号/偏移却来自主题的固定 sp 值——不缩放就是“背景小、文字
 * 大”，固定偏移还会把内容顶出键帽。取两个档位中较小者，任一方向变小时内容都按它
 * 收缩，最坏情况下也不会溢出；贴底键盘不调用它（[Keyboard.contentScale] 恒为 1）。
 */
internal fun floatingContentScale(
    widthPercent: Int,
    heightPercent: Int,
): Float = minOf(widthPercent, heightPercent).coerceIn(0, 100) / 100f

/** 從YAML中加載鍵盤配置，包含多個[按鍵][Key]。  */
@Suppress("ktlint:standard:property-naming")
class Keyboard(
    private val context: Context,
    private val theme: Theme,
    private val allowedWidth: Int,
    selfConfig: TextKeyboard? = null,
) {

    /**
     * 构造时烘焙进键盘几何的悬浮宽/高档位（%）。
     *
     * 网格的像素几何（行高缩放、key.x/y）在构造时按当次档位算死，视图测量高度也来自
     * 这套几何（KeyboardView.onMeasure 用 [height]，无视父容器规格）——档位真正的
     * 载体是本对象而不是偏好。InputView 归一缩放时以此为基准：宽度变化触发的
     * refreshKeyboards 会按当前偏好重新构造本对象（重新烘焙），基准随新对象走、
     * 缩放立即归一——否则布局已烘焙新档位而 scaleY 仍持旧比例，同一档位被施加两次，
     * 表现为松手后键盘高度被额外压扁（宽度因 scaleX 恒归一而不受影响）。
     */
    val bakedFloatingWidthPercent: Int = AppPrefs.defaultInstance().keyboard.floatingKeyboardWidth.getValue()

    val bakedFloatingHeightPercent: Int = AppPrefs.defaultInstance().keyboard.floatingKeyboardHeight.getValue()

    /**
     * 键内容（字号、符号、偏移、间距、圆角）的统一缩放系数。
     *
     * 悬浮卡片的按键背景随宽/高档位收缩，而键内容的字号与偏移来自主题的固定 sp 值，
     * 不跟着缩就会“背景小、文字大”，固定偏移还会把内容顶出键帽。系数取宽/高两个
     * 档位中较小者（短边的百分比），任一方向更小时内容都按它收缩；贴底键盘恒为 1。
     */
    val contentScale: Float =
        if (context.isFloatingEffective()) {
            floatingContentScale(bakedFloatingWidthPercent, bakedFloatingHeightPercent)
        } else {
            1f
        }

    /** 按鍵默認水平間距  */
    internal val horizontalGap: Int =
        intArrayOf(
            selfConfig?.horizontalGap ?: 0,
            theme.generalStyle.horizontalGap,
        ).firstOrNull { it > 0 }?.let { (context.dp(it) * contentScale).roundToInt() } ?: 0

    /** 默認鍵寬  */
    private val keyWidth: Int = (allowedWidth * theme.generalStyle.keyWidth / 100).toInt()

    /** 默認鍵高 (NOTE: 无需 dp 转换，会被 keyboardHeight 等比例缩放) */
    private val keyHeight: Int =
        intArrayOf(
            selfConfig?.height?.toInt() ?: 0,
            theme.generalStyle.keyHeight,
        ).firstOrNull { it > 0 } ?: 0

    /** 默認行距  */
    internal val verticalGap: Int =
        intArrayOf(
            selfConfig?.verticalGap ?: 0,
            theme.generalStyle.verticalGap,
        ).firstOrNull { it > 0 }?.let { (context.dp(it) * contentScale).roundToInt() } ?: 0

    /** 默認按鍵圓角半徑  */
    val roundCorner: Float =
        selfConfig?.roundCorner?.takeIf { it >= 0f } ?: theme.generalStyle.roundCorner

    /** 默認按鍵邊框寬度  */
    val keyBorder: Int =
        selfConfig?.keyBorder?.takeIf { it >= 0 } ?: theme.generalStyle.keyBorder

    /** 鍵盤的Shift鍵  */
    var mShiftKey: Key? = null
    var mCtrlKey: Key? = null
    var mAltKey: Key? = null
    var mMetaKey: Key? = null
    var mSymKey: Key? = null

    /**
     * Total height of the keyboard, including the padding and keys
     *
     * @return the total height of the keyboard
     */
    var height = 0
        private set

    /**
     * Total width of the keyboard, including left side gaps and keys, but not any gaps on the right
     * side.
     */
    var minWidth = 0
        private set

    /** List of keys in this keyboard  */
    private val mKeys = mutableListOf<Key>()
    val appearanceStateKeys = mutableListOf<Key>()
    var modifier = 0
        private set

    var firstPressedKeyIndex: Int = -1

    /** Keyboard default ascii mode  */
    val asciiMode = selfConfig?.asciiMode ?: false
    val resetAsciiMode = selfConfig?.resetAsciiMode ?: true
    var lastAsciiMode: Boolean = asciiMode

    private val preferredSplitPercent by AppPrefs.defaultInstance().keyboard.splitSpacePercent
    private val landscapePercent =
        intArrayOf(
            selfConfig?.landscapeSplitPercent ?: 0,
            preferredSplitPercent,
        ).firstOrNull { it > 0 } ?: 0

    // Variables for pre-computing nearest keys.
    private val labelTransform = selfConfig?.labelTransform ?: TextKeyboard.LabelTransform.NONE
    private var mCellWidth = 0
    private var mCellHeight = 0
    private var gridNeighbors: Array<IntArray?>? = null

    private val proximityThreshold: Int =
        (keyWidth * SEARCH_DISTANCE).pow(2).toInt() // Square it for comparison
    val isLock = selfConfig?.lock ?: false // 切換程序時記憶鍵盤
    val asciiKeyboard: String? = selfConfig?.asciiKeyboard // 英文鍵盤
    val navbar: Boolean = selfConfig?.navbar ?: false // 是否顯示导航栏

    /**
     * 键区高度（含悬浮档位）。悬浮卡片不使用横屏高度变体：卡片的宽度按屏幕短边换算，
     * 横竖屏是同一物理宽度，若高度改用更矮的 keyboard_height_land，卡片就会「宽多高少」，
     * 等比例缩放也救不回来（基准本身已经变形）。悬浮时统一取竖屏高度，卡片随方向
     * 旋转保持完全相同的几何。
     */
    val keyboardHeight: Int =
        context.isFloatingEffective().let { floating ->
            val useLandscapeGeometry = context.isLandscapeMode() && !floating
            resolvePositive(
                selfConfig?.let {
                    pickLandscape(it.keyboardHeight, it.keyboardHeightLand, useLandscapeGeometry)
                },
                pickLandscape(
                    theme.generalStyle.keyboardHeight,
                    theme.generalStyle.keyboardHeightLand,
                    useLandscapeGeometry,
                ),
            ) { context.dp(it) }.let { resolvedHeight ->
                val keyboardPrefs = AppPrefs.defaultInstance().keyboard
                // 悬浮档位只作用于「有效悬浮」：横屏回落普通键盘时高度不再乘档位。
                if (floating) {
                    (resolvedHeight * keyboardPrefs.floatingKeyboardHeight.getValue() / 100f).toInt()
                } else {
                    resolvedHeight
                }
            }
        }

    private val expandKeypressArea: Boolean by AppPrefs.defaultInstance().keyboard.expandKeypressArea

    init {

        if (selfConfig != null) {

            fun firstNonZero(a: Float, b: Float, c: Float): Float = if (a != 0f) {
                a
            } else if (b != 0f) {
                b
            } else {
                c
            }

            val keys = selfConfig.keys
            val keyboardKeyWidth = selfConfig.width

            val maxColumns = if (selfConfig.columns == -1) Int.MAX_VALUE else selfConfig.columns

            // 悬浮键盘不分割：分割比例只作用于贴边的横屏键盘。
            val isSplit = !context.isFloatingEffective() && context.isLandscapeMode() && landscapePercent > 0
            val splitRatio = if (isSplit) landscapePercent / 100f else 0f

            val oneWeightWidthPx =
                allowedWidth.toFloat() / (MAX_TOTAL_WEIGHT * (1 + splitRatio))

            // total width weight for each row.
            val rowWidthTotalWeight = mutableListOf<Float>()

            // raw height of each row before scaling
            val rowRawHeight = mutableListOf<Int>()

            var x = 0
            var column = 0
            var rowHeight = keyHeight
            var totalKeyWidth = 0f
            var rowIndex = 0

            // Row each key belongs to, decided once here and reused when the keys are created.
            // Deciding twice is not equivalent: the second pass places widths computed with the
            // split ratio and may stretch the key that lands on the right edge, so it can wrap a
            // key the first pass kept on the previous row -- and then index a row height that does
            // not exist.
            val keyRows = IntArray(keys.size)

            // determine row count, row heights, total row weights; does not create Key objects
            for ((keyIndex, key) in keys.withIndex()) {

                // determine the width weight of this key
                val keyWidthWeight =
                    if (key.width == 0f && key.hasClickAction) keyboardKeyWidth else key.width

                val widthPx = (keyWidthWeight * allowedWidth / MAX_TOTAL_WEIGHT).toInt()

                // wrap to next row if column or width limit is reached
                if (column >= maxColumns || x + widthPx > allowedWidth) {
                    rowWidthTotalWeight.add(totalKeyWidth)
                    rowRawHeight.add(rowHeight)
                    x = 0
                    column = 0
                    totalKeyWidth = 0f
                    rowIndex++
                }

                keyRows[keyIndex] = rowIndex

                // first key of a row defines the row height
                if (column == 0) {
                    rowHeight = if (key.height > 0) key.height.toInt() else keyHeight
                }

                totalKeyWidth += keyWidthWeight

                // only clickable keys count toward column count
                if (key.hasClickAction) {
                    column++
                }

                x += widthPx
            }

            rowWidthTotalWeight.add(totalKeyWidth)
            rowRawHeight.add(rowHeight)

            val rows = rowRawHeight.size
            val rawHeightSum = rowRawHeight.sum()

            // scaled row heights after fitting into keyboardHeight
            val rowHeightScaled = MutableList(rows) { 0 }

            var remainHeight = keyboardHeight
            val scale = keyboardHeight.toFloat() / rawHeightSum

            // scale row heights to keyboardHeight; last row absorbs rounding errors
            for (i in 0 until rows - 1) {
                val h = (rowRawHeight[i] * scale).toInt()
                rowHeightScaled[i] = h
                remainHeight -= h
            }

            rowHeightScaled[rows - 1] = remainHeight

            var xPos = 0
            var yPos = 0

            var row = 0
            column = 0

            var rowWeightAccumulo = 0f
            var currentRowHeight = rowHeightScaled[0]

            // indicates whether the split gap has been inserted in the current row
            var splitInserted = false

            minWidth = 0

            val spacers = mutableListOf<Triple<Int, Int, Int>>()

            // create Key objects, assign position, size, offsets
            for ((keyIndex, textKey) in keys.withIndex()) {

                val keyWidthWeight =
                    if (textKey.width == 0f && textKey.hasClickAction) keyboardKeyWidth else textKey.width

                var widthPx = (keyWidthWeight * oneWeightWidthPx).toInt()

                // follow the row assignment of the first pass instead of re-deciding it
                val targetRow = keyRows[keyIndex]
                if (targetRow != row) {
                    xPos = 0
                    yPos += currentRowHeight
                    row = targetRow
                    column = 0
                    rowWeightAccumulo = 0f
                    splitInserted = false
                    currentRowHeight = rowHeightScaled[row]
                }

                rowWeightAccumulo += keyWidthWeight

                val totalWeight = rowWidthTotalWeight[row]

                // if split keyboard layout is enabled, insert split gap at row middle when cumulative width > 50%
                if (isSplit && !splitInserted && rowWeightAccumulo > totalWeight * 0.5f) {
                    splitInserted = true
                    val gap = (totalWeight * splitRatio * oneWeightWidthPx).toInt()

                    // large keys absorb the gap; small keys shift right
                    if (keyWidthWeight > 20f) {
                        widthPx += gap
                    } else {
                        if (expandKeypressArea) spacers.add(Triple(xPos, gap, row))
                        xPos += gap
                    }
                }

                if (!textKey.hasClickAction) {
                    if (expandKeypressArea) spacers.add(Triple(xPos, widthPx, row))
                    xPos += widthPx
                    continue
                }

                val key = Key(this, textKey)

                key.keyTextOffsetX = firstNonZero(textKey.keyTextOffsetX, selfConfig.keyTextOffsetX, theme.generalStyle.keyTextOffsetX)
                key.keyTextOffsetY = firstNonZero(textKey.keyTextOffsetY, selfConfig.keyTextOffsetY, theme.generalStyle.keyTextOffsetY)
                key.keySymbolOffsetX = firstNonZero(textKey.keySymbolOffsetX, selfConfig.keySymbolOffsetX, theme.generalStyle.keySymbolOffsetX)
                key.keySymbolOffsetY = firstNonZero(textKey.keySymbolOffsetY, selfConfig.keySymbolOffsetY, theme.generalStyle.keySymbolOffsetY)
                key.keyHintOffsetX = firstNonZero(textKey.keyHintOffsetX, selfConfig.keyHintOffsetX, theme.generalStyle.keyHintOffsetX)
                key.keyHintOffsetY = firstNonZero(textKey.keyHintOffsetY, selfConfig.keyHintOffsetY, theme.generalStyle.keyHintOffsetY)
                key.keyPressOffsetX = firstNonZero(textKey.keyPressOffsetX, selfConfig.keyPressOffsetX, theme.generalStyle.keyPressOffsetX)
                key.keyPressOffsetY = firstNonZero(textKey.keyPressOffsetY, selfConfig.keyPressOffsetY, theme.generalStyle.keyPressOffsetY)

                key.x = xPos
                key.y = yPos

                // Correct minor rounding errors on the right edge. Only the last key of a row
                // may take this: stretching a key in the middle would push everything after it
                // past the edge, which the first pass never accounted for.
                val isRowLast = keyIndex == keys.lastIndex || keyRows[keyIndex + 1] != targetRow
                val rightGap = abs(allowedWidth - xPos - widthPx)
                key.width =
                    if (isRowLast && rightGap <= allowedWidth / 100) allowedWidth - xPos else widthPx

                key.height = currentRowHeight
                key.row = row
                key.column = column

                column++
                xPos += key.width

                mKeys.add(key)

                if (xPos > minWidth) {
                    minWidth = xPos
                }
            }

            // Expand keypress area to edge by distributing spacer widths to neighbors
            if (expandKeypressArea && spacers.isNotEmpty()) {
                for ((spacerX, spacerWidth, spacerRow) in spacers) {
                    val (leftKeys, rightKeys) = mKeys.filter { it.row == spacerRow }.partition { it.x + it.width <= spacerX }
                    val leftKey = leftKeys.maxByOrNull { it.x }
                    val rightKey = rightKeys.minByOrNull { it.x }
                    when {
                        leftKey != null && rightKey != null -> {
                            leftKey.extraWidthRight += spacerWidth / 2
                            rightKey.extraWidthLeft += spacerWidth - spacerWidth / 2
                        }
                        leftKey != null -> leftKey.extraWidthRight += spacerWidth
                        rightKey != null -> rightKey.extraWidthLeft += spacerWidth
                    }
                }
            }

            mKeys.lastOrNull()?.edgeFlags = mKeys.lastOrNull()?.edgeFlags?.or(EDGE_RIGHT) ?: 0

            height = yPos + currentRowHeight

            mKeys.forEachIndexed { index, key ->
                key.index = index
                if (key.column == 0) key.edgeFlags = key.edgeFlags or EDGE_LEFT
                if (key.row == 0) key.edgeFlags = key.edgeFlags or EDGE_TOP
                if (key.row == row) key.edgeFlags = key.edgeFlags or EDGE_BOTTOM
            }
        }
    }

    fun setModifierKey(
        c: Int,
        key: Key?,
    ) {
        when (c) {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                mShiftKey = key
            }
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> {
                mCtrlKey = key
            }
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> {
                mMetaKey = key
            }
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> {
                mAltKey = key
            }
            KeyEvent.KEYCODE_SYM -> {
                mSymKey = key
            }
        }
    }

    val keys: List<Key>
        get() = mKeys

    /**
     * Re-reads the runtime options toggled by the keys of this keyboard.
     *
     * Blocks until rime answers, so it belongs to keyboard (re)activation, not
     * to the draw path.
     */
    fun refreshToggleStates() = keys.forEach { it.refreshToggleStates() }

    /** Forwards an option change reported by rime to every key. */
    fun updateToggleState(
        option: String,
        value: Boolean,
    ) = keys.forEach { it.updateToggleState(option, value) }

    private fun setModifier(
        mask: Int,
        value: Boolean,
    ): Boolean {
        if (modifier.hasFlag(mask) == value) return false
        modifier = if (value) modifier or mask else modifier and mask.inv()
        return true
    }

    val isShifted: Boolean
        get() = modifier.hasFlag(KeyEvent.META_SHIFT_ON) || mShiftKey?.isOn == true

    val isOnlyShiftOn: Boolean
        get() =
            isShifted &&
                !modifier.hasFlag(KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SYM_ON or KeyEvent.META_META_ON)

    /**
     * 设置Shift键状态（用于自动大写）
     *
     * @param on 是否锁定Shift键
     * @param shifted 是否按下Shift键
     * @return Shift键状态是否改变
     */
    fun setShifted(
        on: Boolean,
        shifted: Boolean,
    ): Boolean {
        mShiftKey?.setOn(on)
        return setModifier(KeyEvent.META_SHIFT_ON, shifted)
    }

    /**
     * 设置修饰键的状态
     *
     * @param on 是否锁定修饰键
     * @param keycode 修饰键的 KeyEvent 掩码
     * @return 修饰键状态是否改变
     */
    fun clickModifierKey(
        on: Boolean,
        keycode: Int,
    ): Boolean {
        val keyDown = !modifier.hasFlag(keycode)
        val modifierKey =
            when (keycode) {
                KeyEvent.META_SHIFT_ON -> mShiftKey
                KeyEvent.META_ALT_ON -> mAltKey
                KeyEvent.META_CTRL_ON -> mCtrlKey
                KeyEvent.META_META_ON -> mMetaKey
                KeyEvent.KEYCODE_SYM -> mSymKey
                else -> null
            }
        val keepOn = modifierKey?.setOn(on) ?: on
        return if (on) setModifier(keycode, keepOn) else setModifier(keycode, keyDown)
    }

    fun refreshModifier(): Boolean {
        // 这里改为了一次性重置全部修饰键状态并返回TRUE刷新UI，可能有bug
        var result = false
        if (mShiftKey != null && !mShiftKey!!.isOn) result = result || setModifier(KeyEvent.META_SHIFT_ON, false)
        if (mAltKey != null && !mAltKey!!.isOn) result = result || setModifier(KeyEvent.META_ALT_ON, false)
        if (mCtrlKey != null && !mCtrlKey!!.isOn) result = result || setModifier(KeyEvent.META_CTRL_ON, false)
        if (mMetaKey != null && !mMetaKey!!.isOn) result = result || setModifier(KeyEvent.META_META_ON, false)
        if (mSymKey != null && !mSymKey!!.isOn) result = result || setModifier(KeyEvent.KEYCODE_SYM, false)
        return result
    }

    private fun computeNearestNeighbors() {
        // Round-up so we don't have any pixels outside the grid
        mCellWidth = (minWidth + GRID_WIDTH - 1) / GRID_WIDTH
        mCellHeight = (height + GRID_HEIGHT - 1) / GRID_HEIGHT
        gridNeighbors = arrayOfNulls(GRID_SIZE)
        val indices = IntArray(mKeys.size)
        val gridWidth = GRID_WIDTH * mCellWidth
        val gridHeight = GRID_HEIGHT * mCellHeight
        var x = 0
        while (x < gridWidth) {
            var y = 0
            while (y < gridHeight) {
                var count = 0
                for (i in mKeys.indices) {
                    val key = mKeys[i]
                    if (key.squaredDistanceFrom(x, y) < proximityThreshold ||
                        key.squaredDistanceFrom(x + mCellWidth - 1, y) < proximityThreshold ||
                        (
                            key.squaredDistanceFrom(x + mCellWidth - 1, y + mCellHeight - 1)
                                < proximityThreshold
                            ) ||
                        key.squaredDistanceFrom(x, y + mCellHeight - 1) < proximityThreshold ||
                        key.isInside(x, y) ||
                        key.isInside(x + mCellWidth - 1, y) ||
                        key.isInside(x + mCellWidth - 1, y + mCellHeight - 1) ||
                        key.isInside(x, y + mCellHeight - 1)
                    ) {
                        indices[count++] = i
                    }
                }
                val cell = IntArray(count)
                System.arraycopy(indices, 0, cell, 0, count)
                gridNeighbors?.set(y / mCellHeight * GRID_WIDTH + x / mCellWidth, cell)
                y += mCellHeight
            }
            x += mCellWidth
        }
    }

    /**
     * Returns the indices of the keys that are closest to the given point.
     *
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the array of integer indices for the nearest keys to the given point. If the given
     * point is out of range, then an array of size zero is returned.
     */
    fun getNearestKeys(
        x: Int,
        y: Int,
    ): IntArray? {
        if (gridNeighbors == null) computeNearestNeighbors()
        if (x in 0 until minWidth && y in 0 until height) {
            val index = y / mCellHeight * GRID_WIDTH + x / mCellWidth
            if (index < GRID_SIZE) {
                return gridNeighbors!![index]
            }
        }
        return IntArray(0)
    }

    val isLabelUppercase: Boolean
        get() = labelTransform == TextKeyboard.LabelTransform.UPPERCASE

    companion object {
        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08
        private const val GRID_WIDTH = 10
        private const val GRID_HEIGHT = 5
        private const val GRID_SIZE = GRID_WIDTH * GRID_HEIGHT
        private const val MAX_TOTAL_WEIGHT = 100

        /** Number of key widths from current touch point to search for nearest keys.  */
        const val SEARCH_DISTANCE = 1.4f
    }
}
