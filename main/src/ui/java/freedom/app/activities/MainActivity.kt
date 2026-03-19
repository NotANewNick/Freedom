/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */
package freedom.app.activities

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.RemoteException
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import freedom.app.R
import freedom.app.VpnProfile
import freedom.app.api.IOpenVPNAPIService
import freedom.app.api.IOpenVPNStatusCallback
import freedom.app.core.ConfigParser
import freedom.app.core.ConfigParser.ConfigParseError
import freedom.app.core.ConnectionStatus
import freedom.app.core.IOpenVPNServiceInternal
import freedom.app.core.ProfileManager
import freedom.app.core.VPNLaunchHelper
import freedom.app.databinding.ActivityHomeBinding
import freedom.app.fragments.AboutFragment
import freedom.app.fragments.ContactsFragment
import freedom.app.fragments.ImportRemoteConfig.Companion.newInstance
import freedom.app.fragments.LogFragment
import freedom.app.fragments.MessagesFragment
import freedom.app.fragments.SendDumpFragment
import freedom.app.fragments.SettingsFragment
import freedom.app.fragments.TunnelsFragment
import freedom.app.fragments.VPNProfileList
import freedom.app.helper.LogUtility
import freedom.app.views.ScreenSlidePagerAdapter
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringReader

class MainActivity : BaseActivity() {
    private lateinit var mPager: ViewPager2
    private lateinit var mPagerAdapter: ScreenSlidePagerAdapter
    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = layoutInflater.inflate(R.layout.main_activity, null)

        mPager = view.findViewById(R.id.pager)
        val tabLayout: TabLayout = view.findViewById(R.id.tab_layout)

        mPagerAdapter = ScreenSlidePagerAdapter(supportFragmentManager, lifecycle, this)

        disableToolbarElevation()
        mPagerAdapter.addTab(R.string.vpn_list_title, MessagesFragment::class.java)
        mPagerAdapter.addTab(R.string.faq, SettingsFragment::class.java)
        mPagerAdapter.addTab(R.string.tunnels_tab, TunnelsFragment::class.java)
        if (SendDumpFragment.getLastestDump(this) != null) {
            mPagerAdapter.addTab(R.string.crashdump, SendDumpFragment::class.java)
        }
        if (isAndroidTV)
            mPagerAdapter.addTab(R.string.openvpn_log, LogFragment::class.java)
        mPager.adapter = mPagerAdapter

