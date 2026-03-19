package freedom.app.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.activeSendKey
import freedom.app.data.entity.activeRecvKey
import freedom.app.databinding.ItemContactBinding

class ContactAdapter(
    private val items: MutableList<ContactData>,
    private val onDelete: (ContactData) -> Unit,
    private val onConnect: (ContactData) -> Unit
) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemContactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val contact = items[position]
        holder.binding.tvContactName.text = contact.name

        // Show first DDNS and how many total
        val ddnsList = contact.ddnsNames.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val domainsText = if (ddnsList.isEmpty()) "—"
        else ddnsList.first() + if (ddnsList.size > 1) " (+${ddnsList.size - 1})" else ""
        holder.binding.tvContactDomains.text = domainsText

        holder.binding.tvKeyStatus.text = keyStatusText(contact)

        val hasKeys = contact.activeSendKey.isNotEmpty() && contact.activeRecvKey.isNotEmpty()
        holder.binding.btnConnectContact.isEnabled = hasKeys

        holder.binding.btnConnectContact.setOnClickListener { onConnect(contact) }
        holder.binding.btnDeleteContact.setOnClickListener { onDelete(contact) }
    }

    private fun keyStatusText(contact: ContactData): String {
        val hasSend = contact.activeSendKey.isNotEmpty()
        val hasRecv = contact.activeRecvKey.isNotEmpty()
        return when {
            hasSend && hasRecv -> "Keys: OK"
            hasSend            -> "Keys: send only"
            hasRecv            -> "Keys: recv only"
            else               -> "No keys"
        }
    }

    override fun getItemCount() = items.size

    fun update(newItems: List<ContactData>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
