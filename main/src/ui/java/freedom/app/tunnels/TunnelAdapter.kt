package freedom.app.tunnels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import freedom.app.data.entity.TunnelProfile
import freedom.app.databinding.ItemTunnelProfileBinding

class TunnelAdapter(
    private val items: MutableList<TunnelProfile>,
    private val onDelete: (TunnelProfile) -> Unit,
    private val onMoveUp: (TunnelProfile) -> Unit,
    private val onMoveDown: (TunnelProfile) -> Unit
) : RecyclerView.Adapter<TunnelAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemTunnelProfileBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTunnelProfileBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = items[position]
        holder.binding.tvTunnelPriority.text = (position + 1).toString()
        holder.binding.tvTunnelName.text = profile.name

        holder.binding.tvTunnelAddress.text = when {
            profile.publicAddress.isNotEmpty() -> profile.publicAddress
            else -> "(address pending)"
        }

        holder.binding.tvTunnelStatus.text = when (profile.type) {
            TunnelProfile.TYPE_PLAYIT -> "playit.gg"
            TunnelProfile.TYPE_NGROK  -> "ngrok"
            TunnelProfile.TYPE_OVPN   -> "Custom OVPN"
            else -> profile.type
        }

        holder.binding.btnTunnelUp.isEnabled   = position > 0
        holder.binding.btnTunnelDown.isEnabled = position < items.size - 1

        holder.binding.btnTunnelUp.setOnClickListener   { onMoveUp(profile) }
        holder.binding.btnTunnelDown.setOnClickListener { onMoveDown(profile) }
        holder.binding.btnTunnelDelete.setOnClickListener { onDelete(profile) }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<TunnelProfile>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
