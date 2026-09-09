package com.jarvis.assistant.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.util.ThemeManager

/**
 * An iOS-style segmented control: a rounded track with a sliding accent "pill"
 * indicator behind whichever option is selected. Used for the Personality picker.
 */
class SegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val indicator: android.view.View
    private val labelsRow: LinearLayout
    private var options: List<String> = emptyList()
    private var selectedIndex = 0
    private var onSelectionChanged: ((Int) -> Unit)? = null
    private val labelViews = mutableListOf<TextView>()

    init {
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(ContextCompat.getColor(context, R.color.surface_glass_2))
        }

        indicator = android.view.View(context).apply {
            background = createIndicatorDrawable()
        }
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT))

        labelsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        addView(labelsRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun refreshTheme() {
        indicator.background = createIndicatorDrawable()
        for ((i, tv) in labelViews.withIndex()) {
            val colorRes = if (i == selectedIndex) R.color.text_on_accent else R.color.text_secondary
            tv.setTextColor(ContextCompat.getColor(context, colorRes))
        }
    }

    private fun createIndicatorDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            colors = intArrayOf(
                ThemeManager.getPrimaryColorInt(context),
                ThemeManager.getGlowColorInt(context)
            )
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
        }
    }

    fun setOptions(newOptions: List<String>, initialSelected: Int = 0) {
        options = newOptions
        selectedIndex = initialSelected
        labelsRow.removeAllViews()
        labelViews.clear()

        for ((index, label) in newOptions.withIndex()) {
            val tv = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (index == selectedIndex) R.color.text_on_accent else R.color.text_secondary
                    )
                )
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { select(index, animate = true) }
            }
            labelViews.add(tv)
            labelsRow.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }

        post { layoutIndicator(animate = false) }
    }

    fun onSelectionChange(listener: (Int) -> Unit) {
        onSelectionChanged = listener
    }

    fun select(index: Int, animate: Boolean = true) {
        if (index !in options.indices) return
        selectedIndex = index
        layoutIndicator(animate)
        for ((i, tv) in labelViews.withIndex()) {
            val colorRes = if (i == index) R.color.text_on_accent else R.color.text_secondary
            tv.setTextColor(ContextCompat.getColor(context, colorRes))
        }
        onSelectionChanged?.invoke(index)
    }

    private fun layoutIndicator(animate: Boolean) {
        if (labelViews.isEmpty() || width == 0) return
        val segmentWidth = (width - paddingLeft - paddingRight) / options.size
        val targetX = (paddingLeft + segmentWidth * selectedIndex).toFloat()

        val params = indicator.layoutParams
        params.width = segmentWidth
        indicator.layoutParams = params

        if (animate) {
            indicator.animate()
                .translationX(targetX)
                .setDuration(320)
                .setInterpolator(OvershootInterpolator(1.6f))
                .start()
        } else {
            indicator.translationX = targetX
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutIndicator(animate = false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
