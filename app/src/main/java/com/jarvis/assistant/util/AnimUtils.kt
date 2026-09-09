package com.jarvis.assistant.util

import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * Small collection of iOS-feeling interaction animations: press-down/spring-back
 * scale feedback, staggered list-entrance animation, and a reusable spring builder.
 * These are intentionally lightweight (no external animation libraries) so they
 * drop cleanly into any View.
 */
object AnimUtils {

    private const val PRESS_SCALE = 0.94f
    private const val PRESS_DOWN_DURATION = 110L

    /**
     * Applies iOS-style "press and spring back" visual feedback to any view,
     * without interfering with its existing click / long-click listeners.
     */
    @JvmStatic
    fun attachPressFeedback(view: View, pressScale: Float = PRESS_SCALE) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(pressScale)
                        .scaleY(pressScale)
                        .alpha(0.88f)
                        .setDuration(PRESS_DOWN_DURATION)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    springBack(v)
                }
            }
            false // never consume: let the underlying click/long-click still fire
        }
    }

    private fun springBack(view: View) {
        SpringAnimation(view, DynamicAnimation.SCALE_X, 1f).apply {
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
        }.start()
        SpringAnimation(view, DynamicAnimation.SCALE_Y, 1f).apply {
            spring = SpringForce(1f).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_MEDIUM_BOUNCY
            }
        }.start()
        view.animate().alpha(1f).setDuration(180).start()
    }

    /**
     * Brief "light up" cue for state changes (Idle → Listen → Think → Speak):
     * a quick brighten-and-settle pulse, distinct from [bouncePulse]'s tap
     * feedback in that it reads as "something changed" rather than "you
     * pressed something".
     */
    @JvmStatic
    fun lightUpPulse(view: View) {
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.animate()
            .scaleX(1.18f).scaleY(1.18f)
            .setDuration(140)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
            }
            .start()
    }

    /**
     * Slow, continuous alpha "breathing" loop for the mic ring, matching the
     * reference's `pulse-border-anim` (2.5s ease-in-out, infinite). Call once;
     * returns the animator so the caller can cancel it (e.g. in onDestroy) —
     * previously this ran forever with no owner, leaking the view/Activity.
     */
    @JvmStatic
    fun startBorderBreathing(view: View): android.animation.ValueAnimator {
        val animator = android.animation.ValueAnimator.ofFloat(0.55f, 1f).apply {
            duration = 1250L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { anim -> view.alpha = anim.animatedValue as Float }
        }
        animator.start()
        return animator
    }

    /**
     * One-shot bounce (down then up) for views where you just want a "tap" pulse
     * triggered programmatically (e.g. after a state change) rather than on touch.
     */
    @JvmStatic
    fun bouncePulse(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(0.9f).scaleY(0.9f)
            .setDuration(90)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(260)
                    .setInterpolator(OvershootInterpolator(3.2f))
                    .start()
            }
            .start()
    }

    /**
     * Staggered fade + rise entrance, used for chat bubbles and list rows so
     * new content settles in like iMessage rather than popping into place.
     */
    @JvmStatic
    fun entranceAnimate(view: View, delayMs: Long = 0L) {
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = 24f
        view.scaleX = 0.96f
        view.scaleY = 0.96f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f).scaleY(1f)
            .setStartDelay(delayMs)
            .setDuration(360)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }

    /**
     * Animates a horizontal "pill" indicator sliding to a new x position — used by
     * the segmented personality control in Settings.
     */
    @JvmStatic
    fun slideIndicator(indicator: View, targetX: Float) {
        SpringAnimation(indicator, DynamicAnimation.TRANSLATION_X, targetX).apply {
            spring = SpringForce(targetX).apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
        }.start()
    }

    /** Cross-fades a view's alpha smoothly, e.g. for status text swaps. */
    @JvmStatic
    fun crossFadeText(view: android.widget.TextView, newText: String, duration: Long = 220) {
        if (view.text.toString() == newText) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(duration / 2).withEndAction {
            view.text = newText
            view.animate().alpha(1f).setDuration(duration / 2).start()
        }.start()
    }
}

/** Convenience extension so call sites read naturally: `button.pressFeedback()`. */
fun View.pressFeedback(pressScale: Float = 0.94f) = AnimUtils.attachPressFeedback(this, pressScale)