package com.echsylon.atlantis.extra;

import android.os.Bundle;
import android.preference.PreferenceActivity;

public class AtlantisSettingsActivity extends PreferenceActivity {

    @Override
    @SuppressWarnings("ConstantConditions") // ignore Lint getSupportActionBar()
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new AtlantisSettingsFragment())
                .commit();
    }
}
