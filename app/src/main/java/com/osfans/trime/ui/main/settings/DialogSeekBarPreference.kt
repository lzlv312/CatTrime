/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.res.TypedArray
import android.util.AttributeSet
import androidx.core.widget.TextViewCompat
import androidx.preference.DialogPreference
import com.google.android.material.slider.Slider
import com.osfans.trime.R
import com.osfans.trime.ui.common.materialAlertDialogBuilder
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin
import splitties.views.dsl.core.view
import splitties.views.gravityHorizontalCenter

/**
 * Custom preference which represents a slider and shows the current value in the summary. The
 * value can be changed by clicking on the preference, which brings up a Material 3 slider dialog.
 * This implementation also allows for a min / max step value, while being backwards compatible.
 *
 * @see R.styleable.DialogSeekBarPreferenceAttrs for which xml attributes this preference accepts
 *  besides the default Preference attributes.
 *
 * @property default The default value of this preference.
 * @property min The minimum value of the seek bar. Must not be greater or equal than [max].
 * @property max The maximum value of the seek bar. Must not be lesser or equal than [min].
 * @property step The step in which the seek bar increases per move. If the provided value is less
 *  than 1, 1 will be used as step. Note that the xml attribute's name for this property is
 *  [R.styleable.DialogSeekBarPreferenceAttrs_seekBarIncrement].
 * @property unit The unit to show after the value. Set to an empty string to disable this feature.
 */
class DialogSeekBarPreference : DialogPreference {
    private var value = 0
    var default: Int
    var systemDefaultText: String? = null

    /** Text shown instead of the value at 0, for settings where 0 means "disabled". */
    var zeroText: String? = null
    var min: Int
    var max: Int
    var step: Int
    var unit: String
    var useMinAsDefault: Boolean

