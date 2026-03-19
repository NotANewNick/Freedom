package freedom.app.fragments

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import freedom.app.contacts.ContactAdapter
import freedom.app.data.entity.ContactData
import freedom.app.databinding.FragmentContactsBinding
import freedom.app.helper.FreedomCrypto
import freedom.app.security.PasskeySession
import freedom.app.tcpserver.BootstrapKeyHolder
import freedom.app.tcpserver.ConnectionEngine
import freedom.app.tcpserver.IMessageReceived
import freedom.app.viewModels.ContactViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ContactsFragment : Fragment() {

    private lateinit var binding: FragmentContactsBinding
    private lateinit var viewModel: ContactViewModel
    private lateinit var adapter: ContactAdapter

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        Log.d(TAG, "Scan result: contents=${result.contents}")
        if (result.contents == null) {
            Toast.makeText(requireContext(), "Scan cancelled or failed", Toast.LENGTH_SHORT).show()
        } else {
            parseAndSaveContact(result.contents)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentContactsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[ContactViewModel::class.java]

        adapter = ContactAdapter(
            mutableListOf(),
            onDelete  = { contact -> viewModel.delete(contact) },
            onConnect = { contact -> connectTo(contact) }
        )

        binding.rvContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvContacts.adapter = adapter

        viewModel.allContacts.observe(viewLifecycleOwner) { contacts ->
            Log.d(TAG, "allContacts updated: ${contacts?.size} items")
            adapter.update(contacts ?: emptyList())
        }

        binding.btnScanQr.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }
            scanLauncher.launch(options)
        }
    }

    /**
     * New QR JSON format (minimal):
     * {
     *   "app": "freedom",
     *   "ddns": "a.ddns.net",
     *   "port": 22176,
     *   "key": "<Base64 of ephemeral bootstrap key>"
     * }
     */
    private fun parseAndSaveContact(json: String) {
        Log.d(TAG, "Parsing QR content: $json")

        val map: Map<String, Any> = try {
            @Suppress("UNCHECKED_CAST")
            Gson().fromJson(json, Map::class.java) as Map<String, Any>
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            Toast.makeText(requireContext(), "QR parse error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        if (map["app"]?.toString() != "freedom") {
            Toast.makeText(requireContext(), "Wrong QR type (app='${map["app"]}')", Toast.LENGTH_LONG).show()
            return
        }

        val ddns = map["ddns"] as? String ?: ""
        val port = (map["port"] as? Number)?.toInt() ?: 0
        val rawKeyB64 = map["key"] as? String ?: ""

        if (ddns.isEmpty() || port == 0 || rawKeyB64.isEmpty()) {
            Toast.makeText(requireContext(), "QR missing required fields", Toast.LENGTH_LONG).show()
            return
        }

        val bootstrapKey = try { Base64.decode(rawKeyB64, Base64.NO_WRAP) } catch (e: Exception) {
            Toast.makeText(requireContext(), "Invalid key in QR", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(requireContext(), "Connecting to $ddns:$port...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            // Generate B's 24KB send key
            val myKey = FreedomCrypto.generateMessageKey()
            val myKeyB64 = Base64.encodeToString(myKey, Base64.NO_WRAP)
            val encSendKey = PasskeySession.encryptField(myKeyB64) ?: myKeyB64

            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val myName  = prefs.getString("my_name", "") ?: ""
            val myDdns  = prefs.getString("my_domains", "") ?: ""
            val myPorts = prefs.getString("my_ports", "") ?: ""

            val myInfo = mapOf(
                "name"  to myName,
                "ddns"  to myDdns,
                "ports" to myPorts
            )

            val msgInterface = object : IMessageReceived {
                override fun messageReceived(message: ByteArray, count: Int?) {}
                override fun messageReceivedInString(message: String) {}
            }

            // Create a temporary contact to save
            val tempContact = ContactData(
                name = ddns,
                ddnsNames = ddns,
                ports = port.toString(),
                sendKey0 = encSendKey,
                sendKeyCreatedAt0 = System.currentTimeMillis(),
                activeSendKeyIdx = 0
            )

            // Save contact first (so we have an ID)
            try {
                viewModel.insertSuspend(tempContact)
            } catch (e: Exception) {
                Log.e(TAG, "DB insert error", e)
                Toast.makeText(requireContext(), "DB error: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }

            // Step 4: B sends key+info to A's server
            val engine = ConnectionEngine(requireActivity().application, tempContact, msgInterface)
            val ok = withContext(Dispatchers.IO) {
                engine.bootstrapSendKey(ddns, port, bootstrapKey, myKey, myInfo)
            }

            if (ok) {
                Toast.makeText(requireContext(), "Key sent! Waiting for response...", Toast.LENGTH_SHORT).show()

                // Set up BootstrapKeyHolder for A's reverse connection (step 6-7)
                BootstrapKeyHolder.pendingReverseContact = tempContact
                BootstrapKeyHolder.scannedBootstrapKey = bootstrapKey
                BootstrapKeyHolder.onHandshakeComplete = { updatedContact ->
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(requireContext(), "Contact '${updatedContact.name}' exchange complete!", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(requireContext(), "Failed to connect to $ddns:$port", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun connectTo(contact: ContactData) {
        val app = requireActivity().application

        val msgInterface = object : IMessageReceived {
            override fun messageReceived(message: ByteArray, count: Int?) {}
            override fun messageReceivedInString(message: String) {
                Log.d(TAG, "Incoming from ${contact.name}: $message")
            }
        }

        lifecycleScope.launch {
            ConnectionEngine(app, contact, msgInterface).connect { _, msg ->
                if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val TAG = "CONTACTS_FRAGMENT"
    }
}
