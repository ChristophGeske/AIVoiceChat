package com.example.advancedvoice

import android.graphics.Color
import android.text.Html
import android.text.method.LinkMovementMethod
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

    // KORREKTUR: 'currentHighlight' property wurde entfernt.

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_conversation_entry, parent, false)
        return ConversationViewHolder(view, onReplayClicked)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        val entry = getItem(position)
        // KORREKTUR: Übergibt die property nicht mehr.
        holder.bind(entry, position)
    }

    class ConversationViewHolder(
        itemView: View,
        private val onReplayClicked: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val speakerLabel: TextView = itemView.findViewById(R.id.speakerLabel)
        private val messageContent: TextView = itemView.findViewById(R.id.messageContent)
        private val replayButton: ImageButton = itemView.findViewById(R.id.replayButton)

        // KORREKTUR: Nimmt die property nicht mehr an.
        fun bind(entry: ConversationEntry, entryIndex: Int) {
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
            speakerLabel.setTextColor(Color.parseColor(labelColor))

            if (entry.isAssistant) {
                // KORREKTUR: Ruft die vereinfachte Render-Methode auf.
                renderAssistantMessage(entry)
                replayButton.visibility = View.VISIBLE
                replayButton.setOnClickListener { onReplayClicked(entryIndex) }
            } else {
                if (entry.speaker.equals("System", ignoreCase = true)) {
                    val html = entry.sentences.joinToString(" ")
                    messageContent.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
                    messageContent.movementMethod = LinkMovementMethod.getInstance()
                } else {
                    messageContent.text = entry.sentences.firstOrNull().orEmpty()
                }
                replayButton.visibility = View.GONE
            }
        }

        // KORREKTUR: Stark vereinfachte Methode. Zeigt nur noch den Text an.
        private fun renderAssistantMessage(entry: ConversationEntry) {
            val fullText = entry.sentences.joinToString(" ")
            messageContent.text = fullText
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