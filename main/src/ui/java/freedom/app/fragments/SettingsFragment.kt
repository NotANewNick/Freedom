/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.fragments

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import freedom.app.R
import freedom.app.databinding.FragmentSettingsBinding
import freedom.app.ddns.DdnsAdapter
import freedom.app.ddns.DdnsConfig
import freedom.app.ddns.DdnsConfigStorage
import freedom.app.ddns.DdnsRegistrar
import freedom.app.ddns.DdnsServiceType
import freedom.app.ddns.DdnsUpdater
import freedom.app.ddns.DdnsWorker
import freedom.app.ddns.publicHostname
import android.os.SystemClock
import android.util.Base64
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import freedom.app.data.entity.ContactData
import freedom.app.helper.FreedomCrypto
import freedom.app.security.PasskeySession
import freedom.app.tcpserver.BootstrapKeyHolder
import freedom.app.tcpserver.ConnectionEngine
import freedom.app.tcpserver.IMessageReceived
import freedom.app.tcpserver.TcpServerService
import freedom.app.tcpserver.UdpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SettingsFragment : Fragment() {

    private lateinit var binding: FragmentSettingsBinding
    private lateinit var ddnsConfigs: MutableList<DdnsConfig>
    private lateinit var ddnsAdapter: DdnsAdapter

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        copyOvpnFile(uri)
    }

    /** Called after the RECORD_AUDIO permission result (granted or denied). */
    private var pendingKeyGen: (() -> Unit)? = null
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingKeyGen?.invoke()
        pendingKeyGen = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSettingsBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnLoadVpnProfile.setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }

        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val currentPort = prefs.getInt("server_port", 22176)
        binding.etServerPort.setText(currentPort.toString())

        binding.btnApplyPort.setOnClickListener {
            val port = binding.etServerPort.text.toString().toIntOrNull()
            if (port == null || port < 1 || port > 65535) {
                Toast.makeText(requireContext(), "Enter a valid port (1–65535)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putInt("server_port", port).apply()
            requireContext().stopService(Intent(requireContext(), TcpServerService::class.java))
            requireContext().stopService(Intent(requireContext(), UdpServerService::class.java))
            requireContext().startService(Intent(requireContext(), TcpServerService::class.java))
            requireContext().startService(Intent(requireContext(), UdpServerService::class.java))
            Toast.makeText(requireContext(), "Port set to $port", Toast.LENGTH_SHORT).show()
            updateServerStatus()
        }

        binding.btnStartServer.setOnClickListener {
            requireContext().startService(Intent(requireContext(), TcpServerService::class.java))
            requireContext().startService(Intent(requireContext(), UdpServerService::class.java))
            Toast.makeText(requireContext(), "Server started on port ${prefs.getInt("server_port", 22176)}", Toast.LENGTH_SHORT).show()
            updateServerStatus()
        }

        binding.btnStopServer.setOnClickListener {
            requireContext().stopService(Intent(requireContext(), TcpServerService::class.java))
            requireContext().stopService(Intent(requireContext(), UdpServerService::class.java))
            Toast.makeText(requireContext(), "Server stopped", Toast.LENGTH_SHORT).show()
            updateServerStatus()
        }

        updateServerStatus()

        setupSearchable(prefs)
        setupShareRateLimit(prefs)
        setupMyQr(prefs)
        setupDdns()
    }

    private fun setupSearchable(prefs: android.content.SharedPreferences) {
        binding.switchSearchable.isChecked = prefs.getBoolean("my_searchable", false)
        binding.switchSearchable.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("my_searchable", isChecked).apply()
        }
    }

    private fun setupShareRateLimit(prefs: android.content.SharedPreferences) {
        val current = prefs.getInt("share_rate_limit_seconds", 15)
        binding.etShareRateLimit.setText(current.toString())
        binding.etShareRateLimit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = binding.etShareRateLimit.text.toString().toIntOrNull()
                if (value != null && value >= 0) {
                    prefs.edit().putInt("share_rate_limit_seconds", value).apply()
                } else {
                    binding.etShareRateLimit.setText(current.toString())
                    Toast.makeText(requireContext(), "Enter a valid number (0 or more)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupMyQr(prefs: android.content.SharedPreferences) {
        binding.etMyName.setText(prefs.getString("my_name", ""))

        // Auto-populate domains from active DDNS configs; fall back to saved value
        val ddnsConfigs = DdnsConfigStorage.load(requireContext())
        if (ddnsConfigs.isNotEmpty()) {
            binding.etMyDomains.setText(ddnsConfigs.joinToString(",") { it.publicHostname() })
        } else {
            binding.etMyDomains.setText(prefs.getString("my_domains", ""))
        }

        // Ports: restore saved value, defaulting to 3 ports based on server port
        val savedPorts = prefs.getString("my_ports", "")
        if (savedPorts.isNullOrEmpty()) {
            val base = prefs.getInt("server_port", 22176)
            binding.etMyPorts.setText("$base,${base + 1},${base + 2}")
        } else {
            binding.etMyPorts.setText(savedPorts)
        }

        // Restore locked key slot display from stored metadata (no decryption needed)
        if ((prefs.getString("my_key_full", "") ?: "").isNotEmpty()) {
            showLockedKeySlots(prefs)
        }

        binding.btnGenerateKey.setOnClickListener {
            binding.btnGenerateKey.isEnabled = false
            val startGen = {
                showMotionEntropyDialog { allEntropy ->
                    lifecycleScope.launch {
                        val masterKey = withContext(Dispatchers.IO) {
                            FreedomCrypto.generateKey(
                                requireContext(),
                                padLengthBytes = FreedomCrypto.MASTER_PAD_BYTES,
                                extraEntropy = allEntropy
                            )
                        }
                        val entropy  = FreedomCrypto.entropyBitsPerByte(masterKey)
                        val segments = FreedomCrypto.splitKey(masterKey)
                        prefs.edit()
                            .putString("my_key_fingerprint", FreedomCrypto.keyFingerprint(masterKey))
                            .putFloat("my_key_entropy", entropy.toFloat())
                            .also { ed ->
                                segments.forEachIndexed { i, seg ->
                                    ed.putString("my_key_seg_fp_$i", FreedomCrypto.keyFingerprint(seg).take(20))
                                }
                            }
                            .apply()

                        val encrypted = PasskeySession.encryptField(masterKey) ?: masterKey
                        prefs.edit().putString("my_key_full", encrypted).apply()
                        showLockedKeySlots(prefs)
                        binding.btnGenerateKey.isEnabled = true
                    }
                }
            }
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                startGen()
            } else {
                pendingKeyGen = startGen
                recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        binding.btnShowMyQr.setOnClickListener {
            val domains = binding.etMyDomains.text.toString().trim()
            val ports   = binding.etMyPorts.text.toString().trim()

            if (domains.isEmpty() || ports.isEmpty()) {
                Toast.makeText(requireContext(), "Please fill in domains and ports", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("my_domains", domains)
                .putString("my_ports",   ports)
                .apply()

            showQrWaitingDialog(prefs, domains, ports)
        }
    }

    /**
     * Generate a minimal QR code with an ephemeral bootstrap key and show
     * a "Waiting for connection..." dialog until the handshake completes.
     */
    private fun showQrWaitingDialog(
        prefs: android.content.SharedPreferences,
        domains: String,
        ports: String
    ) {
        // Generate ephemeral bootstrap key
        val bootstrapKey = FreedomCrypto.generateBootstrapKey()
        val bootstrapB64 = Base64.encodeToString(bootstrapKey, Base64.NO_WRAP)

        // Store in memory-only holder
        BootstrapKeyHolder.activeBootstrapKey = bootstrapKey

        // Primary DDNS and port for the QR
        val primaryDdns = domains.split(",").first().trim()
        val primaryPort = prefs.getInt("server_port", 22176)

        val content = Gson().toJson(mapOf(
            "app"  to "freedom",
            "ddns" to primaryDdns,
            "port" to primaryPort,
            "key"  to bootstrapB64
        ))

        // Build the dialog
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val imageView = try {
            val bitmap = BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, 600, 600)
            ImageView(requireContext()).apply {
                setImageBitmap(bitmap)
                setPadding(32, 32, 32, 32)
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to generate QR: ${e.message}", Toast.LENGTH_SHORT).show()
            BootstrapKeyHolder.clear()
            return
        }

        val statusText = TextView(requireContext()).apply {
            text = "Waiting for connection..."
            gravity = android.view.Gravity.CENTER
            textSize = 16f
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }

        container.addView(imageView)
        container.addView(statusText)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("My QR Code")
            .setView(container)
            .setNegativeButton("Cancel") { _, _ ->
                BootstrapKeyHolder.clear()
            }
            .setCancelable(false)
            .create()

        // Set up handshake completion callback
        BootstrapKeyHolder.onHandshakeComplete = { newContact ->
            // Step 6: A connects to B to deliver A's key
            lifecycleScope.launch {
                statusText.text = "Contact received! Delivering key..."

                val myKey = FreedomCrypto.generateMessageKey()
                val myKeyB64 = Base64.encodeToString(myKey, Base64.NO_WRAP)
                val encSendKey = PasskeySession.encryptField(myKeyB64) ?: myKeyB64

                // Get B's recv key (which is our recv key from their perspective, but stored as our recvKey)
                val theirKeyB64 = PasskeySession.decryptField(newContact.recvKey0) ?: ""
                val theirKey = if (theirKeyB64.isNotEmpty()) {
                    try { Base64.decode(theirKeyB64, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                } else ByteArray(0)

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

                val engine = ConnectionEngine(requireActivity().application, newContact, msgInterface)

                val ok = withContext(Dispatchers.IO) {
                    engine.bootstrapDeliverKey(newContact, myKey, theirKey, bootstrapKey, myInfo)
                }

                if (ok) {
                    // Save our send key for this contact
                    withContext(Dispatchers.IO) {
                        try {
                            val dao = freedom.app.data.room.FreedomDatabase
                                .getDataseClient(requireContext()).contactDao()
                            val fresh = dao.findByDdns(newContact.ddnsNames.split(",").first().trim())
                                ?: newContact
                            dao.insert(fresh.copy(
                                sendKey0 = encSendKey,
                                sendKeyCreatedAt0 = System.currentTimeMillis(),
                                activeSendKeyIdx = 0
                            ))
                        } catch (_: Exception) {}
                    }
                    statusText.text = "Key exchange complete!"
                    Toast.makeText(requireContext(), "Contact '${newContact.name}' added", Toast.LENGTH_SHORT).show()
                } else {
                    statusText.text = "Key delivery failed — contact saved without send key"
                    Toast.makeText(requireContext(), "Key delivery failed", Toast.LENGTH_SHORT).show()
                }

                BootstrapKeyHolder.clear()
                delay(1500)
                if (dialog.isShowing) dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * Populate [llKeySlots] using only cached metadata (fingerprints, entropy).
     */
    private fun showLockedKeySlots(prefs: android.content.SharedPreferences) {
        binding.llKeySlots.removeAllViews()

        val masterFp = prefs.getString("my_key_fingerprint", "") ?: ""
        if (masterFp.isEmpty()) return

        val entropy = prefs.getFloat("my_key_entropy", 0f).toDouble()
        val rating = when {
            entropy >= 7.99 -> "excellent"
            entropy >= 7.95 -> "good"
            entropy >= 7.80 -> "fair"
            else            -> "poor"
        }
        binding.tvKeyFingerprint.text = "$masterFp… (${FreedomCrypto.MASTER_PAD_BYTES / 1024} KB) \uD83D\uDD12"
        binding.tvEntropyScore.text = "Entropy: ${"%.4f".format(entropy)} / 8.0000 bits/byte — $rating"
        binding.tvEntropyScore.visibility = View.VISIBLE

        val dp = resources.displayMetrics.density
        repeat(FreedomCrypto.KEY_SEGMENTS) { idx ->
            val segFp = prefs.getString("my_key_seg_fp_$idx", "???") ?: "???"
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (4 * dp).toInt() }
            }
            val label = TextView(requireContext()).apply {
                text = "Key ${idx + 1}: $segFp… \uD83D\uDD12"
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFF444444.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val copyBtn = android.widget.Button(requireContext()).apply {
                text = getString(R.string.btn_copy_key_n, idx + 1)
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { copyKeySegment(idx, prefs) }
            }
            row.addView(label)
            row.addView(copyBtn)
            binding.llKeySlots.addView(row)
        }
    }

    /** Decrypt the master key with the session login key, copy segment [idx] to clipboard. */
    private fun copyKeySegment(idx: Int, prefs: android.content.SharedPreferences) {
        lifecycleScope.launch {
            val encrypted = prefs.getString("my_key_full", "") ?: ""
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val master = PasskeySession.decryptField(encrypted)
                        ?: error("Session locked — re-enter your passkey")
                    FreedomCrypto.splitKey(master)[idx]
                }
            }
            result.fold(
                onSuccess = { segKey ->
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("freedom_otp_key_${idx + 1}", segKey))
                    Toast.makeText(requireContext(), getString(R.string.key_copied), Toast.LENGTH_SHORT).show()
                },
                onFailure = {
                    Toast.makeText(requireContext(), it.message, Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /** Dialog with two password fields; calls [onConfirmed] only when both match. */
    private fun showSetPasscodeDialog(onConfirmed: (String) -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        val etPass = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.passcode_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val etConfirm = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.passcode_confirm_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(etPass)
        container.addView(etConfirm)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.passcode_set_title)
            .setView(container)
            .setPositiveButton(getString(R.string.passcode_set_btn), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val p1 = etPass.text.toString()
            val p2 = etConfirm.text.toString()
            when {
                p1.isEmpty() -> Toast.makeText(requireContext(), getString(R.string.passcode_empty), Toast.LENGTH_SHORT).show()
                p1 != p2     -> Toast.makeText(requireContext(), getString(R.string.passcode_mismatch), Toast.LENGTH_SHORT).show()
                else         -> { dialog.dismiss(); onConfirmed(p1) }
            }
        }
    }

    /** Single-field passcode prompt; calls [onEntered] with the typed passcode. */
    private fun showEnterPasscodeDialog(onEntered: (String) -> Unit) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val etPass = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.passcode_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(pad * 2, pad, pad * 2, pad)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.passcode_enter_title)
            .setView(etPass)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val p = etPass.text.toString()
                if (p.isNotEmpty()) onEntered(p)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setupDdns() {
        ddnsConfigs = DdnsConfigStorage.load(requireContext())
        ddnsAdapter = DdnsAdapter(
            ddnsConfigs,
            onEdit = { showDdnsDialog(it) },
            onDelete = { config ->
                ddnsConfigs.removeAll { it.id == config.id }
                DdnsConfigStorage.save(requireContext(), ddnsConfigs)
                ddnsAdapter.update(ddnsConfigs)
                if (ddnsConfigs.isEmpty()) DdnsWorker.cancel(requireContext())
            }
        )

        binding.rvDdnsConfigs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDdnsConfigs.adapter = ddnsAdapter
        binding.rvDdnsConfigs.isNestedScrollingEnabled = false

        if (ddnsConfigs.isNotEmpty()) DdnsWorker.schedule(requireContext())

        binding.btnAddDdnsService.setOnClickListener {
            showDdnsAddPicker()
        }

        binding.btnUpdateAllDdns.setOnClickListener {
            if (ddnsConfigs.isEmpty()) {
                Toast.makeText(requireContext(), "No DDNS services configured", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnUpdateAllDdns.isEnabled = false
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        val ip = DdnsUpdater.fetchPublicIp()
                        DdnsConfigStorage.saveLastIp(requireContext(), ip)
                        var ok = 0; var fail = 0
                        ddnsConfigs.forEach { config ->
                            runCatching { DdnsUpdater.update(config, ip) }
                                .onSuccess { ok++ }
                                .onFailure { fail++ }
                        }
                        "Updated $ok service(s)" + if (fail > 0) ", $fail failed" else ""
                    }
                }
                binding.btnUpdateAllDdns.isEnabled = true
                result.fold(
                    onSuccess = { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() },
                    onFailure = { Toast.makeText(requireContext(), "Error: ${it.message}", Toast.LENGTH_SHORT).show() }
                )
            }
        }
    }

    // ── DDNS registration flow ────────────────────────────────────────────────

    private fun showDdnsAddPicker() {
        AlertDialog.Builder(requireContext())
            .setTitle("Add DDNS Service")
            .setItems(arrayOf("Register / verify with provider", "Enter credentials manually")) { _, which ->
                if (which == 0) showDdnsProviderPicker() else showDdnsDialog(null)
            }
            .show()
    }

    private fun showDdnsProviderPicker() {
        val automated = listOf(
            DdnsServiceType.DESEC, DdnsServiceType.DUCKDNS, DdnsServiceType.DYNV6,
            DdnsServiceType.YDNS, DdnsServiceType.IPV64, DdnsServiceType.DYNU
        )
        val manual = listOf(DdnsServiceType.NOIP, DdnsServiceType.FREEDNS)
        val types = automated + manual
        val labels = automated.map { "⚡ ${it.displayName}" } + manual.map { it.displayName }
        AlertDialog.Builder(requireContext())
            .setTitle("Select Provider")
            .setItems(labels.toTypedArray()) { _, which ->
                showDdnsRegistrationDialog(types[which])
            }
            .show()
    }

    private fun generateSubdomain(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return "fr-" + (1..6).map { chars.random() }.joinToString("")
    }

    private data class RegSpec(
        val infoText: String,
        val cred1Label: String,
        val cred2Label: String?,
        val hostLabel: String?,
        val hostSuffix: String = ""
    )

    private fun getRegSpec(type: DdnsServiceType): RegSpec = when (type) {
        DdnsServiceType.DUCKDNS -> RegSpec(
            infoText = "Create a free account at duckdns.org and copy your token. A subdomain will be created automatically.",
            cred1Label = "Token", cred2Label = null, hostLabel = null
        )
        DdnsServiceType.DESEC -> RegSpec(
            infoText = "Enter your e-mail and a new password to create a free deSEC account (or log in if you already have one). A dedyn.io subdomain will be created automatically.",
            cred1Label = "E-mail", cred2Label = "Password", hostLabel = null
        )
        DdnsServiceType.DYNV6 -> RegSpec(
            infoText = "Create an account at dynv6.com to obtain your token. A subdomain will be created automatically.",
            cred1Label = "Token", cred2Label = null, hostLabel = null
        )
        DdnsServiceType.YDNS -> RegSpec(
            infoText = "Create an account at ydns.io. A subdomain will be created automatically.",
            cred1Label = "E-mail", cred2Label = "Password", hostLabel = null
        )
        DdnsServiceType.IPV64 -> RegSpec(
            infoText = "Create an account at ipv64.net and copy your API key from the dashboard. A subdomain will be created automatically.",
            cred1Label = "API Key", cred2Label = null, hostLabel = null
        )
        DdnsServiceType.FREEDNS -> RegSpec(
            infoText = "Create a subdomain at freedns.afraid.org. From the Dynamic DNS page, copy the update token from your update URL (the part after /u/ and before /?).",
            cred1Label = "Update Token", cred2Label = null,
            hostLabel = "Full hostname (e.g. myhost.mooo.com)", hostSuffix = ""
        )
        DdnsServiceType.NOIP -> RegSpec(
            infoText = "Create a hostname at noip.com first, then enter your credentials here to verify and save.",
            cred1Label = "Username", cred2Label = "Password",
            hostLabel = "Full hostname (e.g. myhost.ddns.net)", hostSuffix = ""
        )
        DdnsServiceType.DYNU -> RegSpec(
            infoText = "Enter your Dynu credentials. A hostname will be created automatically under freeddns.org.",
            cred1Label = "Username", cred2Label = "Password", hostLabel = null
        )
        else -> RegSpec(
            infoText = "Enter your credentials manually.",
            cred1Label = type.field1Label, cred2Label = type.field2Label,
            hostLabel = null
        )
    }

    private fun showDdnsRegistrationDialog(type: DdnsServiceType) {
        val spec = getRegSpec(type)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }

        container.addView(TextView(requireContext()).apply {
            text = spec.infoText
            setPadding(0, 0, 0, pad)
        })

        if (type.websiteUrl.isNotEmpty()) {
            container.addView(android.widget.Button(requireContext()).apply {
                text = "Open ${type.displayName} website"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(type.websiteUrl)))
                }
            })
        }

        val etCred1 = EditText(requireContext()).apply { hint = spec.cred1Label }
        container.addView(etCred1)

        val etCred2 = spec.cred2Label?.let { label ->
            EditText(requireContext()).apply {
                hint = label
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { container.addView(it) }
        }

        val etHostname = spec.hostLabel?.let { label ->
            EditText(requireContext()).apply {
                hint = if (spec.hostSuffix.isNotEmpty()) "$label${spec.hostSuffix}" else label
            }.also { container.addView(it) }
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Register with ${type.displayName}")
            .setView(container)
            .setPositiveButton("Register", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cred1 = etCred1.text.toString().trim()
            val cred2 = etCred2?.text?.toString()?.trim() ?: ""
            val hostname = etHostname?.text?.toString()?.trim() ?: ""

            if (cred1.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter ${spec.cred1Label}", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (spec.cred2Label != null && cred2.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter ${spec.cred2Label}", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (spec.hostLabel != null && hostname.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter the hostname", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            val resolvedHostname = hostname.ifEmpty { generateSubdomain() }
            lifecycleScope.launch {
                val result = DdnsRegistrar.register(type, cred1, cred2, resolvedHostname)
                if (isAdded) dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
                result.fold(
                    onSuccess = { config ->
                        ddnsConfigs.add(config)
                        DdnsConfigStorage.save(requireContext(), ddnsConfigs)
                        ddnsAdapter.update(ddnsConfigs)
                        DdnsWorker.schedule(requireContext())
                        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        val existing = prefs.getString("my_domains", "") ?: ""
                        val publicHost = config.publicHostname()
                        if (publicHost.isNotEmpty() && !existing.split(",").map { it.trim() }.contains(publicHost)) {
                            val updated = if (existing.isEmpty()) publicHost else "$existing,$publicHost"
                            prefs.edit().putString("my_domains", updated).apply()
                            binding.etMyDomains.setText(updated)
                        }
                        dialog.dismiss()
                        Toast.makeText(requireContext(),
                            "${type.displayName} registered successfully", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { err ->
                        Toast.makeText(requireContext(),
                            "Registration failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun showDdnsDialog(existing: DdnsConfig?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ddns_edit, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinnerService)
        val etField1 = dialogView.findViewById<EditText>(R.id.etField1)
        val etField2 = dialogView.findViewById<EditText>(R.id.etField2)
        val etField3 = dialogView.findViewById<EditText>(R.id.etField3)

        val services = DdnsServiceType.values()
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            services.map { it.displayName }
        )
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter

        fun applyServiceFields(type: DdnsServiceType) {
            etField1.hint = type.field1Label
            etField2.hint = type.field2Label
            etField3.hint = type.field3Label ?: ""
            etField3.visibility = if (type.field3Label != null) View.VISIBLE else View.GONE
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                applyServiceFields(services[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        if (existing != null) {
            val idx = services.indexOfFirst { it == existing.serviceType }
            if (idx >= 0) spinner.setSelection(idx)
            etField1.setText(existing.field1)
            etField2.setText(existing.field2)
            etField3.setText(existing.field3)
        } else {
            applyServiceFields(services[0])
        }

        val title = if (existing != null) "Edit DDNS Service" else "Add DDNS Service"
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val service = services[spinner.selectedItemPosition]
            val f1 = etField1.text.toString().trim()
            val f2 = etField2.text.toString().trim()
            val f3 = etField3.text.toString().trim()
            if (f1.isEmpty() || f2.isEmpty() || (service.field3Label != null && f3.isEmpty())) {
                Toast.makeText(requireContext(), "Please fill in all fields", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val newConfig = DdnsConfig(
                id = existing?.id ?: UUID.randomUUID().toString(),
                serviceType = service,
                field1 = f1,
                field2 = f2,
                field3 = f3
            )
            if (existing != null) {
                val idx = ddnsConfigs.indexOfFirst { it.id == existing.id }
                if (idx >= 0) ddnsConfigs[idx] = newConfig
            } else {
                ddnsConfigs.add(newConfig)
            }
            DdnsConfigStorage.save(requireContext(), ddnsConfigs)
            ddnsAdapter.update(ddnsConfigs)
            DdnsWorker.schedule(requireContext())
            dialog.dismiss()
        }
    }

    private fun showMotionEntropyDialog(onComplete: (ByteArray) -> Unit) {
        val progressBar = ProgressBar(
            requireContext(), null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            val margin = (16 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(margin * 2, 0, margin * 2, margin * 2) }
        }

        val promptText = TextView(requireContext()).apply {
            text = getString(R.string.entropy_motion_prompt)
            gravity = android.view.Gravity.CENTER
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad * 2, pad * 2, pad)
            textSize = 15f
        }

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(promptText)
            addView(progressBar)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.entropy_dialog_title)
            .setView(container)
            .setCancelable(false)
            .create()

        dialog.show()

        val touchEntropy = mutableListOf<Byte>()
        dialog.window?.decorView?.setOnTouchListener { _, event ->
            val x = event.rawX.toBits()
            val y = event.rawY.toBits()
            val p = event.pressure.toBits()
            val t = event.eventTime
            for (v in intArrayOf(x, y, p)) {
                touchEntropy.add((v         and 0xFF).toByte())
                touchEntropy.add(((v shr  8) and 0xFF).toByte())
                touchEntropy.add(((v shr 16) and 0xFF).toByte())
                touchEntropy.add(((v shr 24) and 0xFF).toByte())
            }
            for (s in 0..7) touchEntropy.add(((t shr (s * 8)) and 0xFF).toByte())
            false
        }

        lifecycleScope.launch {
            var motionBytes = byteArrayOf()

            val collectionJob = launch {
                motionBytes = FreedomCrypto.collectMotionEntropy(
                    requireContext(), MOTION_DURATION_MS
                )
            }

            val startTime = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startTime < MOTION_DURATION_MS) {
                val elapsed = SystemClock.elapsedRealtime() - startTime
                progressBar.progress = ((elapsed.toFloat() / MOTION_DURATION_MS) * 100).toInt()
                delay(50)
            }
            progressBar.progress = 100

            collectionJob.join()
            dialog.dismiss()
            onComplete(motionBytes + touchEntropy.toByteArray())
        }
    }

    private fun copyOvpnFile(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                File(requireContext().filesDir, "client.ovpn").outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(requireContext(), "VPN profile loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to load profile: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateServerStatus() {
        val running = isTcpServerRunning()
        val port = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getInt("server_port", 22176)
        binding.tvServerStatus.text = if (running) "Server: running on port $port" else "Server: stopped"
        binding.tvServerStatus.setTextColor(if (running) 0xFF4CAF50.toInt() else 0xFF888888.toInt())
        binding.btnStartServer.isEnabled = !running
        binding.btnStopServer.isEnabled  = running
    }

    private fun isTcpServerRunning(): Boolean {
        val manager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(100)) {
            if (TcpServerService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        const val TAG = "SETTINGS_FRAGMENT"
        private const val MOTION_DURATION_MS = 3000L
    }
}
