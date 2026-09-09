package com.jarvis.assistant.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.model.GroupChatMessage
import com.jarvis.assistant.util.AnimUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView adapter for the Jarvis Group Chat.
 *
 * - Self messages (right-aligned, blue/accent bubble)
 * - Other users' messages (left-aligned, grey bubble with sender name + optional ADMIN badge)
 */
class GroupChatAdapter(
    private val currentUserEmail: String
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_SELF = 0
        private const val TYPE_OTHER = 1
    }

    private val messages = mutableListOf<GroupChatMessage>()
    private var lastAnimatedPosition = -1

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())

    class SelfViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
    }

    class OtherViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val senderNameText: TextView = view.findViewById(R.id.senderNameText)
        val adminBadge: TextView = view.findViewById(R.id.adminBadge)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestampText: TextView = view.findViewById(R.id.timestampText)
    }

    override fun getItemViewType(position: Int): Int {
        val msg = messages[position]
        return if (msg.senderEmail.trim().lowercase() == currentUserEmail.trim().lowercase()) {
            TYPE_SELF
        } else {
            TYPE_OTHER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SELF) {
            SelfViewHolder(inflater.inflate(R.layout.item_group_chat_self, parent, false))
        } else {
            OtherViewHolder(inflater.inflate(R.layout.item_group_chat_other, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        val formattedTime = timeFormat.format(Date(msg.timestamp))

        when (holder) {
            is SelfViewHolder -> {
                holder.messageText.text = msg.text
                holder.timestampText.text = formattedTime
            }
            is OtherViewHolder -> {
                holder.senderNameText.text = if (msg.isAdmin) "Admin" else msg.senderName
                holder.messageText.text = msg.text
                holder.timestampText.text = formattedTime

                // Show ADMIN badge for admin users
                if (msg.isAdmin) {
                    holder.adminBadge.visibility = View.VISIBLE
                } else {
                    holder.adminBadge.visibility = View.GONE
                }
            }
        }

        // Entrance animation for new messages
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

    /**
     * Updates the entire message list (called from Firestore snapshot listener).
     */
    fun setMessages(newMessages: List<GroupChatMessage>) {
        val previousSize = messages.size
        messages.clear()
        messages.addAll(newMessages)
        if (previousSize == 0) {
            lastAnimatedPosition = -1
            notifyDataSetChanged()
        } else {
            // Animate only newly added messages
            lastAnimatedPosition = previousSize - 1
            notifyDataSetChanged()
        }
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        lastAnimatedPosition = -1
        notifyItemRangeRemoved(0, size)
    }
}
