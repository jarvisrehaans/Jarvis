package com.jarvis.assistant.ui.main

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.model.ChatMessage
import com.jarvis.assistant.util.AnimUtils
import com.jarvis.assistant.util.ThemeManager
import kotlin.random.Random

/**
 * 20-bar waveform, amplitude-reactive, lerp-animated toward target heights,
 * rendered with a soft vertical gradient so it visually matches the orb.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barCount = 24
    private val barHeights = FloatArray(barCount) { 0.04f }
    private val targetHeights = FloatArray(barCount) { 0.04f }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var isActive = false
    private var currentAmplitude = 0f

    private var colorStart = Color.parseColor("#2979FF")
    private var colorEnd = Color.parseColor("#0091EA")

    /** Keeps the waveform's palette in sync with the orb's current state. */
    fun setColors(start: Int, end: Int) {
        colorStart = start
        colorEnd = end
        invalidate()
    }

    fun startAnimation() {
        isActive = true
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 55
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                if (isActive) {
                    for (i in 0 until barCount) {
                        if (Random.nextFloat() < 0.28f) {
                            targetHeights[i] = (0.12f + currentAmplitude * Random.nextFloat()).coerceIn(0.04f, 1f)
                        }
                        barHeights[i] += (targetHeights[i] - barHeights[i]) * 0.32f
                    }
                    invalidate()
                }
            }
            start()
        }
    }

    fun stopAnimation() {
        isActive = false
        animator?.cancel()
        for (i in 0 until barCount) {
            targetHeights[i] = 0.04f
            barHeights[i] = 0.04f
        }
        invalidate()
    }

    fun setAmplitude(rms: Float) {
        currentAmplitude = rms.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width.toFloat() / (barCount * 1.7f)
        val gap = barWidth * 0.7f
        val totalWidth = (barWidth + gap) * barCount
        var x = (width - totalWidth) / 2f
        val midY = height / 2f

        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            colorStart, colorEnd, Shader.TileMode.CLAMP
        )

        for (i in 0 until barCount) {
            val h = (barHeights[i] * height).coerceAtLeast(3f)
            paint.alpha = (150 + barHeights[i] * 105).toInt().coerceIn(150, 255)
            canvas.drawRoundRect(
                x, midY - h / 2f, x + barWidth, midY + h / 2f,
                barWidth / 2f, barWidth / 2f, paint
            )
            x += barWidth + gap
        }
    }
}

/**
 * RecyclerView adapter for the chat transcript (user + JARVIS bubbles), with a
 * gentle fade + rise entrance animation on each newly inserted bubble.
 */
class ChatAdapter(private val messages: MutableList<ChatMessage> = mutableListOf()) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_JARVIS = 1
    }

    private var lastAnimatedPosition = -1

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    class JarvisViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) TYPE_USER else TYPE_JARVIS

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_USER) {
            UserViewHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
        } else {
            JarvisViewHolder(inflater.inflate(R.layout.item_chat_jarvis, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        when (holder) {
            is UserViewHolder -> {
                holder.text.text = msg.text
                holder.text.setBackgroundResource(ThemeManager.getUserChatDrawable(holder.itemView.context))
            }
            is JarvisViewHolder -> {
                holder.text.text = msg.text
            }
        }

        if (position > lastAnimatedPosition) {
            lastAnimatedPosition = position
            AnimUtils.entranceAnimate(holder.itemView)
        } else {
            holder.itemView.animate().cancel()
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
        }
    }

    override fun getItemCount(): Int = messages.size

    /** Returns the text of the last JARVIS (non-user) message, or null if none exists. */
    fun lastJarvisText(): String? = messages.lastOrNull { !it.isUser }?.text

    fun addMessage(message: ChatMessage) {
        // Deduplicate: skip if last JARVIS message is identical to the new one
        if (!message.isUser && message.text == lastJarvisText()) return
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun setMessages(newList: List<ChatMessage>) {
        messages.clear()
        messages.addAll(newList)
        lastAnimatedPosition = -1
        notifyDataSetChanged()
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        lastAnimatedPosition = -1
        notifyItemRangeRemoved(0, size)
    }
}