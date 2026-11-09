package com.ghostbrowser.browser;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.preference.PreferenceManager;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Settings, bookmarks, history and session persistence on top of SharedPreferences. */
final class Store {
    static final String DEFAULT_SEARCH = "https://duckduckgo.com/?q=%s";
    private static final int HISTORY_LIMIT = 300;

    final SharedPreferences p;

    Store(Context c) {
        p = PreferenceManager.getDefaultSharedPreferences(c);
        // settings left behind by the removed Ghost Relay feature
        if (p.contains("relay_url") || p.contains("relay_code") || p.contains("relay_sites")) {
            p.edit().remove("relay_url").remove("relay_code").remove("relay_sites").apply();
        }
    }

    String searchTemplate() {
        String s = p.getString("search_engine", DEFAULT_SEARCH);
        return s.contains("%s") ? s : DEFAULT_SEARCH;
    }

    int accent() {
        try {
            return Color.parseColor(p.getString("accent", "#5B5BD6"));
        } catch (IllegalArgumentException e) {
            return 0xFF5B5BD6;
        }
    }

    int textZoom() {
        try {
            return Integer.parseInt(p.getString("text_zoom", "100"));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    boolean flag(String key, boolean def) {
        return p.getBoolean(key, def);
    }

    // --- bookmarks & history: JSON arrays of {u, t, ts}, newest first ---

    JSONArray list(String key) {
        try {
            return new JSONArray(p.getString(key, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void putList(String key, JSONArray a) {
        p.edit().putString(key, a.toString()).apply();
    }

    private static JSONArray prepend(JSONArray old, String url, String title, int limit) {
        JSONArray out = new JSONArray();
        try {
            JSONObject o = new JSONObject();
            o.put("u", url);
            o.put("t", title == null ? "" : title);
            o.put("ts", System.currentTimeMillis());
            out.put(o);
            for (int i = 0; i < old.length() && out.length() < limit; i++) {
                JSONObject e = old.optJSONObject(i);
                if (e != null && !url.equals(e.optString("u"))) out.put(e);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static JSONArray without(JSONArray old, String url) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            JSONObject e = old.optJSONObject(i);
            if (e != null && !url.equals(e.optString("u"))) out.put(e);
        }
        return out;
    }

    void addHistory(String url, String title) {
        putList("history", prepend(list("history"), url, title, HISTORY_LIMIT));
    }

    void removeHistory(String url) {
        putList("history", without(list("history"), url));
    }

    boolean isBookmarked(String url) {
        if (url == null) return false;
        JSONArray a = list("bookmarks");
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && url.equals(e.optString("u"))) return true;
        }
        return false;
    }

    void addBookmark(String url, String title) {
        putList("bookmarks", prepend(list("bookmarks"), url, title, Integer.MAX_VALUE));
    }

    void removeBookmark(String url) {
        putList("bookmarks", without(list("bookmarks"), url));
    }

    /** Wipes cookies, cache, site storage, form data and (optionally) history. */
    @SuppressWarnings("deprecation")
    static void clearBrowsingData(Context c, boolean history) {
        WebView tmp = new WebView(c);
        tmp.clearCache(true);
        tmp.clearFormData();
        tmp.clearHistory();
        tmp.destroy();
        CookieManager cm = CookieManager.getInstance();
        if (Build.VERSION.SDK_INT >= 21) {
            cm.removeAllCookies(null);
            cm.flush();
        } else {
            cm.removeAllCookie();
        }
        WebStorage.getInstance().deleteAllData();
        WebViewDatabase db = WebViewDatabase.getInstance(c);
        db.clearFormData();
        db.clearHttpAuthUsernamePassword();
        if (history) {
            PreferenceManager.getDefaultSharedPreferences(c).edit().remove("history").apply();
        }
    }
}
