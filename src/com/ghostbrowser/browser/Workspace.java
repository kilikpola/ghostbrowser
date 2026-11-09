package com.ghostbrowser.browser;

import java.util.ArrayList;

/** A named, coloured group of tabs (Zen-style workspace) with an optional split partner. */
final class Workspace {
    String name;
    int color;
    final ArrayList<Tab> tabs = new ArrayList<Tab>();
    Tab primary;
    Tab secondary;

    Workspace(String name, int color) {
        this.name = name;
        this.color = color;
    }
}
