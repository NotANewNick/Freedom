package freedom.app.fragments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import freedom.app.R
import freedom.app.data.entity.TunnelProfile
import freedom.app.databinding.FragmentTunnelsBinding
import freedom.app.tunnels.PlayitRegistrationManager
import freedom.app.tunnels.TunnelAdapter
import freedom.app.tunnels.TunnelProfileViewModel
import freedom.app.tunnels.VpnAutoLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class TunnelsFragment : Fragment() {

    private lateinit var binding: FragmentTunnelsBinding
    private lateinit var viewModel: TunnelProfileViewModel
    private lateinit var adapter: TunnelAdapter

    private val ovpnFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        pendingOvpnUri = uri
        pendingOvpnDialog?.show()
    }

    private var pendingOvpnUri: Uri? = null
    private var pendingOvpnDialog: AlertDialog? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentTunnelsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this)[TunnelProfileViewModel::class.java]

        adapter = TunnelAdapter(
            mutableListOf(),
            onDelete   = { profile -> confirmDelete(profile) },
            onMoveUp   = { profile -> moveProfile(profile, -1) },
            onMoveDown = { profile -> moveProfile(profile, +1) }
        )

        binding.rvTunnelProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTunnelProfiles.adapter = adapter
        binding.rvTunnelProfiles.isNestedScrollingEnabled = false

        viewModel.allProfiles.observe(viewLifecycleOwner) { profiles ->
            adapter.update(profiles ?: emptyList())
            binding.tvTunnelCount.text = getString(R.string.tunnel_count, profiles?.size ?: 0, MAX_TUNNELS)
            binding.btnAddTunnel.isEnabled = (profiles?.size ?: 0) < MAX_TUNNELS
        }

        binding.btnAddTunnel.setOnClickListener { showAddTunnelPicker() }
    }

    // ── Add picker ──────────────────────────────────────────────────────────

    private fun showAddTunnelPicker() {
        val options = arrayOf(
            getString(R.string.tunnel_type_playit),
            getString(R.string.tunnel_type_ngrok),
            getString(R.string.tunnel_type_ovpn)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tunnel_add_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPlayitDialog()
                    1 -> showNgrokDialog()
                    2 -> showOvpnDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── playit.gg registration ───────────────────────────────────────────────

    private fun showPlayitDialog() {
        val mgr  = PlayitRegistrationManager()
        val code = mgr.generateCode()
        val url  = mgr.claimUrl(code)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val tvStatus = TextView(requireContext()).apply {
            text = getString(R.string.playit_claim_waiting)
            setPadding(pad * 2, pad, pad * 2, pad)
            textSize = 13f
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.tunnel_type_playit)
            .setView(tvStatus)
            .setPositiveButton(R.string.playit_open_browser) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setCancelable(false)
            .create()

        dialog.show()
        tvStatus.text = getString(R.string.playit_claim_visit, url)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { mgr.startSetup(code) }
                tvStatus.text = getString(R.string.playit_claim_visit, url)

                val secretKey = withContext(Dispatchers.IO) { mgr.awaitApproval(code) }
                tvStatus.text = getString(R.string.playit_creating_tunnel)

                val (tunnelId, host, port) = withContext(Dispatchers.IO) {
                    mgr.createAndWaitForTunnel(secretKey, "Freedom-${System.currentTimeMillis()}")
                }

                val priority = viewModel.count()
                val profile = TunnelProfile(
                    type        = TunnelProfile.TYPE_PLAYIT,
                    name        = "playit.gg — $host",
                    secretKey   = secretKey,
                    tunnelId    = tunnelId,
                    publicHost  = host,
                    publicPort  = port,
                    priority    = priority
                )
                viewModel.insertSuspend(profile)
                addPortToConfig(port)
                dialog.dismiss()
                Toast.makeText(requireContext(), getString(R.string.tunnel_added, "$host:$port"), Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                if (isAdded) {
                    tvStatus.text = getString(R.string.tunnel_error, e.message)
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                }
            }
        }
    }

    // ── ngrok (manual authtoken + address) ──────────────────────────────────

    private fun showNgrokDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        val etAuthtoken = EditText(requireContext()).apply {
            hint = getString(R.string.ngrok_authtoken_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val etHost = EditText(requireContext()).apply {
            hint = getString(R.string.tunnel_host_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val etPort = EditText(requireContext()).apply {
            hint = getString(R.string.tunnel_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        container.addView(etAuthtoken)
        container.addView(etHost)
        container.addView(etPort)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.tunnel_type_ngrok)
            .setView(container)
            .setPositiveButton(R.string.tunnel_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val authtoken = etAuthtoken.text.toString().trim()
            val host      = etHost.text.toString().trim()
            val port      = etPort.text.toString().trim().toIntOrNull()
            if (authtoken.isEmpty() || host.isEmpty() || port == null) {
                Toast.makeText(requireContext(), getString(R.string.tunnel_fill_all), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val priority = viewModel.count()
                val profile = TunnelProfile(
                    type       = TunnelProfile.TYPE_NGROK,
                    name       = "ngrok — $host",
                    secretKey  = authtoken,
                    publicHost = host,
                    publicPort = port,
                    priority   = priority
                )
                viewModel.insertSuspend(profile)
                addPortToConfig(port)
            }
            dialog.dismiss()
            Toast.makeText(requireContext(), getString(R.string.tunnel_added, "$host:$port"), Toast.LENGTH_SHORT).show()
        }
    }

    // ── Custom OVPN ──────────────────────────────────────────────────────────

    private fun showOvpnDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad * 2, pad, pad * 2, 0)
        }
        val tvFile = TextView(requireContext()).apply {
            text = getString(R.string.ovpn_no_file_selected)
            textSize = 12f
            setPadding(0, 0, 0, pad / 2)
        }
        val etHost = EditText(requireContext()).apply {
            hint = getString(R.string.tunnel_host_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val etPort = EditText(requireContext()).apply {
            hint = getString(R.string.tunnel_port_hint)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        container.addView(tvFile)
        container.addView(etHost)
        container.addView(etPort)

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.tunnel_type_ovpn)
            .setView(container)
            .setPositiveButton(R.string.tunnel_save, null)
            .setNeutralButton(R.string.ovpn_pick_file, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        pendingOvpnDialog = dialog
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            ovpnFilePicker.launch(arrayOf("*/*"))
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val uri  = pendingOvpnUri
            val host = etHost.text.toString().trim()
            val port = etPort.text.toString().trim().toIntOrNull()

            if (uri == null || host.isEmpty() || port == null) {
                Toast.makeText(requireContext(), getString(R.string.tunnel_fill_all), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Copy the OVPN file to app private storage
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(requireContext().filesDir, "tunnel_${System.currentTimeMillis()}.ovpn")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { input.copyTo(it) }
                    }
                    val priority = viewModel.count()
                    val profile = TunnelProfile(
                        type       = TunnelProfile.TYPE_OVPN,
                        name       = "Custom VPN — $host",
                        ovpnPath   = destFile.absolutePath,
                        publicHost = host,
                        publicPort = port,
                        priority   = priority
                    )
                    viewModel.insertSuspend(profile)
                    withContext(Dispatchers.Main) {
                        addPortToConfig(port)
                        VpnAutoLauncher.connect(requireContext(), profile)
                        dialog.dismiss()
                        pendingOvpnUri = null
                        Toast.makeText(requireContext(), getString(R.string.tunnel_added, "$host:$port"), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Update file label when picker returns
        viewModel.allProfiles.observe(viewLifecycleOwner) {
            pendingOvpnUri?.let { tvFile.text = it.lastPathSegment ?: it.toString() }
        }
    }

    // ── Priority reorder ─────────────────────────────────────────────────────

    private fun moveProfile(profile: TunnelProfile, direction: Int) {
        val current = adapter.itemCount.let { _ ->
            viewModel.allProfiles.value?.toMutableList() ?: return
        }
        val idx = current.indexOfFirst { it.id == profile.id }.takeIf { it >= 0 } ?: return
        val newIdx = (idx + direction).coerceIn(0, current.size - 1)
        if (newIdx == idx) return
        current.add(newIdx, current.removeAt(idx))
        viewModel.reorder(current.map { it.id })
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun confirmDelete(profile: TunnelProfile) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tunnel_delete_title)
            .setMessage(getString(R.string.tunnel_delete_msg, profile.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    // Delete playit.gg tunnel from the server too
                    if (profile.type == TunnelProfile.TYPE_PLAYIT &&
                        profile.secretKey.isNotEmpty() && profile.tunnelId.isNotEmpty()) {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                PlayitRegistrationManager().deleteTunnel(profile.secretKey, profile.tunnelId)
                            }
                        }
                    }
                    viewModel.delete(profile)
                    // Remove the port from config only if no other tunnel still uses it
                    val remaining = viewModel.getOrderedProfiles()
                    if (remaining.none { it.publicPort == profile.publicPort }) {
                        removePortFromConfig(profile.publicPort)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ── Port config sync ─────────────────────────────────────────────────────

    /**
     * Append [port] to the "my_ports" SharedPreferences key (used for QR generation)
     * if it is not already in the list.
     */
    private fun addPortToConfig(port: Int) {
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("my_ports", "") ?: ""
        val ports = current.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val portStr = port.toString()
        if (!ports.contains(portStr)) {
            ports.add(portStr)
            prefs.edit().putString("my_ports", ports.joinToString(",")).apply()
        }
    }

    /**
     * Remove [port] from the "my_ports" SharedPreferences key.
     * Called when a tunnel is deleted and no other tunnel shares the same port.
     */
    private fun removePortFromConfig(port: Int) {
        if (port <= 0) return
        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val current = prefs.getString("my_ports", "") ?: ""
        val ports = current.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != port.toString() }
        prefs.edit().putString("my_ports", ports.joinToString(",")).apply()
    }

    companion object {
        const val MAX_TUNNELS = 6
    }
}
