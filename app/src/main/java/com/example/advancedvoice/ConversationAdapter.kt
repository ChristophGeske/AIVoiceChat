package com.example.advancedvoice

import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class ConversationAdapter(
    private val onReplayClicked: (Int) -> Unit
) : ListAdapter<ConversationEntry, ConversationAdapter.ConversationViewHolder>(ConversationDiffCallback()) {

    var currentSpeaking: SpokenSentence? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation_entry, parent, false)
        return ConversationViewHolder(view, onReplayClicked)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry, position, currentSpeaking)
    }

    class ConversationViewHolder(
        itemView: View,
        private val onReplayClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val speakerLabel: TextView = itemView.findViewById(R.id.speakerLabel)
        private val messageContent: TextView = itemView.findViewById(R.id.messageContent)
        private val replayButton: ImageButton = itemView.findViewById(R.id.replayButton)

        fun bind(entry: ConversationEntry, entryIndex: Int, currentSpeaking: SpokenSentence?) {
            // Logging
            val speakingLog = if (currentSpeaking?.entryIndex == entryIndex) {
                "highlighting sentence ${currentSpeaking.sentenceIndex}"
            } else {
                "not highlighted"
            }
            Log.d("ConvAdapter", "Binding item at index $entryIndex. State: $speakingLog")

            val colorLabelYou = "#1E88E5"
            val colorLabelAssistant = "#43A047"
            val colorLabelSystem = "#8E24AA"
            val colorLabelError = "#E53935"
            val labelColor = when (entry.speaker.lowercase(Locale.US)) {
                "you" -> colorLabelYou
                "assistant" -> colorLabelAssistant
                "system" -> colorLabelSystem
                "error" -> colorLabelError
                else -> "#CCCCCC"
            }
            speakerLabel.text = entry.speaker
            speakerLabel.setTextColor(android.graphics.Color.parseColor(labelColor))

            if (entry.isAssistant) {
                renderAssistantMessage(entry, entryIndex, currentSpeaking)
                replayButton.visibility = View.VISIBLE
                replayButton.setOnClickListener { onReplayClicked(entryIndex) }
                // Assistant content usually doesn't contain links; keep default movement method
                messageContent.movementMethod = null
                messageContent.linksClickable = false
            } else {
                // Non-assistant entries (You/System/Error/…)
                if (entry.speaker.equals("System", ignoreCase = true)) {
                    // Render System messages (e.g., "Web sources") as HTML with clickable links
                    val html = entry.sentences.joinToString(" ")
                    messageContent.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                    messageContent.movementMethod = LinkMovementMethod.getInstance()
                    messageContent.linksClickable = true
                } else {
                    // Plain text for user/error or other roles
                    messageContent.text = entry.sentences.firstOrNull().orEmpty()
                    messageContent.movementMethod = null
                    messageContent.linksClickable = false
                }
                replayButton.visibility = View.GONE
            }
        }

        private fun renderAssistantMessage(
            entry: ConversationEntry,
            entryIndex: Int,
            currentSpeaking: SpokenSentence?
        ) {
            val colorSpeaking = "#D32F2F"
            val colorContent = "#FFFFFF"
            fun escapeHtml(s: String): String = s
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br/>")

            val sb = StringBuilder()
            val speaking = currentSpeaking?.takeIf { it.entryIndex == entryIndex }

            entry.sentences.forEachIndexed { sentenceIndex, sentence ->
                val isSpeaking = (speaking?.sentenceIndex == sentenceIndex)
                val color = if (isSpeaking) colorSpeaking else colorContent
                sb.append("<font color='$color'>${escapeHtml(sentence)}</font> ")
            }

            val inStream = entry.streamingText
            if (inStream != null) {
                val completeText = entry.sentences.joinToString(" ")
                val lastSentenceEndIndexInStream = completeText.length
                if (inStream.length > lastSentenceEndIndexInStream) {
                    val remainder = inStream.substring(lastSentenceEndIndexInStream)
                    sb.append("<font color='$colorContent'>${escapeHtml(remainder)}</font>")
                }
            }
            messageContent.text = Html.fromHtml(sb.toString(), Html.FROM_HTML_MODE_COMPACT)
        }
    }
}

class ConversationDiffCallback : DiffUtil.ItemCallback<ConversationEntry>() {
    override fun areItemsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean {
        return oldItem === newItem
    }
    override fun areContentsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean {
        return oldItem == newItem
    }
}