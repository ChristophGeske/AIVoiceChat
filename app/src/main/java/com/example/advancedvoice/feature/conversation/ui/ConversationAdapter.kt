package com.example.advancedvoice.feature.conversation.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.advancedvoice.R
import com.example.advancedvoice.domain.entities.ConversationEntry
import java.util.Locale

class ConversationAdapter(
    private val onReplayClicked: (Int) -> Unit
) : ListAdapter<ConversationEntry, ConversationAdapter.VH>(Diff()) {

    class VH(view: View, private val onReplayClicked: (Int) -> Unit) : RecyclerView.ViewHolder(view) {
        private val speakerLabel: TextView = view.findViewById(R.id.speakerLabel)
        private val messageContent: TextView = view.findViewById(R.id.messageContent)
        private val replayButton: ImageButton = view.findViewById(R.id.replayButton)

        fun bind(entry: ConversationEntry, index: Int) {
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

            messageContent.text = entry.sentences.joinToString(" ")

            if (entry.isAssistant) {
                replayButton.visibility = View.VISIBLE
                replayButton.setOnClickListener { onReplayClicked(index) }
            } else {
                replayButton.visibility = View.GONE
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_conversation_entry, parent, false)
        return VH(v, onReplayClicked)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position), position)
    }

    class Diff : DiffUtil.ItemCallback<ConversationEntry>() {
        override fun areItemsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean = oldItem === newItem
        override fun areContentsTheSame(oldItem: ConversationEntry, newItem: ConversationEntry): Boolean = oldItem == newItem
    }
}
