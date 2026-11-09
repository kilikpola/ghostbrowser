package com.ghostbrowser.browser;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.widget.Toast;

public class SettingsActivity extends PreferenceActivity {

    @SuppressWarnings("deprecation")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.prefs);

        for (String key : new String[] {"search_engine", "accent", "text_zoom"}) {
            final ListPreference lp = (ListPreference) findPreference(key);
            lp.setSummary(escape(lp.getEntry()));
            lp.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override public boolean onPreferenceChange(Preference pref, Object value) {
                    int i = lp.findIndexOfValue(String.valueOf(value));
                    lp.setSummary(i >= 0 ? escape(lp.getEntries()[i]) : "");
                    return true;
                }
            });
        }

        findPreference("clear_now").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override public boolean onPreferenceClick(Preference pref) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle("Clear browsing data?")
                        .setMessage("Cookies, cache, site storage, saved form data and history will be deleted.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Store.clearBrowsingData(SettingsActivity.this, true);
                                Toast.makeText(SettingsActivity.this, "Browsing data cleared", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .show();
                return true;
            }
        });

        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pi.versionName;
        } catch (Exception ignored) {
        }
        findPreference("about").setSummary("Version " + version + " · Android " + Build.VERSION.RELEASE
                + "\nWorkspaces, split view, tracker blocking. Uses the system WebView engine.");
    }

    /** ListPreference.getSummary() runs the summary through String.format, so "100%" would crash it. */
    static String escape(CharSequence s) {
        return s == null ? "" : s.toString().replace("%", "%%");
    }

}
