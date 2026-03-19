package freedom.app.fragments

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import freedom.app.R
import freedom.app.contacts.ContactBlobAdapter
import freedom.app.data.entity.ContactData
import freedom.app.data.entity.MessageData
import freedom.app.databinding.FragmentMessagesBinding
import freedom.app.messages.MessageBubbleAdapter
import freedom.app.tcpserver.ContactConnectionManager
import freedom.app.tcpserver.ContactShareEngine
import freedom.app.tcpserver.FileTransferEngine
import freedom.app.viewModels.ContactViewModel
import freedom.app.viewModels.MessageViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessagesFragment : Fragment() {

    private lateinit var binding:          FragmentMessagesBinding
    private lateinit var messageViewModel: MessageViewModel
    private lateinit var contactViewModel: ContactViewModel
    private lateinit var blobAdapter:      ContactBlobAdapter
    private lateinit var bubbleAdapter:    MessageBubbleAdapter

    private var selectedContact: ContactData? = null

    // ── ActivityResult launchers ─────────────────────────────────────────────

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val contact = selectedContact ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = FileTransferEngine.sendFile(
                requireActivity().application,
                contact.id,
                uri
            )
            if (!ok) {
                Toast.makeText(requireContext(),
                    "Cannot send: not connected to ${contact.name}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            parseAndSaveContact(result.contents)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMessagesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        messageViewModel = ViewModelProvider(this)[MessageViewModel::class.java]
        contactViewModel = ViewModelProvider(this)[ContactViewModel::class.java]

        // ── Contact sidebar ───────────────────────────────────────────────────
        blobAdapter = ContactBlobAdapter(
            onSelect = { contact -> selectContact(contact) },
            onKeyRefreshNeeded = { contact ->
                // Key is RED — fire a key-refresh request if there is a live connection
                lifecycleScope.launch {
                    ContactConnectionManager.send(contact.id, "INFRA:KEY_REQ")
                }
            },
            onLongPress = { contact -> showShareContactDialog(contact) }
        )
        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter       = blobAdapter

        val addCircle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF757575.toInt())
        }
        binding.btnAddContact.background = addCircle
        binding.btnAddContact.setOnClickListener { launchQrScan() }

        contactViewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            blobAdapter.update(contacts ?: emptyList())
        }

        // Long-press on the selected contact name header also triggers share
        binding.tvSelectedContactName.setOnLongClickListener {
            val contact = selectedContact ?: return@setOnLongClickListener false
            showShareContactDialog(contact)
            true
        }

        // ── Message RecyclerView ──────────────────────────────────────────────
        bubbleAdapter = MessageBubbleAdapter()
        val llm = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.rvMessages.layoutManager = llm
        binding.rvMessages.adapter       = bubbleAdapter

        // ── Attach file ───────────────────────────────────────────────────────
        binding.btnAttachFile.setOnClickListener {
            fileLauncher.launch("*/*")
        }

        // ── Send ──────────────────────────────────────────────────────────────
        binding.btnClientSend.setOnClickListener {
            val contact = selectedContact ?: return@setOnClickListener
            val text    = binding.etClientMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            sendMessage(contact, text)
        }

        // ── Contact sharing: listen for incoming share requests ──────────────
        ContactShareEngine.onShareRequestReceived = { request ->
            requireActivity().runOnUiThread {
                showIncomingShareRequestDialog(request)
            }
        }
        ContactShareEngine.onShareResult = { _, success, message ->
            requireActivity().runOnUiThread {
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Contact selection ─────────────────────────────────────────────────────

    private fun selectContact(contact: ContactData) {
        selectedContact = contact
        binding.tvSelectedContactName.text = contact.name
        binding.etClientMessage.isEnabled  = true
        binding.btnClientSend.isEnabled    = true
        binding.btnAttachFile.isEnabled    = true

        // Swap the observed LiveData to this contact's message thread
        messageViewModel.getForContact(contact.id).observe(viewLifecycleOwner) { messages ->
            bubbleAdapter.submitList(messages ?: emptyList())
            if (!messages.isNullOrEmpty()) {
                binding.rvMessages.scrollToPosition(messages.size - 1)
            }
        }
    }

    // ── Sending ───────────────────────────────────────────────────────────────

    private fun sendMessage(contact: ContactData, text: String) {
        binding.btnClientSend.isEnabled = false
        binding.etClientMessage.text?.clear()

        val ok = ContactConnectionManager.send(contact.id, text)

        if (ok) {
            // Persist as SENT in the message DB
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            messageViewModel.insertMessage(
                MessageData(
                    timestamp   = timestamp,
                    messageType = "TEXT",
                    content     = text,
                    sender      = "me",
                    contactId   = contact.id,
                    direction   = MessageData.SENT
                )
            ) { }
        } else {
            // No live connection — show toast, re-enable input
            Toast.makeText(requireContext(), "Not connected to ${contact.name}", Toast.LENGTH_SHORT).show()
        }

        binding.btnClientSend.isEnabled = true
    }

    // ── Contact sharing ───────────────────────────────────────────────────────

    /**
     * Show context menu with "Share Contact" option for the long-pressed contact.
     */
    private fun showShareContactDialog(contactToShare: ContactData) {
        AlertDialog.Builder(requireContext())
            .setTitle(contactToShare.name)
            .setItems(arrayOf(getString(R.string.share_contact))) { _, _ ->
                showContactPickerForShare(contactToShare)
            }
            .show()
    }

    /**
     * Show a list of A's other contacts (excluding [contactToShare]) for the user to pick
     * who to introduce [contactToShare] to.
     */
    private fun showContactPickerForShare(contactToShare: ContactData) {
        val allContacts = contactViewModel.allContacts.value ?: emptyList()
        val otherContacts = allContacts.filter { it.id != contactToShare.id }

        if (otherContacts.isEmpty()) {
            Toast.makeText(requireContext(), "No other contacts to share with", Toast.LENGTH_SHORT).show()
            return
        }

        val names = otherContacts.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_contact_title, contactToShare.name))
            .setItems(names) { _, which ->
                val targetContact = otherContacts[which]
                showShareMessageDialog(contactToShare, targetContact)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show a dialog to optionally enter a message before sending the share request.
     */
    private fun showShareMessageDialog(contact1: ContactData, contact2: ContactData) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }

        val label = TextView(requireContext()).apply {
            text = "Introduce ${contact1.name} and ${contact2.name} to each other"
            textSize = 14f
            setPadding(0, 0, 0, pad)
        }
        container.addView(label)

        val etMessage = EditText(requireContext()).apply {
            hint = getString(R.string.share_contact_message_hint)
            isSingleLine = false
            maxLines = 3
        }
        container.addView(etMessage)

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_contact))
            .setView(container)
            .setPositiveButton(getString(R.string.share_contact_send)) { _, _ ->
                val message = etMessage.text.toString().trim()
                lifecycleScope.launch {
                    val (success, resultMsg) = ContactShareEngine.initiateShare(
                        requireActivity().application,
                        contact1.id,
                        contact2.id,
                        message
                    )
                    Toast.makeText(requireContext(), resultMsg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Show an incoming share request dialog to the user.
     */
    private fun showIncomingShareRequestDialog(request: ContactShareEngine.IncomingShareRequest) {
        if (!isAdded) return

        val allContacts = contactViewModel.allContacts.value ?: emptyList()
        val fromContact = allContacts.find { it.id == request.fromContactId }
        val fromName = fromContact?.name ?: "Unknown"

        val body = if (request.message.isNotEmpty()) {
            getString(R.string.share_request_body_with_msg, fromName, request.otherContactName, request.message)
        } else {
            getString(R.string.share_request_body, fromName, request.otherContactName)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.share_request_title))
            .setMessage(body)
            .setPositiveButton(getString(R.string.share_accept)) { _, _ ->
                val sent = ContactShareEngine.approveShare(request.shareId)
                if (!sent) {
                    Toast.makeText(requireContext(), "Failed to send approval — not connected", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.share_deny)) { _, _ ->
                ContactShareEngine.denyShare(request.shareId)
            }
            .setCancelable(false)
            .show()
    }

    // ── QR scanning ───────────────────────────────────────────────────────────

    private fun launchQrScan() {
        val options = ScanOptions().apply {
            setPrompt("")
            setBeepEnabled(true)
            setOrientationLocked(false)
        }
        scanLauncher.launch(options)
    }

    private fun parseAndSaveContact(json: String) {
        val map: Map<String, Any> = try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "QR parse error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (map["app"]?.toString() != "freedom") {
            Toast.makeText(requireContext(), "Not a Freedom QR code", Toast.LENGTH_LONG).show()
            return
        }

        val ddns = map["ddns"] as? String ?: ""
        val port = (map["port"] as? Number)?.toInt() ?: 0
        val rawKeyB64 = map["key"] as? String ?: ""

        if (ddns.isEmpty() || port == 0 || rawKeyB64.isEmpty()) {
            Toast.makeText(requireContext(), "QR missing required fields", Toast.LENGTH_LONG).show()
            return
        }

        val contact = ContactData(
            name = ddns,
            ddnsNames = ddns,
            ports = port.toString()
        )

        lifecycleScope.launch {
            try {
                contactViewModel.insertSuspend(contact)
                Toast.makeText(requireContext(), "Contact added — open Contacts tab to complete exchange", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "DB insert error", e)
                Toast.makeText(requireContext(), "DB error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear callbacks to avoid leaking the fragment
        ContactShareEngine.onShareRequestReceived = null
        ContactShareEngine.onShareResult = null
    }

    companion object {
        const val TAG = "MESSAGES_FRAGMENT"
    }
}
