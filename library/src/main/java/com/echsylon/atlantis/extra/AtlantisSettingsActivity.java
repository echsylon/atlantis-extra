package com.echsylon.atlantis.extra;

import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceActivity;

public class AtlantisSettingsActivity extends PreferenceActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(getApplicationContext(), AtlantisService.class);
        startService(intent);

        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new AtlantisSettingsFragment())
                .commit();
    }
}
