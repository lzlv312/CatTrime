/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.switches

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.icu.text.BreakIterator
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.FontManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.AutoScaleTextView
import com.osfans.trime.ime.keyboard.GestureFrame
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerOn
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.gravityCenter
import splitties.views.imageDrawable

class SwitchOptionEntryUi(
    override val ctx: Context,
    private val theme: Theme,
) : Ui {
    /** 图标框的“键面”：主题底色 + 边框 + 圆角；图片图标会整个替换它。 */
    private val keyDecorBackground: Drawable? =
        ColorManager.getDecorDrawable(
            "key_back_color",
            "key_border_color",
            ctx.dp(theme.generalStyle.keyBorder),
            ctx.dp(theme.generalStyle.roundCorner),
        )

    val bkg =
        frameLayout {
            background = keyDecorBackground
        }

    val icon =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }

    val textIcon =
        view(::AutoScaleTextView) {
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
            // keep original typeface, apply textStyle only
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 600 = Semi Bold, 700 = Bold which is too heavy
                typeface = Typeface.create(typeface, 600, false)
            } else {
                setTypeface(typeface, Typeface.BOLD)
            }
        }

    val label =
        textView {
            textSize = 12f
            gravity = gravityCenter
        }

    // 主题 menu_keyboard/fonts：面板字体（功能面板与方案开关共用）。
    private val panelTypeface: Typeface? =
        theme.menuKeyboard?.fonts?.takeIf { it.isNotEmpty() }?.let(FontManager::getTypefaceForFiles)

    init {
        panelTypeface?.let {
            label.typeface = it
            textIcon.typeface = it
        }
    }

    override val root =
        object : GestureFrame(ctx) {
            val content =
                constraintLayout {
                    add(
                        bkg,
                        lParams(dp(48), dp(48)) {
                            topOfParent(dp(4))
                            centerHorizontally()
                            above(label)
                        },
                    )
                    add(
                        icon,
                        lParams {
                            centerOn(bkg)
                        },
                    )
                    add(
                        textIcon,
                        lParams(wrapContent, wrapContent) {
                            centerOn(bkg)
                        },
                    )
                    add(
                        label,
                        lParams(wrapContent, wrapContent) {
                            below(bkg, dp(6))
                            centerHorizontally()
                        },
                    )
                }

            init {
                add(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                layoutParams = ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, dp(96))
            }
        }

    fun setEntry(entry: SwitchOptionEntry) {
        // 双态条目开启时 label 与图标位文字都用按键的“标明”文字色：常态是 key_text_color，
        // 高亮自然对应 hilited_key_text_color；主题未配置时由 ColorManager 的 fallback 链补齐。
        val highlighted = (entry as? SwitchOptionEntry.Menu)?.isOn == true
        val textColor = ColorManager.getColor(if (highlighted) "hilited_key_text_color" else "key_text_color")
        label.setTextColor(textColor)
        // 功能面板条目的图标位只有两种：背景图片或文字；文字优先 iconText（取自预设键的
        // state/label），没有则回落条目 label，最多两个字。
        val menuIconStyle = (entry as? SwitchOptionEntry.Menu)?.iconStyle.orEmpty()
        // 图片图标替换的是图标框本身：底色、边框、圆角一起让位，图标字也不再显示；
        // 底部 label 在图标框下方，不占它的位置，照常显示。
        val imageBackground = menuIconStyle
            .takeIf { it.matches(IMAGE_PATTERN) }
            ?.let { ColorManager.getDrawable(it) }
        bkg.background = imageBackground ?: keyDecorBackground
        when {
            imageBackground != null -> {
                icon.visibility = View.GONE
                textIcon.visibility = View.GONE
            }
            entry.icon != 0 -> {
                icon.visibility = View.VISIBLE
                textIcon.visibility = View.GONE
                icon.imageDrawable = ctx.drawable(entry.icon)!!.apply {
                    mutate()
                    setTint(ColorManager.getColor("key_text_color"))
                }
            }
            else -> {
                icon.visibility = View.GONE
                textIcon.visibility = View.VISIBLE
                if (entry is SwitchOptionEntry.Menu) {
                    textIcon.text = takeIconCharacters(entry.iconText.ifEmpty { entry.label }, 2)
                } else {
                    // 方案开关页沿用首字指示。
                    textIcon.text = takeIconCharacters(entry.label, 1)
                }
                textIcon.setTextColor(textColor)
            }
        }
        label.text = entry.label
    }

    /**
     * 图标字：按字素簇截取前 [count] 个字，避免把 emoji 等组合字符拆坏。
     */
    private fun takeIconCharacters(
        s: String,
        count: Int,
    ): String {
        if (s.isEmpty()) return ""
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return s.substring(0, s.offsetByCodePoints(0, minOf(count, s.codePointCount(0, s.length))))
        }
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(s)
        var end = iterator.first()
        repeat(count) {
            val next = iterator.next()
            if (next == BreakIterator.DONE) return s.substring(0, end)
            end = next
        }
        return s.substring(0, end)
    }
}

/** 功能面板 icons 只接受图片；与 ToolButton 的图标图片判定一致。 */
internal val IMAGE_PATTERN = ".*\\.(png|jpg|gif|webp)$".toRegex()
