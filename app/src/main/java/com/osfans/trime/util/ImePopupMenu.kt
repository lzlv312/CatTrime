/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.res.use
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.styledDrawable
import splitties.views.dsl.core.withTheme
import kotlin.math.max
import kotlin.math.min

/**
 * A drop-down menu that is safe to show from inside the input method window.
 *
 * `android.widget.PopupMenu` cannot be used here. It is built on `ListPopupWindow`, whose
 * constructor calls `setInputMethodMode(INPUT_METHOD_NEEDED)` on its internal `PopupWindow`.
 * Because that popup is also not focusable, `PopupWindow.computeFlags()` then adds
 * `FLAG_ALT_FOCUSABLE_IM` — the flag documented to block *every view below it* from connecting
 * to the input method. With it set the editor stops talking to the IME: the framework drops the
 * input session (`onStartInput(restarting = true)`), the input method falls back to its main
 * keyboard and detaches whatever board window the menu was anchored in, which dismisses the
 * menu. The user sees the panel they were in flash away the moment they long press — the
 * clipboard list, say. (`FLAG_ALT_FOCUSABLE_IM` is also what used to break the theme pickers;
 * see `TrimeInputMethodService.showDialog`.)
 *
 * This popup keeps that flag off: it is not focusable, and `INPUT_METHOD_NOT_NEEDED` only adds
 * the flag above when the popup *is* focusable. The editor therefore keeps its input
 * connection and the panel stays put. Anchoring to a view in the input method makes it a child
 * window of the input method, so it is drawn just above the keyboard and goes away with it.
 */
class ImePopupMenu(anchor: View) {
    private val anchorView = anchor

    private val ctx: Context =
        anchor.context.withTheme(android.R.style.Theme_DeviceDefault_Settings)

    private val panelBackground =
        GradientDrawable().apply {
            setColor(colorOf(android.R.attr.colorBackgroundFloating, Color.DKGRAY))
            cornerRadius = ctx.dp(12).toFloat()
        }

    /**
     * The window background: it paints nothing (the visible panel is [panelBackground] on [rows]),
     * but it hands the popup an outline, which is what the elevation shadow needs. `PopupWindow`
     * elevates exactly the view that owns its background drawable and lets that view cast the
     * shadow from its outline — a plain transparent background has an empty outline, so the
     * elevation set on the popup would never produce one. Reporting the panel's rounded rect here
     * gives the menu a shadow that follows its corners. Reporting `TRANSLUCENT` keeps the window
     * format translucent: the popup's pixel format is taken from the background's opacity, and an
     * opaque one would fill the area outside the rounded corners with black.
     */
    private val panelOutline =
        object : Drawable() {
            override fun draw(canvas: Canvas) = Unit
            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
            override fun getOutline(outline: Outline) {
                outline.setRoundRect(bounds, ctx.dp(12).toFloat())
            }
        }

