package freedom.app.messages

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import freedom.app.R
import freedom.app.data.entity.MessageData

class MessageBubbleAdapter :
    ListAdapter<MessageData, MessageBubbleAdapter.BubbleViewHolder>(DIFF) {

    inner class BubbleViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_message_bubble, parent, false)
    ) {
        private val root:      LinearLayout = itemView.findViewById(R.id.bubbleRoot)
        private val container: LinearLayout = itemView.findViewById(R.id.bubbleContainer)
        private val content:   TextView     = itemView.findViewById(R.id.tvBubbleContent)
        private val timestamp: TextView     = itemView.findViewById(R.id.tvBubbleTimestamp)

        fun bind(msg: MessageData) {
            val sent = msg.direction == MessageData.SENT

            val isFile = msg.messageType == "FILE_SENT" || msg.messageType == "FILE_RECEIVED"
            content.text   = if (isFile) "📎 ${msg.content ?: ""}" else msg.content ?: ""
            timestamp.text = msg.timestamp ?: ""

            if (sent) {
                // Right-aligned teal bubble
                root.gravity      = Gravity.END
                content.setTextColor(0xFFFFFFFF.toInt())
                timestamp.setTextColor(0xCCFFFFFF.toInt())
                container.background = bubble(0xFF008577.toInt())
            } else {
                // Left-aligned grey bubble
                root.gravity      = Gravity.START
                content.setTextColor(0xFF212121.toInt())
                timestamp.setTextColor(0xFF757575.toInt())
                container.background = bubble(0xFFE0E0E0.toInt())
            }
        }

        private fun bubble(color: Int) = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = 16f
            setColor(color)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = BubbleViewHolder(parent)
    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) = holder.bind(getItem(position))

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MessageData>() {
            override fun areItemsTheSame(a: MessageData, b: MessageData) = a.id == b.id
            override fun areContentsTheSame(a: MessageData, b: MessageData) = a == b
        }
    }
}
