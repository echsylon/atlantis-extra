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
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;

public class AtlantisSettingsFragment extends PreferenceFragment {

    private String configurationPreferenceKey;
    private String recordingPreferenceKey;
    private String recordingFailuresPreferenceKey;
    private String enabledPreferenceKey;

    private boolean isEnabled;
    private boolean isRecording;
    private boolean isRecordingFailures;
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

        configurationPreferenceKey = getString(R.string.key_atlantis_configuration);
        recordingPreferenceKey = getString(R.string.key_atlantis_record);
        recordingFailuresPreferenceKey = getString(R.string.key_atlantis_record_failures);
        enabledPreferenceKey = getString(R.string.key_atlantis_enable);

        Context context = getActivity().getApplicationContext();
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        configuration = sharedPreferences.getString(configurationPreferenceKey, null);
        isEnabled = sharedPreferences.getBoolean(enabledPreferenceKey, false);
        isRecording = sharedPreferences.getBoolean(recordingPreferenceKey, false);
        isRecordingFailures = sharedPreferences.getBoolean(recordingFailuresPreferenceKey, false);
    }

    @Override
    public void onStart() {
        super.onStart();
        Context context = getActivity();
        Intent intent = new Intent(context, AtlantisService.class);
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);

        Preference configurationPreference = findPreference(configurationPreferenceKey);
        configurationPreference.setSummary(configuration);
        configurationPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            configuration = (String) newValue;
            preference.setSummary(configuration);
            refreshServiceState();
            return true;
        });

        Preference enablePreference = findPreference(enabledPreferenceKey);
        enablePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            isEnabled = (Boolean) newValue;
            refreshServiceState();
            return true;
        });

        Preference recordPreference = findPreference(recordingPreferenceKey);
        recordPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            isRecording = (Boolean) newValue;
            refreshServiceState();
            return true;
        });

        Preference recordFailuresPreference = findPreference(recordingFailuresPreferenceKey);
        recordFailuresPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            isRecordingFailures = (Boolean) newValue;
            refreshServiceState();
            return true;
        });
    }

    @Override
    public void onStop() {
        super.onStop();
        Context context = getActivity();
        context.unbindService(connection);
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
                        try {
                            service.setAtlantisEnabled(isEnabled, configuration);
                            service.setRecordMissingRequestsEnabled(isRecording);
                            service.setRecordMissingFailuresEnabled(isRecordingFailures);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
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
                // Reset the preference.
                SwitchPreference enabled = (SwitchPreference) findPreference(enabledPreferenceKey);
                enabled.setChecked(false);
                new AlertDialog.Builder(getActivity())
                        .setMessage(R.string.check_configuration)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> dialog.dismiss())
                        .show();
            }
    }
}
