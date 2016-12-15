package com.echsylon.atlantis.extra;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

public class AtlantisSettingsFragment extends PreferenceFragment implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    private boolean isEnabled;
    private boolean isRecording;
    private String configuration;
    private ProgressDialog progress;
    private AtlantisService service;
    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName component, IBinder binder) {
            AtlantisService.Binder atlantisBinder = (AtlantisService.Binder) binder;
            service = atlantisBinder.getService();
            refreshServiceState();
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            service = null;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
        Context context = getActivity();
        Intent intent = new Intent(context, AtlantisService.class);
        context.startService(intent);
    }

    @Override
    public void onStart() {
        super.onStart();
        SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
        configuration = sharedPreferences.getString("key_atlantis_configuration", null);
        isEnabled = sharedPreferences.getBoolean("key_atlantis_enable", false);
        isRecording = sharedPreferences.getBoolean("key_atlantis_record", false);

        Context context = getActivity();
        Intent intent = new Intent(context, AtlantisService.class);
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        getPreferenceManager()
                .getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);

        Context context = getActivity();
        context.unbindService(connection);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key != null)
            switch (key) {
                case "key_atlantis_configuration":
                    configuration = sharedPreferences.getString(key, null);
                    refreshServiceState();
                    break;
                case "key_atlantis_enable":
                    isEnabled = sharedPreferences.getBoolean(key, false);
                    refreshServiceState();
                    break;
                case "key_atlantis_record":
                    isRecording = sharedPreferences.getBoolean(key, false);
                    refreshServiceState();
                    break;
                default:
                    break;
            }
    }

    private void refreshServiceState() {
        if (service != null)
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected void onPreExecute() {
                    Context context = getActivity();
                    if (progress != null && context != null)
                        progress = ProgressDialog.show(context, null, null, true);
                }

                @Override
                protected Void doInBackground(Void... params) {
                    if (service != null) {
                        service.setAtlantisEnabled(false, null);
                        service.setAtlantisEnabled(isEnabled, configuration);
                        service.setRecordMissingRequestsEnabled(isRecording);
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (progress != null)
                            progress.dismiss();
                        validateIntegrity();
                    }, 200);
                }
            }.execute();
    }

    private void validateIntegrity() {
        if (service != null)
            if (isEnabled && !service.isAtlantisEnabled()) {
                // Reset the setting.
                SwitchPreference enabled = (SwitchPreference) findPreference("key_atlantis_enable");
                enabled.setChecked(false);
                new AlertDialog.Builder(getActivity())
                        .setMessage(R.string.check_configuration)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            dialog.dismiss();
                        }).show();
            }
    }
}