        TabLayoutMediator(tabLayout, mPager) { tab, position ->
            tab.text = mPagerAdapter.getPageTitle(position)
            tab.icon = when (position) {
                0 -> ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_tab_messages)
                1 -> ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_tab_settings)
                2 -> ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_tab_tunnels)
                else -> null
            }
        }.attach()

        setUpEdgeEdgeInsetsListener(view, R.id.root_linear_layout)
        setContentView(view)
    }

    override fun observeViewModel() {
    }

    override fun initViewBinding() {
        // layout inflated manually in onCreate
    }

    private fun disableToolbarElevation() {
        supportActionBar?.elevation = 0f
    }

    override fun onResume() {
        super.onResume()
        val intent = intent
        if (intent != null) {
            val action = intent.action
            if (Intent.ACTION_VIEW == action) {
                val uri = intent.data
                uri?.let { checkUriForProfileImport(it) }
            }
            setIntent(null)
        }
    }

    private fun checkUriForProfileImport(uri: android.net.Uri) {
        if ("openvpn" == uri.scheme && "import-profile" == uri.host) {
            var realUrl = uri.encodedPath + "?" + uri.encodedQuery
            if (!realUrl.startsWith("/https://")) {
                Toast.makeText(
                    this,
                    "Cannot use openvpn://import-profile/ URL that does not use https://",
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            realUrl = realUrl.substring(1)
            startOpenVPNUrlImport(realUrl)
        }
    }

    private fun startOpenVPNUrlImport(url: String) {
        val asImportFrag = newInstance(url)
        asImportFrag.show(supportFragmentManager, "dialog")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.show_log) {
            val showLog = Intent(this, LogWindow::class.java)
            startActivity(showLog)
        }
        return super.onOptionsItemSelected(item)
    }

    private val MSG_UPDATE_STATE = 0
    private val START_PROFILE_EMBEDDED = 2
    private val ICS_OPENVPN_PERMISSION = 7
    private val REQUEST_CODE_NOTIFICATION_PERMISSION = 101
    private val REQUEST_CODE_FGS_PERMISSION = 1001
    protected var mService: IOpenVPNAPIService? = null
    protected var mServiceInternal: IOpenVPNServiceInternal? = null
    private var mHandler: Handler? = null

    private fun startEmbeddedProfile(addNew: Boolean, editable: Boolean, startAfterAdd: Boolean) {
        try {
            val conf = FileInputStream(File(filesDir, "client.ovpn"))
            val br = BufferedReader(InputStreamReader(conf))
            val config = StringBuilder()
            var line: String?
            while (true) {
                line = br.readLine()
                if (line == null) break
                config.append(line).append("\n")
            }
            br.close()
            conf.close()
            if (addNew) {
                val name = if (editable) "Profile from remote App" else "Non editable profile"
                val profile = mService!!.addNewVPNProfile(name, editable, config.toString())
                mService?.startProfile(profile.mUUID)
            } else {
                val cp = ConfigParser()
                cp.parseConfig(StringReader(config.toString()))
                mSelectedProfile = cp.convertProfile()
                ProfileManager.setTemporaryProfile(this, mSelectedProfile)
                val startReason = "external OpenVPN service by uid: " + Binder.getCallingUid()
                VPNLaunchHelper.startOpenVpn(mSelectedProfile, this, startReason, true)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: RemoteException) {
            e.printStackTrace()
        } catch (e: ConfigParseError) {
            throw RuntimeException(e)
        }
    }

    private val mCallback: IOpenVPNStatusCallback = object : IOpenVPNStatusCallback.Stub() {
        @Throws(RemoteException::class)
        override fun newStatus(uuid: String, state: String, message: String, level: String) {
            val msg = Message.obtain(mHandler, MSG_UPDATE_STATE, "$state|$message")
            msg.sendToTarget()

            if (level == ConnectionStatus.LEVEL_CONNECTED.name) {
                // VPN connected
            }
        }
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mService = IOpenVPNAPIService.Stub.asInterface(service)
            try {
                val i = (mService as IOpenVPNAPIService).prepare(packageName)
                if (i != null) {
                    startActivityForResult(i, ICS_OPENVPN_PERMISSION)
                } else {
                    onActivityResult(ICS_OPENVPN_PERMISSION, RESULT_OK, null)
                }
            } catch (e: RemoteException) {
                e.printStackTrace()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mService = null
            mServiceInternal = null
        }
    }

    private fun bindService() {
        val icsopenvpnService = Intent(IOpenVPNAPIService::class.java.name)
        icsopenvpnService.setPackage("freedom.app")
        bindService(icsopenvpnService, mConnection, BIND_AUTO_CREATE)
    }

    private var mSelectedProfile: VpnProfile? = null

    override fun onStart() {
        super.onStart()
        mHandler = Handler(Looper.getMainLooper())
        if (mService == null) bindService()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            if (requestCode == START_PROFILE_EMBEDDED)
                startEmbeddedProfile(false, false, false)
            if (requestCode == ICS_OPENVPN_PERMISSION) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                REQUEST_CODE_NOTIFICATION_PERMISSION
                            )
                        } else {
                            prepareStartProfile(START_PROFILE_EMBEDDED)
                        }
                    } else {
                        prepareStartProfile(START_PROFILE_EMBEDDED)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                try {
                    mService!!.registerStatusCallback(mCallback)
                } catch (e: RemoteException) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_NOTIFICATION_PERMISSION) {
            prepareStartProfile(START_PROFILE_EMBEDDED)
        }
    }

    @Throws(RemoteException::class)
    private fun prepareStartProfile(requestCode: Int) {
        val requestpermission = mService?.prepareVPNService()
        if (requestpermission == null) {
            onActivityResult(requestCode, RESULT_OK, null)
        } else {
            startActivityForResult(requestpermission, requestCode)
        }
    }
}
