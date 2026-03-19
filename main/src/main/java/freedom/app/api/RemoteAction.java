/*
 * Copyright (c) 2012-2017 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.api;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import freedom.app.LaunchVPN;
import freedom.app.VpnProfile;
import freedom.app.core.IOpenVPNServiceInternal;
import freedom.app.core.OpenVPNService;
import freedom.app.core.ProfileManager;

public class RemoteAction extends Activity {

    public static final String EXTRA_NAME = "freedom.app.api.profileName";
    private boolean mDoDisconnect;
    private IOpenVPNServiceInternal mService;
    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {

            mService = IOpenVPNServiceInternal.Stub.asInterface(service);
            try {
                performAction();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            //mService = null;
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        Intent intent = new Intent(this, OpenVPNService.class);
        intent.setAction(OpenVPNService.START_SERVICE);
        getApplicationContext().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

    }

    private void performAction() throws RemoteException {

        if (!mService.isAllowedExternalApp(getCallingPackage())) {
            finish();
            return;
        }

        Intent intent = getIntent();
        setIntent(null);
        ComponentName component = intent.getComponent();
        if (component == null)
            return;


        switch (component.getShortClassName()) {
            case ".api.DisconnectVPN":
                mService.stopVPN(false);
                break;
            case ".api.PauseVPN":
                mService.userPause(true);
                break;
            case ".api.ResumeVPN":
                mService.userPause(false);
                break;
            case ".api.ConnectVPN":
                String vpnName = intent.getStringExtra(EXTRA_NAME);
                VpnProfile profile = ProfileManager.getInstance(this).getProfileByName(vpnName);
                if (profile == null) {
                    Toast.makeText(this, String.format("Vpn profile %s from API call not found", vpnName), Toast.LENGTH_LONG).show();
                } else {
                    Intent startVPN = new Intent(this, LaunchVPN.class);
                    startVPN.putExtra(LaunchVPN.EXTRA_KEY, profile.getUUID().toString());
                    startVPN.putExtra(OpenVPNService.EXTRA_START_REASON, ".api.ConnectVPN call");
                    startVPN.setAction(Intent.ACTION_MAIN);
                    startActivity(startVPN);
                }
                break;
        }
        finish();
    }

    @Override
    public void finish() {
        if(mService!=null) {
            mService = null;
            getApplicationContext().unbindService(mConnection);
        }
        super.finish();
    }
}
