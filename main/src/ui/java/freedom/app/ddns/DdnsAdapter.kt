package freedom.app.ddns

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import freedom.app.databinding.ItemDdnsConfigBinding

class DdnsAdapter(
    private val items: MutableList<DdnsConfig>,
    private val onEdit: (DdnsConfig) -> Unit,
    private val onDelete: (DdnsConfig) -> Unit
) : RecyclerView.Adapter<DdnsAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemDdnsConfigBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDdnsConfigBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val config = items[position]
        holder.binding.tvServiceName.text = config.serviceType.displayName
        holder.binding.tvServiceSummary.text = config.summary()
        holder.binding.btnEdit.setOnClickListener { onEdit(config) }
        holder.binding.btnDelete.setOnClickListener { onDelete(config) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<DdnsConfig>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