    @Suppress("unused")
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) :
        this(context, attrs, androidx.preference.R.attr.preferenceStyle)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        context.theme.obtainStyledAttributes(attrs, R.styleable.DialogSeekBarPreferenceAttrs, 0, 0).run {
            try {
                min = getInt(R.styleable.DialogSeekBarPreferenceAttrs_min, 0)
                max = getInt(R.styleable.DialogSeekBarPreferenceAttrs_max, 100)
                step = getInt(R.styleable.DialogSeekBarPreferenceAttrs_seekBarIncrement, 1)
                default =
                    getInt(R.styleable.DialogSeekBarPreferenceAttrs_android_defaultValue, 0)
                systemDefaultText =
                    getString(R.styleable.DialogSeekBarPreferenceAttrs_systemDefaultValueText)
                unit = getString(R.styleable.DialogSeekBarPreferenceAttrs_unit) ?: ""
                useMinAsDefault = getBoolean(R.styleable.DialogSeekBarPreferenceAttrs_useMinAsDefault, false)
                if (getBoolean(R.styleable.DialogSeekBarPreferenceAttrs_useSimpleSummaryProvider, false)) {
                    summaryProvider = SimpleSummaryProvider
                }
            } finally {
                recycle()
            }
        }
    }

    override fun persistInt(value: Int): Boolean = super.persistInt(value).also {
        if (it) this@DialogSeekBarPreference.value = value
    }

    override fun setDefaultValue(defaultValue: Any?) {
        super.setDefaultValue(defaultValue)
        default = defaultValue as? Int ?: 0
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getInteger(index, default)

    override fun onSetInitialValue(defaultValue: Any?) {
        // coerce 到滑条范围：外部写入（等比联动、手势落盘）可能产生范围外的持久化值，
        // 不钳制的话 Slider 赋值越界会直接抛异常闪退。
        value = getPersistedInt(defaultValue as? Int ?: default).coerceIn(min, max)
    }

    // The value can also change *outside* this preference screen (keep-ratio syncing writes the
    // height pref while the user drags the width slider; a floating-keyboard gesture persists both
    // dimensions). androidx Preference has no push mechanism for that, so watch the raw store.
    private val externalValueWatcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == this.key) {
            onSetInitialValue(null)
            notifyChanged()
        }
    }

    override fun onAttached() {
        super.onAttached()
        sharedPreferences?.registerOnSharedPreferenceChangeListener(externalValueWatcher)
    }

    override fun onDetached() {
        sharedPreferences?.unregisterOnSharedPreferenceChangeListener(externalValueWatcher)
        super.onDetached()
    }

    override fun onClick() {
        showSeekBarDialog()
    }

    /**
     * Generates the text for the given [value] and adds the defined [unit] at the end.
     * A non-null [zeroText] replaces the value at 0; otherwise, if [systemDefaultText] is
     * not null this method tries to match the given [value] with [default] and returns
     * [systemDefaultText] upon matching.
     */
    private fun getTextForValue(value: Int): String = when {
        zeroText != null && value == 0 -> zeroText!!
        systemDefaultText != null && value == (if (useMinAsDefault) min else default) -> systemDefaultText!!
        unit.isBlank() -> value.toString()
        else -> "$value $unit"
    }

    /**
     * Shows the seek bar dialog.
     */
    private fun showSeekBarDialog() = with(context) {
        val textView = textView {
            text = getTextForValue(value)
            TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        }
        val slider = view(::Slider) {
            valueFrom = 0f
            valueTo = getProgressForValue(this@DialogSeekBarPreference.max).toFloat()
            stepSize = 1f
            value = getProgressForValue(this@DialogSeekBarPreference.value).toFloat()
            // 滑块内部存的是 (值 - min) / step 的档位，默认气泡会直接显示档位；
            // 还原成真实值，才能和上方文字保持一致（min 非 0 或 step > 1 时两者不同）。
            setLabelFormatter { progress -> getTextForValue(getValueForProgress(progress.toInt())) }
            addOnChangeListener { _, sliderValue, _ ->
                textView.text = getTextForValue(getValueForProgress(sliderValue.toInt()))
            }
        }
        val dialogView = verticalLayout {
            gravity = gravityHorizontalCenter
            if (dialogMessage != null) {
                val messageText = textView {
                    text = dialogMessage
                    TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                add(
                    messageText,
                    lParams {
                        verticalMargin = dp(8)
                        horizontalMargin = dp(24)
                    },
                )
            }
            add(
                textView,
                lParams {
                    verticalMargin = dp(24)
                },
            )
            add(
                slider,
                lParams {
                    width = matchParent
                    horizontalMargin = dp(16)
                    bottomMargin = dp(12)
                },
            )
        }
        // 与其余弹框共用统一的 MD3 包装（非 Material context 下自动套应用主题），
        // 保证背景、圆角、文字颜色全应用一致。
        materialAlertDialogBuilder()
            .setTitle(this@DialogSeekBarPreference.title)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val actualValue = getValueForProgress(slider.value.toInt())
                if (callChangeListener(actualValue)) {
                    persistInt(actualValue)
                    notifyChanged()
                }
            }.setNeutralButton(R.string.default_) { _, _ ->
                persistInt(default)
                notifyChanged()
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Converts the actual value to a progress value which the Android SeekBar implementation can
     * handle. (Android's SeekBar step is fixed at 1 and min at 0)
     *
     * @param value The actual value.
     * @return the internal value which is used to allow different min and step values.
     */
    private fun getProgressForValue(value: Int) = (value - min) / step

    /**
     * Converts the Android SeekBar value to the actual value.
     *
     * @param progress The progress value of the SeekBar.
     * @return the actual value which is ready to use.
     */
    private fun getValueForProgress(progress: Int) = (progress * step) + min

    object SimpleSummaryProvider : SummaryProvider<DialogSeekBarPreference> {
        override fun provideSummary(preference: DialogSeekBarPreference): CharSequence = preference.getTextForValue(preference.value)
    }
}
