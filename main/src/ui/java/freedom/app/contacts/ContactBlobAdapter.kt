package freedom.app.contacts

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import freedom.app.data.entity.ContactData
import freedom.app.databinding.ItemContactBlobBinding
import freedom.app.helper.KeyHealth
import freedom.app.helper.KeyHealthCalculator

/** Material Green 500 — contact key is healthy. */
private const val COLOR_HEALTH_GREEN = 0xFF4CAF50.toInt()
/** Material Amber 500 — key rotation approaching. */
private const val COLOR_HEALTH_AMBER = 0xFFFFC107.toInt()
/** Material Red 500 — key needs immediate rotation. */
private const val COLOR_HEALTH_RED   = 0xFFF44336.toInt()

class ContactBlobAdapter(
    private val onSelect: (ContactData) -> Unit,
    /** Called when a contact's key health drops to RED and an active connection exists. */
    private val onKeyRefreshNeeded: (ContactData) -> Unit = {},
    /** Called on long-press of a contact blob for sharing / context actions. */
    private val onLongPress: (ContactData) -> Unit = {}
) : RecyclerView.Adapter<ContactBlobAdapter.ViewHolder>() {

    private val items = mutableListOf<ContactData>()
    private var selectedId: Long = -1L

    inner class ViewHolder(val binding: ItemContactBlobBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBlobBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = items[position]
        val selected = contact.id == selectedId

        holder.binding.tvInitials.text = initials(contact.name)

        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(blobColor(contact.name))
            if (selected) setStroke(4, 0xFFFFFFFF.toInt())
        }
        holder.binding.tvInitials.background = circle

        // Key health pin
        val health = KeyHealthCalculator.compute(contact, holder.itemView.context)
        val pinColor = when (health) {
            KeyHealth.GREEN  -> COLOR_HEALTH_GREEN
            KeyHealth.YELLOW -> COLOR_HEALTH_AMBER
            KeyHealth.RED    -> COLOR_HEALTH_RED
        }
        holder.binding.vKeyHealthPin.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(pinColor)
            setStroke(2, 0xFFFFFFFF.toInt())          // thin white ring for visibility
        }

        if (health == KeyHealth.RED) onKeyRefreshNeeded(contact)

        holder.itemView.setOnClickListener {
            val prev = items.indexOfFirst { it.id == selectedId }
            selectedId = contact.id
            if (prev >= 0) notifyItemChanged(prev)
            notifyItemChanged(position)
            onSelect(contact)
        }

        holder.itemView.setOnLongClickListener {
            onLongPress(contact)
            true
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<ContactData>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        val prev = items.indexOfFirst { it.id == selectedId }
        selectedId = -1L
        if (prev >= 0) notifyItemChanged(prev)
    }

    private fun initials(name: String): String {
        val words = name.trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return when {
            words.isEmpty() -> "?"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "${words[0][0]}${words[1][0]}".uppercase()
        }
    }

    private fun blobColor(name: String): Int {
        val palette = intArrayOf(
            0xFF1565C0.toInt(), // blue
            0xFF2E7D32.toInt(), // green
            0xFF6A1B9A.toInt(), // purple
            0xFFC62828.toInt(), // red
            0xFF00695C.toInt(), // teal
            0xFFE65100.toInt(), // deep orange
            0xFF37474F.toInt(), // blue-grey
            0xFF4527A0.toInt()  // deep purple
        )
        return palette[Math.abs(name.hashCode()) % palette.size]
    }
}