    private val rows =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ctx.dp(4), 0, ctx.dp(4))
            background = panelBackground
        }

    /**
     * Menus are short, but the popup is capped to whatever room is actually left (see [show]), so
     * anything that does not fit still has to be reachable.
     */
    private val scroller =
        ScrollView(ctx).apply {
            addView(rows, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            isFillViewport = true
        }

    private val popup =
        PopupWindow(ctx).apply {
            contentView = scroller
            width = ViewGroup.LayoutParams.WRAP_CONTENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            // Both of these matter, see the class comment.
            isFocusable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            // Outside touch is what makes a popup dismiss on a tap outside it; the visible
            // background lives on the content, while the window background only carries the
            // outline the elevation shadow is cast from (see [panelOutline]).
            isOutsideTouchable = true
            setBackgroundDrawable(panelOutline)
            elevation = ctx.dp(8).toFloat()
            setOnDismissListener { onDismiss?.invoke() }
        }

    /** Called when the menu goes away, however it goes away. */
    var onDismiss: (() -> Unit)? = null

    val isShowing: Boolean get() = popup.isShowing

    /**
     * Resolves a themed colour attribute to a concrete colour int.
     *
     * Two traps live here. `splitties`' [styledColor] reads the attribute with
     * `TypedArray.getColor`, which only handles direct colour ints — an attribute backed by a
     * colour *state list* (as most framework text colours are) silently comes back as the
     * fallback, i.e. transparent. And `TypedArray.getColor` throws for references it cannot
     * coerce. Going through [getColorStateList] handles both: state lists resolve to their
     * default colour, direct colours still work, and anything unresolved falls back.
     */
    private fun colorOf(
        @AttrRes attr: Int,
        fallback: Int,
    ): Int = runCatching {
        ctx.obtainStyledAttributes(intArrayOf(attr)).use { ta ->
            ta.getColorStateList(0)?.defaultColor ?: fallback
        }
    }.getOrDefault(fallback)

    private fun addRow(
        title: CharSequence,
        @DrawableRes icon: Int,
        @ColorInt iconTint: Int,
        enabled: Boolean,
        onClick: Function0<Any?>?,
    ) {
        val color =
            if (enabled) {
                colorOf(android.R.attr.textColorPrimary, Color.WHITE)
            } else {
                colorOf(android.R.attr.textColorTertiary, Color.GRAY)
            }
        val row =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
                minimumWidth = ctx.dp(180)
                // IME controls must not take focus away from the active editor.
                isFocusable = false
            }
        if (enabled && onClick != null) {
            row.isClickable = true
            row.background = ctx.styledDrawable(android.R.attr.selectableItemBackground)
            row.setOnClickListener {
                dismiss()
                onClick.invoke()
            }
        }
        if (icon != 0) {
            val iconView =
                ImageView(ctx).apply {
                    setImageDrawable(
                        ctx.drawable(icon)?.apply {
                            setTint(if (iconTint != 0) iconTint else color)
                        },
                    )
                }
            row.addView(
                iconView,
                LinearLayout.LayoutParams(ctx.dp(20), ctx.dp(20)).apply { marginEnd = ctx.dp(16) },
            )
        }
        val label =
            TextView(ctx).apply {
                text = title
                textSize = 16f
                setTextColor(color)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }
        row.addView(
            label,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        rows.addView(row)
    }

    fun item(
        @StringRes title: Int,
        @DrawableRes icon: Int = 0,
        @ColorInt iconTint: Int = 0,
        enabled: Boolean = true,
        onClick: Function0<Any?>? = null,
    ): ImePopupMenu = item(ctx.getString(title), icon, iconTint, enabled, onClick)

    fun item(
        title: CharSequence,
        @DrawableRes icon: Int = 0,
        @ColorInt iconTint: Int = 0,
        enabled: Boolean = true,
        onClick: Function0<Any?>? = null,
    ): ImePopupMenu {
        addRow(title, icon, iconTint, enabled, onClick)
        return this
    }

    /**
     * Shows the menu next to the anchor, flipping it to whichever side has more room and capping
     * its height to that room.
     *
     * `PopupMenu` gets this for free from `ListPopupWindow`, which measures the space left and
     * shrinks the list to fit. A plain `showAsDropDown` does not: a menu anchored near the bottom
     * of the keyboard runs off the bottom of the screen and its last entries become unreachable,
     * which is exactly what it looks like when the clipboard list only shows its first few
     * actions.
     */
    fun show() {
        if (popup.isShowing) popup.dismiss()

        rows.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val menuHeight = rows.measuredHeight

        val anchorLocation = IntArray(2)
        anchorView.getLocationOnScreen(anchorLocation)
        val anchorTop = anchorLocation[1]
        val anchorBottom = anchorTop + anchorView.height

        // A panel window is a child of the window it is anchored in, and it is also clipped by the
        // screen. Which of the two bounds wins is not something worth relying on, so take the
        // tighter one — the menu then fits under either.
        val visibleFrame = Rect()
        anchorView.getWindowVisibleDisplayFrame(visibleFrame)
        val rootLocation = IntArray(2)
        val root = anchorView.rootView
        root.getLocationOnScreen(rootLocation)
        val availableTop = max(visibleFrame.top, rootLocation[1])
        val availableBottom = min(visibleFrame.bottom, rootLocation[1] + root.height)

        val spaceBelow = availableBottom - anchorBottom
        val spaceAbove = anchorTop - availableTop
        val dropDown = spaceBelow >= menuHeight || spaceBelow >= spaceAbove
        val space = if (dropDown) spaceBelow else spaceAbove

        if (menuHeight <= 0 || space <= 0) {
            // Nothing meaningful to measure against; leave the layout to the framework.
            popup.height = ViewGroup.LayoutParams.WRAP_CONTENT
            popup.showAsDropDown(anchorView)
            return
        }

        popup.height = min(menuHeight, space)
        // When the menu is flipped the offset is measured from the bottom of the anchor, so it has
        // to be moved up by its own height as well.
        val offset = if (dropDown) 0 else -(anchorView.height + popup.height)
        popup.showAsDropDown(anchorView, 0, offset)
    }

    fun dismiss() {
        popup.dismiss()
    }
}
