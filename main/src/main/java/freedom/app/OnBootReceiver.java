/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

import freedom.app.core.Preferences;
import freedom.app.core.ProfileManager;
import freedom.app.core.VPNLaunchHelper;
import freedom.app.tcpserver.TcpServerService;
import freedom.app.tcpserver.UdpServerService;


public class OnBootReceiver extends BroadcastReceiver {
	// Debug: am broadcast -a android.intent.action.BOOT_COMPLETED
	@Override
	public void onReceive(Context context, Intent intent) {

		final String action = intent.getAction();
		if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
			return;
		}

		// Always start TCP/UDP servers so we never miss incoming messages
		ContextCompat.startForegroundService(context, new Intent(context, TcpServerService.class));
		ContextCompat.startForegroundService(context, new Intent(context, UdpServerService.class));

		// Optionally also restart VPN if configured
		SharedPreferences prefs = Preferences.getDefaultSharedPreferences(context);
		boolean alwaysActive = prefs.getBoolean("restartvpnonboot", false);
		if (alwaysActive) {
			VpnProfile bootProfile = ProfileManager.getAlwaysOnVPN(context);
			if (bootProfile != null) {
				launchVPN(bootProfile, context);
			}
		}
	}

	void launchVPN(VpnProfile profile, Context context) {
		VPNLaunchHelper.startOpenVpn(profile, context.getApplicationContext(), "on Boot receiver", false);
	}
}
