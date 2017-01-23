package com.echsylon.atlantis.extra;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.echsylon.atlantis.Atlantis;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * This service ensures an isolated runtime for the {@link Atlantis} mock
 * infrastructure. The client app can either bind to the service or push
 * commands to it through {@link android.content.Context#startService(Intent)
 * Context#startService(Intent)}.
 * <p>
 * The {@code startService(Intent)} approach is, due to its nature, a silent
 * send-only API. The client can, nevertheless, start {@link AtlantisService}
 * like so:
 * <pre><code>
 *
 *     ComponentName component = new ComponentName(
 *             "{INSERT_YOUR_APP_PACKAGE_HERE}",
 *             "com.echsylon.atlantis.extra.AtlantisService");
 *
 *     Intent intent = new Intent("echsylon.atlantis.action.SET");
 *     intent.setComponent(component);
 *     intent.putExtra("echsylon.atlantis.extra.FEATURE", "ATLANTIS");
 *     intent.putExtra("echsylon.atlantis.extra.ENABLE", true);
 *     intent.putExtra("echsylon.atlantis.extra.DATA", "asset://config.json");
 *
 *     if (startService(intent) == null)
 *         Log.d("TAG", "Atlantis isn't available in this build config");
 *
 * </code></pre>
 * A similar example on how to enable/disable recording of missing request
 * templates could look something like:
 * <pre><code>
 *
 *     ComponentName component = new ComponentName(
 *             "{INSERT_YOUR_APP_PACKAGE_HERE}",
 *             "com.echsylon.atlantis.extra.AtlantisService");
 *
 *     Intent intent = new Intent("echsylon.atlantis.action.SET");
 *     intent.setComponent(component);
 *     intent.putExtra("echsylon.atlantis.extra.FEATURE", "RECORD");
 *     intent.putExtra("echsylon.atlantis.extra.ENABLE", true);
 *
 *     if (startService(intent) == null)
 *         Log.d("TAG", "Atlantis isn't available in this build config");
 *
 * </code></pre>
 * To have a more interactive connection to this service the client can bind to
 * it and get a reference to the service instance through the returned binder.
 * The instance then exposes a somewhat more nuanced API.
 * <pre><code>
 *
 *     private AtlantisService service;
 *
 *     private ServiceConnection connection = new ServiceConnection() {
 *        {@literal @Override}
 *         public void onServiceConnected(ComponentName c, IBinder binder) {
 *             AtlantisService.Binder bndr = (AtlantisService.Binder) binder;
 *             service = bndr.getService();
 *         }
 *
 *        {@literal @Override}
 *         public void onServiceDisconnected(ComponentName component) {
 *             service = null;
 *         }
 *     };
 *
 *    {@literal @Override}
 *     protected void onStart() {
 *         super.onStart();
 *         Intent intent = new Intent(this, AtlantisService.class);
 *         bindService(intent, connection, Context.BIND_AUTO_CREATE);
 *     }
 *
 *    {@literal @Override}
 *     protected void onStop() {
 *         super.onStop();
 *         unbindService(connection);
 *     }
 *
 *     public void onButtonPress(View view) {
 *         if (service.isAtlantisEnabled()) {
 *             service.setRecordMissingRequestsEnabled(true);
 *         }
 *     }
 *
 * </code></pre>
 * This approach requires a bit more code, but also offers more control. It also
 * adds a hard dependency between the {@link AtlantisService} service and your
 * app.
 * <p>
 * In order to get full access to the full feature set of {@code Atlantis} the
 * client can create a local instance of {@code Atlantis} and interact directly
 * with it. In this case it's the client app's undisputed responsibility to
 * maintain a suitable life cycle environment for {@code Atlantis}:
 * <pre><code>
 *
 *     private Atlantis atlantis;
 *
 *    {@literal @Override}
 *     protected void onCreate(@Nullable Bundle savedInstanceState) {
 *         super.onCreate(savedInstanceState);
 *         String json = getConfigurationJson(configuration);
 *         InputStream inputStream = new ByteArrayInputStream(json.getBytes());
 *         atlantis = new Atlantis(this, inputStream);
 *         atlantis.start();
 *     }
 *
 *    {@literal @Override}
 *     protected void onDestroy() {
 *         atlantis.stop();
 *         atlantis = null;
 *         super.onDestroy();
 *     }
 *
 *     public void onStartButtonClick(View view) {
 *         atlantis.setRecordServedRequestsEnabled(true);
 *     }
 *
 *     public void onStopButtonClick(View view) {
 *         atlantis.setRecordServedRequestsEnabled(true);
 *         List&lt;MockRequest&gt; requests = atlantis.servedRequests();
 *
 *         // Analyze the order or whatever else.
 *     }
 *
 * </code></pre>
 */
@SuppressWarnings({"WeakerAccess", "unused"})
public class AtlantisService extends Service {
    private static final String TAG = "ATLANTIS-EXTRA";
    private static final String ACTION_SET = "echsylon.atlantis.action.SET";
    private static final String EXTRA_FEATURE = "echsylon.atlantis.extra.FEATURE";
    private static final String EXTRA_STATE = "echsylon.atlantis.extra.ENABLE";
    private static final String EXTRA_DATA = "echsylon.atlantis.extra.DATA";

    private static final String FEATURE_ATLANTIS = "ATLANTIS";
    private static final String FEATURE_RECORD_MISSING_REQUESTS = "RECORD";
    private static final String FEATURE_RECORD_MISSING_FAILURES = "RECORD_FAILURES";

    private static final int NOTIFICATION_ID = 1;

    /**
     * This class enables means of binding to the {@link AtlantisService} and
     * calling the public API methods directly from another Android component.
     */
    public final class Binder extends android.os.Binder {

        /**
         * Exposes the public API of the {@code AtlantisService}
         * implementation.
         *
         * @return The service instance.
         */
        public AtlantisService getService() {
            return AtlantisService.this;
        }
    }


    private SharedPreferences sharedPreferences;
    private String configurationPreferenceKey;
    private String recordingPreferenceKey;
    private String recordingFailuresPreferenceKey;
    private String enabledPreferenceKey;
    private Atlantis atlantis;


    @Override
    public IBinder onBind(Intent intent) {
        return new AtlantisService.Binder();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setServiceForegroundEnabled(true);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        configurationPreferenceKey = getString(R.string.key_atlantis_configuration);
        recordingPreferenceKey = getString(R.string.key_atlantis_record);
        recordingFailuresPreferenceKey = getString(R.string.key_atlantis_record_failures);
        enabledPreferenceKey = getString(R.string.key_atlantis_enable);

        String configuration = sharedPreferences.getString(configurationPreferenceKey, null);
        boolean doEnable = sharedPreferences.getBoolean(recordingFailuresPreferenceKey, false);

        setAtlantisEnabled(doEnable && configuration != null, configuration);
        setRecordMissingRequestsEnabled(sharedPreferences.getBoolean(recordingPreferenceKey, false));
        setRecordMissingFailuresEnabled(sharedPreferences.getBoolean(recordingFailuresPreferenceKey, false));
    }

    @Override
    public void onDestroy() {
        if (atlantis != null) {
            atlantis.stop();
            atlantis = null;
        }

        setServiceForegroundEnabled(false);
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ACTION_SET.equals(intent.getAction())) {
            String feature = intent.getStringExtra(EXTRA_FEATURE);
            if (feature != null)
                switch (feature) {
                    case FEATURE_ATLANTIS: {
                        Bundle extras = intent.getExtras();
                        boolean enable = extras.getBoolean(EXTRA_STATE,
                                sharedPreferences.getBoolean(enabledPreferenceKey, false));
                        String configuration = extras.getString(EXTRA_DATA,
                                sharedPreferences.getString(configurationPreferenceKey, null));
                        setAtlantisEnabled(enable, configuration);
                        break;
                    }
                    case FEATURE_RECORD_MISSING_REQUESTS: {
                        Bundle extras = intent.getExtras();
                        boolean enable = extras.getBoolean(EXTRA_STATE,
                                sharedPreferences.getBoolean(recordingPreferenceKey, false));
                        setRecordMissingRequestsEnabled(enable);
                        break;
                    }
                    case FEATURE_RECORD_MISSING_FAILURES: {
                        Bundle extras = intent.getExtras();
                        boolean enable = extras.getBoolean(EXTRA_STATE,
                                sharedPreferences.getBoolean(recordingFailuresPreferenceKey, false));
                        setRecordMissingFailuresEnabled(enable);
                        break;
                    }
                    default:
                        // Nothing
                        break;
                }
        }

        return START_STICKY;
    }

    /**
     * Sets the enabled state of the Atlantis mock infrastructure. If already
     * running and the given enabled flag is "true", then Atlantis will be
     * stopped first and then re-started with the (possibly) new configuration.
     *
     * @param enable        The desired enabled state of Atlantis.
     * @param configuration The Atlantis configuration source description. If
     *                      the enabled flag is "false" then this parameter is
     *                      ignored and can safely be passed as null.
     */
    public void setAtlantisEnabled(final boolean enable, final String configuration) {
        if (atlantis != null) {
            atlantis.stop();
            atlantis = null;
        }

        if (enable) {
            InputStream inputStream = null;
            try {
                inputStream = getConfigurationInputStream(configuration);
                atlantis = new Atlantis(getApplicationContext(), inputStream);
                atlantis.start();
                updateConfigurationPreference(configuration);
                updateEnabledPreference(true);
            } catch (Exception e) {
                Log.i(TAG, "Couldn't enable Atlantis: ", e);
            } finally {
                closeSilently(inputStream);
            }
        } else {
            updateEnabledPreference(false);
        }
    }

    /**
     * Enables or disables recording of missing request templates. NOTE! The
     * {@code Atlantis} configuration must have a {@code fallbackBaseUrl} set
     * for this to have effect.
     *
     * @param enable The desired enabled state of the feature.
     */
    public void setRecordMissingRequestsEnabled(final boolean enable) {
        if (atlantis != null) {
            atlantis.setRecordMissingRequestsEnabled(enable);
            updateRecordingPreference(enable);
        }
    }

    /**
     * Enables or disables recording of missing request templates that return
     * with an HTTP error state from the real server. NOTE! The "record missing
     * requests" feature must be enabled for this preference to take effect.
     *
     * @param enable The desired enabled state of the feature.
     */
    public void setRecordMissingFailuresEnabled(final boolean enable) {
        if (atlantis != null) {
            atlantis.setRecordMissingFailuresEnabled(enable);
            updateRecordingPreference(enable);
        }
    }

    /**
     * Returns the enabled state of the {@code Atlantis} infrastructure.
     *
     * @return Boolean true if {@code Atlantis} is ready to intercept requests
     * and deliver mock responses for them. False otherwise.
     */
    public boolean isAtlantisEnabled() {
        return atlantis != null && atlantis.isRunning();
    }

    /**
     * Returns the enabled state for whether missing request templates are
     * recorded or not.
     *
     * @return Boolean true if missing requests are recorded, false otherwise.
     */
    public boolean isRecordMissingRequestsEnabled() {
        return atlantis != null && atlantis.isRecordingMissingRequests();
    }


    /**
     * Updates the {@code Atlantis} configuration preference.
     *
     * @param newConfiguration The new configuration description.
     */
    private void updateConfigurationPreference(final String newConfiguration) {
        sharedPreferences.edit()
                .putString(configurationPreferenceKey, newConfiguration)
                .apply();
    }

    /**
     * Updates the {@code Atlantis} enabled state preference.
     *
     * @param newEnabledState The new enabled state flag.
     */
    private void updateEnabledPreference(final boolean newEnabledState) {
        sharedPreferences.edit()
                .putBoolean(enabledPreferenceKey, newEnabledState)
                .apply();
    }

    /**
     * Updates the {@code Atlantis} recording state preference.
     *
     * @param newRecordingState The new recording state flag.
     */
    private void updateRecordingPreference(final boolean newRecordingState) {
        sharedPreferences.edit()
                .putBoolean(recordingPreferenceKey, newRecordingState)
                .apply();
    }

    /**
     * Updates the {@code Atlantis} recording failures state preference.
     *
     * @param newRecordingState The new recording state flag.
     */
    private void updateRecordingFailuresPreference(final boolean newRecordingState) {
        sharedPreferences.edit()
                .putBoolean(recordingFailuresPreferenceKey, newRecordingState)
                .apply();
    }

    /**
     * Sets the Atlantis service as a foreground or background service.
     *
     * @param isForegroundEnabled The foreground enabled state.
     */
    private void setServiceForegroundEnabled(boolean isForegroundEnabled) {
        if (isForegroundEnabled) {
            Notification notification = new Notification.Builder(this)
                    .setContentTitle(getText(R.string.atlantis))
                    .setContentText(getText(R.string.settings))
                    .setSmallIcon(android.R.drawable.sym_def_app_icon)
                    .setContentIntent(getForegroundNotificationPendingIntent())
                    .build();

            startForeground(NOTIFICATION_ID, notification);
        } else {
            stopForeground(true);
        }
    }

    /**
     * Tries to construct a pending intent that will launch the Atlantis
     * settings Activity.
     * <p>
     * First attempt will look for {@code "notification_activity_package"} and
     * {@code "notification_activity_class"} meta data values from the android
     * manifest and will default to the internal {@code AtlantisSettingsActivity}
     * if no such is found.
     *
     * @return A pending intent, never null.
     */
    private PendingIntent getForegroundNotificationPendingIntent() {
        Intent intent;

        try {
            ComponentName componentName = new ComponentName(this, getClass());
            PackageManager packageManager = getPackageManager();
            ServiceInfo serviceInfo = packageManager.getServiceInfo(componentName, PackageManager.GET_META_DATA);
            Bundle bundle = serviceInfo.metaData;
            
            if (bundle == null) {
                intent = new Intent(getApplicationContext(), AtlantisSettingsActivity.class);
            } else {
                String pkg = bundle.getString("notification_activity_package");
                String cls = bundle.getString("notification_activity_class");

                Log.i(TAG, "Custom activity intent: " + pkg + "/" + cls);

                if (pkg == null || cls == null || pkg.length() <= 0 || cls.length() <= 0) {
                    throw new Resources.NotFoundException();
                } else {
                    intent = new Intent()
                            .setClassName(pkg, cls)
                            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                }
            }
        } catch (Resources.NotFoundException | PackageManager.NameNotFoundException e) {
            Log.i(TAG, "Couldn't read custom meta data", e);
            intent = new Intent(getApplicationContext(), AtlantisSettingsActivity.class);
        }

        return PendingIntent.getActivity(this, 0, intent, 0);
    }

    /**
     * Parses a given {@code Atlantis} configuration description and returns the
     * corresponding JSON. If the description doesn't state a specific scheme;
     * {@code [asset|file|http|https]} then a guesswork is started where the
     * first non-exception attempt is assumed being a qualified guess.
     *
     * @param description The {@code Atlantis} configuration description.
     * @return The {@code Atlantis} configuration JSON.
     */
    private InputStream getConfigurationInputStream(final String description) {
        if (description == null)
            return null;

        // This is clearly an asset reference.
        if (description.startsWith("asset://"))
            try {
                String asset = description.substring(8);
                return getAssets().open(asset);
            } catch (Exception e) {
                Log.i(TAG, "Couldn't read configuration: " + description, e);
                return null;
            }

        // This is clearly a file reference.
        if (description.startsWith("file://"))
            try {
                String file = description.substring(7);
                return new FileInputStream(file);
            } catch (Exception e) {
                Log.i(TAG, "Couldn't read configuration: " + description, e);
                return null;
            }

        // This is clearly an online resource.
        if (description.matches("^(http|https)://.*$"))
            try {
                URL url = new URL(description);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                return connection.getInputStream();
            } catch (Exception e) {
                Log.i(TAG, "Couldn't read configuration: " + description, e);
                return null;
            }

        // No known scheme has been provided. Start guessing.
        Log.i(TAG, "No known configuration scheme provided. Start guessing [Asset|File]");

        // Try treating it as an asset.
        try {
            return getAssets().open(description);
        } catch (IOException e) {
            Log.i(TAG, "Not an asset: " + description, e);
            // No return here, we wan't to guess other options too...
        }

        // Nop, not an asset. Try opening as a file.
        try {
            return new FileInputStream(description);
        } catch (FileNotFoundException e) {
            Log.i(TAG, "Not a file: " + description, e);
            // No return here either...
        }

        // Nop. Not a file either. Assume clear text.
        return new ByteArrayInputStream(description.getBytes());
    }

    /**
     * Tries to gracefully close an input stream. Any exceptions during the
     * process will be consumed, but printed to the info log.
     *
     * @param inputStream The input stream to close.
     */
    private void closeSilently(final InputStream inputStream) {
        if (inputStream != null)
            try {
                inputStream.close();
            } catch (IOException e) {
                Log.i(TAG, "Couldn't close configuration input stream", e);
            }
    }
}
