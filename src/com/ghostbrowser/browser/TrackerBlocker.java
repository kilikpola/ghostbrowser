package com.ghostbrowser.browser;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Locale;

/** Host-based blocklist matching a domain and all of its subdomains. */
final class TrackerBlocker {
    private static volatile HashSet<String> hosts = new HashSet<String>();

    private TrackerBlocker() {}

    static void load(final Context c) {
        new Thread(new Runnable() {
            @Override public void run() {
                HashSet<String> set = new HashSet<String>();
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(c.getAssets().open("blocklist.txt"), "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.length() == 0 || line.startsWith("#")) continue;
                        set.add(line.toLowerCase(Locale.US));
                    }
                } catch (IOException ignored) {
                } finally {
                    if (r != null) try { r.close(); } catch (IOException ignored) {}
                }
                hosts = set;
            }
        }, "blocklist").start();
    }

    static boolean isTracker(String host) {
        if (host == null) return false;
        HashSet<String> set = hosts;
        String h = host.toLowerCase(Locale.US);
        while (true) {
            if (set.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    /** Approximate registrable domain (last two labels); good enough to spot first-party requests. */
    static String site(String host) {
        if (host == null) return "";
        String h = host.toLowerCase(Locale.US);
        int last = h.lastIndexOf('.');
        if (last <= 0) return h;
        int prev = h.lastIndexOf('.', last - 1);
        return prev < 0 ? h : h.substring(prev + 1);
    }

    static String host(String url) {
        if (url == null) return null;
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
