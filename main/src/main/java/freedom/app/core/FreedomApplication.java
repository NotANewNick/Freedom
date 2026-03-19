/*
 * Copyright (c) 2012-2016 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.core;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import android.os.StrictMode;
import android.os.strictmode.Violation;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.bugfender.sdk.Bugfender;

import java.util.concurrent.Executors;

import freedom.app.BuildConfig;
import freedom.app.R;
import freedom.app.api.AppRestrictions;
import freedom.app.core.UserStateHolder;

public class FreedomApplication extends Application {
    private StatusListener mStatus;

    private UserStateHolder userStateHolder;  // field (null initially)
    /**
     * Lazy getter for UserStateHolder.
     * Thread-safe double-checked locking pattern.
     */
    public UserStateHolder getUserStateHolder() {
        if (userStateHolder == null) {
            synchronized (this) {
                if (userStateHolder == null) {
                    userStateHolder = new UserStateHolder(this);
                }
            }
        }
        return userStateHolder;
    }

    @Override
    public void onCreate() {

        Bugfender.init(this, "38ygK8dmNmh5ngitKsF0qoCdW506ahcL", BuildConfig.DEBUG, true);
        Bugfender.enableCrashReporting();
        Bugfender.enableUIEventLogging(this);
        Bugfender.enableLogcatLogging();

        if (BuildConfig.BUILD_TYPE.equals("debug"))
            enableStrictModes();

        if("robolectric".equals(Build.FINGERPRINT))
            return;

        LocaleHelper.setDesiredLocale(this);
        super.onCreate();


        System.setProperty("org.apache.poi.javax.xml.stream.XMLInputFactory", "com.fasterxml.aalto.stax.InputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLOutputFactory", "com.fasterxml.aalto.stax.OutputFactoryImpl");
        System.setProperty("org.apache.poi.javax.xml.stream.XMLEventFactory", "com.fasterxml.aalto.stax.EventFactoryImpl");


        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannels();
        mStatus = new StatusListener();
        mStatus.init(getApplicationContext());

        AppRestrictions.getInstance(this).checkRestrictions(this);
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.updateResources(base));
    }

    private void enableStrictModes() {
        StrictMode.ThreadPolicy.Builder tpbuilder = new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog();



        StrictMode.VmPolicy.Builder vpbuilder = new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            tpbuilder.penaltyListener(Executors.newSingleThreadExecutor(), this::logViolation);
            vpbuilder.penaltyListener(Executors.newSingleThreadExecutor(), this::logViolation);

        }
        //tpbuilder.penaltyDeath();
        //vpbuilder.penaltyDeath();

        StrictMode.VmPolicy policy = vpbuilder.build();
        StrictMode.setVmPolicy(policy);

    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        LocaleHelper.onConfigurationChange(this);
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    public void logViolation(Violation v) {
        String name = Application.getProcessName();
        System.err.println("------------------------- Violation detected in " + name + " ------" + v.getCause() + "---------------------------");
        VpnStatus.logException(VpnStatus.LogLevel.DEBUG, null, v);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void createNotificationChannels() {
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // Background message
        CharSequence name = getString(R.string.channel_name_background);
        NotificationChannel mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
                name, NotificationManager.IMPORTANCE_MIN);

        mChannel.setDescription(getString(R.string.channel_description_background));
        mChannel.enableLights(false);

        mChannel.setLightColor(Color.DKGRAY);
        mNotificationManager.createNotificationChannel(mChannel);

        // Connection status change messages

        name = getString(R.string.channel_name_status);
        mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
                name, NotificationManager.IMPORTANCE_LOW);

        mChannel.setDescription(getString(R.string.channel_description_status));
        mChannel.enableLights(true);

        mChannel.setLightColor(Color.BLUE);
        mNotificationManager.createNotificationChannel(mChannel);


        // Urgent requests, e.g. two factor auth
        name = getString(R.string.channel_name_userreq);
        mChannel = new NotificationChannel(OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
                name, NotificationManager.IMPORTANCE_HIGH);
        mChannel.setDescription(getString(R.string.channel_description_userreq));
        mChannel.enableVibration(true);
        mChannel.setLightColor(Color.CYAN);
        mNotificationManager.createNotificationChannel(mChannel);
    }
}
