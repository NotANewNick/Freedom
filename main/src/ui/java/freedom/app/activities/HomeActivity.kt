/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.activities

import android.os.Bundle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import freedom.app.R
import freedom.app.databinding.ActivityHomeBinding
import freedom.app.fragments.AboutFragment
import freedom.app.fragments.LogFragment
import freedom.app.fragments.MessagesFragment
import freedom.app.fragments.SendDumpFragment
import freedom.app.fragments.SettingsFragment
import freedom.app.fragments.VPNProfileList
import freedom.app.views.ScreenSlidePagerAdapter

class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var mPager: ViewPager2
    private lateinit var mPagerAdapter: ScreenSlidePagerAdapter

    override fun observeViewModel() {
        // no-op — individual fragments observe their own ViewModels
    }

    override fun initViewBinding() {
        binding = ActivityHomeBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        mPager = view.findViewById(R.id.pager)
        val tabLayout: TabLayout = view.findViewById(R.id.tab_layout)

        mPagerAdapter = ScreenSlidePagerAdapter(supportFragmentManager, lifecycle, this)

        disableToolbarElevation()

        mPagerAdapter.addTab(R.string.vpn_list_title, VPNProfileList::class.java)
        mPagerAdapter.addTab(R.string.messages, MessagesFragment::class.java)
        mPagerAdapter.addTab(R.string.faq, SettingsFragment::class.java)

        if (SendDumpFragment.getLastestDump(this) != null) {
            mPagerAdapter.addTab(R.string.crashdump, SendDumpFragment::class.java)
        }
        if (isAndroidTV) {
            mPagerAdapter.addTab(R.string.openvpn_log, LogFragment::class.java)
        }
        mPagerAdapter.addTab(R.string.about, AboutFragment::class.java)

        mPager.adapter = mPagerAdapter

        TabLayoutMediator(tabLayout, mPager) { tab, position ->
            tab.text = mPagerAdapter.getPageTitle(position)
        }.attach()

        setUpEdgeEdgeInsetsListener(view, R.id.root_linear_layout)
    }

    private fun disableToolbarElevation() {
        supportActionBar?.elevation = 0f
    }
}
