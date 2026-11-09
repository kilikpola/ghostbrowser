package com.ghostbrowser.browser;

import android.graphics.Bitmap;
import android.webkit.WebView;

import java.util.concurrent.atomic.AtomicInteger;

/** One browsing tab. The WebView is created lazily so restored sessions stay light. */
final class Tab {
    String url;
    String title;
    boolean incognito;
    boolean desktop;
    WebView web;
    Bitmap icon;
    int progress = 100;
    Tab parent;
    boolean compatOff, darkOff;
    int compatStage;
    volatile String ua;
    volatile String host;
    final AtomicInteger blocked = new AtomicInteger();

    Tab(String url, boolean incognito) {
        this.url = url;
        this.incognito = incognito;
        this.host = TrackerBlocker.host(url);
    }
}
