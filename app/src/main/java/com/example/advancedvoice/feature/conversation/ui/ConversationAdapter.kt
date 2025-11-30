package com.example.advancedvoice.feature.conversation.ui

import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.advancedvoice.R
import com.example.advancedvoice.domain.entities.ConversationEntry

class ConversationAdapter(
    private val onReplayClicked: (Int) -> Unit
) : ListAdapter<ConversationEntry, ConversationAdapter.VH>(Diff()) {

    class VH(view: View, private val onReplayClicked: (Int) -> Unit) : RecyclerView.ViewHolder(view) {
        // Containers for different message types
        private val userContainer: LinearLayout = view.findViewById(R.id.userMessageContainer)
        private val assistantContainer: LinearLayout = view.findViewById(R.id.assistantMessageContainer)
        private val systemMessageText: TextView = view.findViewById(R.id.systemMessageText)

        // Assistant views
        private val assistantMessage: TextView = view.findViewById(R.id.textAssistantMessage)
        private val replayButton: ImageButton = view.findViewById(R.id.replayButton)

        // User views
        private val userMessage: TextView = view.findViewById(R.id.textUserMessage)
        private val userStatus: TextView = view.findViewById(R.id.userTranscriptStatus)

        fun bind(entry: ConversationEntry, index: Int) {
            // Reset visibility
            userContainer.visibility = View.GONE
            assistantContainer.visibility = View.GONE
            systemMessageText.visibility = View.GONE

            when {
                // -------------------------------------------------
                // ASSISTANT MESSAGE
                // -------------------------------------------------
                entry.isAssistant -> {
                    assistantContainer.visibility = View.VISIBLE

                    val text = if (!entry.streamingText.isNullOrBlank()) {
                        entry.streamingText
                    } else {
                        entry.sentences.joinToString(" ")
                    }
                    assistantMessage.text = text

                    replayButton.setOnClickListener { onReplayClicked(index) }
                }

                // -------------------------------------------------
                // USER MESSAGE
                // -------------------------------------------------
                entry.speaker == "You" -> {
                    userContainer.visibility = View.VISIBLE

                    val text = entry.streamingText ?: entry.sentences.joinToString(" ")
                    userMessage.text = text

                    // Handle "Preview" vs "Final" status
                    when {
                        entry.isRefinedTranscript -> {
                            userStatus.text = "Final Transcript"
                            userStatus.setTextColor(0xFF4CAF50.toInt()) // Green
                            userStatus.visibility = View.VISIBLE
                        }
                        // If we have streaming text OR it's not refined yet, it's a preview
                        entry.streamingText != null || (!entry.isRefinedTranscript && text.isNotEmpty()) -> {
                            userStatus.text = "Preview Transcript"
                            userStatus.setTextColor(0xFFFFA726.toInt()) // Orange
                            userStatus.visibility = View.VISIBLE
                        }
                        else -> {
                            userStatus.visibility = View.GONE
                        }
                    }
                }

                // -------------------------------------------------
                // SYSTEM / ERROR MESSAGES
                // -------------------------------------------------
                else -> {
                    systemMessageText.visibility = View.VISIBLE
                    val content = entry.sentences.joinToString(" ")

                    // Support HTML links in system messages (e.g. sources)
                    systemMessageText.text = Html.fromHtml(content, Html.FROM_HTML_MODE_COMPACT)
                    systemMessageText.movementMethod = LinkMovementMethod.getInstance()

                    // Optional: Color coding for errors
                    if (entry.speaker.equals("Error", ignoreCase = true)) {
                        systemMessageText.setTextColor(0xFFEF5350.toInt()) // Red
                    } else {
                        systemMessageText.setTextColor(0xFF9E9E9E.toInt()) // Grey
                    }
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation_entry, parent, false)
        return VH(v, onReplayClicked)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }

    class Diff : DiffUtil.ItemCallback<ConversationEntry>() {
        override fun areItemsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean =
            oldItem === newItem // Simple referential check is often enough for immutable data classes if they change entirely

        override fun areContentsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean =
            oldItem == newItem
    }
}