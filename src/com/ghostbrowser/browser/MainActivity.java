package com.ghostbrowser.browser;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Patterns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.JsPromptResult;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    static final String START = "file:///android_asset/start.html";
    static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    static final int[] PALETTE = {0xFF5B5BD6, 0xFF0E9384, 0xFFE5484D, 0xFFD97706, 0xFF0B6BCB, 0xFF8E4EC6, 0xFF3F3F46};
    static final String[] PALETTE_NAMES = {"Indigo", "Teal", "Rose", "Amber", "Ocean", "Violet", "Graphite"};
    static final int PRIVATE_COLOR = 0xFF2D2440;
    static final int REQ_FILE = 1, REQ_STORAGE = 2;
    static final Pattern HOSTLIKE = Pattern.compile(
            "^(localhost|[\\w-]+(\\.[\\w-]+)+|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?([/?#].*)?$");

    // menu ids
    static final int M_BACK = 1, M_FORWARD = 2, M_RELOAD = 3, M_NEW = 4, M_PRIVATE = 5, M_SPLIT = 6,
            M_SWAP = 7, M_FIND = 8, M_DESKTOP = 9, M_BOOKMARK = 10, M_BOOKMARKS = 11, M_HISTORY = 12,
            M_SHARE = 13, M_COPY = 14, M_SETTINGS = 15, M_QUIT = 16, M_BLOCKED = 17, M_COMPAT = 18, M_DARK = 19;

    Store store;
    final ArrayList<Workspace> workspaces = new ArrayList<Workspace>();
    Workspace ws;
    Tab focused;

    LinearLayout root, toolbar, findBar, panes, sidebar, wsStrip, sidebarHeader;
    FrameLayout content, paneA, paneB;
    View divider, scrim, wsDot;
    Ui.Card cardA, cardB;
    ListView tabList;
    TextView tabCount, wsTitle, findCount, newTabBtn;
    EditText urlBar, findInput;
    Ui.Dots menuBtn;
    Ui.Progress progress;
    TabAdapter adapter;

    int accent;
    boolean toolbarBottom;
    // Dark-only palette, from Helium's neutral scale and Zen's tinted frame.
    // Contrast: FG 14.5:1 / SUB 6.6:1 / LINK 7.2:1 on FRAME; FG 12.2:1 / SUB 5.5:1 on SURFACE.
    static final int FRAME = 0xFF15171C, CARD_BG = 0xFF1A1C22, SURFACE = 0xFF23262E, RAISED = 0xFF2D3039,
            FG = 0xFFE6E7EB, SUB = 0xFF9A9CA8, ICON = 0xFFC4C6D1, LINK = 0xFF8E9EFF;
    int frame = FRAME;
    int sidebarBg, sidebarFg, sidebarSub;

    ValueCallback<Uri> legacyUpload;
    ValueCallback<Uri[]> upload;
    View customView;
    WebChromeClient.CustomViewCallback customCallback;
    String[] pendingDownload;
    final Handler handler = new Handler();
    final Runnable saveTask = new Runnable() {
        @Override public void run() { saveSession(); }
    };

    int dp(float v) { return Ui.dp(this, v); }

    // ------------------------------------------------------------------ lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        ModernJs.detectEngine(this);
        if (Build.VERSION.SDK_INT >= 19 && store.flag("web_debugging", false)) WebView.setWebContentsDebuggingEnabled(true);
        TrackerBlocker.load(getApplicationContext());
        buildUi();
        restoreSession();
        applyLook();
        showWorkspace();
        handleIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onPause() {
        super.onPause();
        saveSession();
        for (Tab t : allTabs()) if (t.web != null) t.web.onPause();
        if (focused != null && focused.web != null) focused.web.pauseTimers();
        if (Build.VERSION.SDK_INT >= 21) CookieManager.getInstance().flush();
    }

    @Override protected void onResume() {
        super.onResume();
        applyLook();
        for (Tab t : allTabs()) if (t.web != null) applySettings(t);
        if (focused != null && focused.web != null) focused.web.resumeTimers();
        resumeVisible();
        updateCookiePolicy();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(saveTask);
        if (isFinishing()) {
            for (Tab t : allTabs()) destroyWeb(t);
            if (store.flag("clear_on_exit", false)) Store.clearBrowsingData(this, true);
        }
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        updateSplitOrientation();
        sidebar.getLayoutParams().width = sidebarWidth();
        sidebar.requestLayout();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Zen-style tab unloading: free WebViews that aren't on screen; they reload when shown.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE || level == 10 /* RUNNING_LOW */) unloadHidden();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        unloadHidden();
    }

    @Override public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
        } else if (sidebar.getVisibility() == View.VISIBLE) {
            closeSidebar();
        } else if (findBar.getVisibility() == View.VISIBLE) {
            closeFind();
        } else if (focused != null && focused.web != null && focused.web.canGoBack()) {
            focused.appNav = true;
            focused.web.goBack();
        } else if (focused != null && focused.parent != null && ws.tabs.contains(focused.parent)) {
            Tab parent = focused.parent;
            closeTab(focused);
            selectTab(parent);
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) { // hardware menu key on 4.x devices
            showMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // ------------------------------------------------------------------ UI construction

    void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true); // keeps the URL bar from grabbing focus at launch

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(6), dp(6), dp(2), dp(6));

        tabCount = new TextView(this);
        tabCount.setGravity(Gravity.CENTER);
        tabCount.setTextSize(12);
        tabCount.setTypeface(Ui.medium());
        tabCount.setTextColor(ICON);
        Ui.bg(tabCount, Ui.outlined(ICON, dp(1.5f), dp(6)));
        tabCount.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSidebar(); }
        });
        tabCount.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { newTab(START, false, null, true); return true; }
        });
        FrameLayout countWrap = new FrameLayout(this);
        countWrap.addView(tabCount, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        countWrap.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSidebar(); }
        });
        toolbar.addView(countWrap, new LinearLayout.LayoutParams(dp(44), dp(44)));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setTextSize(15);
        urlBar.setTextColor(FG);
        urlBar.setHintTextColor(SUB);
        urlBar.setPadding(dp(14), 0, dp(14), 0);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent e) {
                boolean enter = e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && e.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    navigate(urlBar.getText().toString());
                    return true;
                }
                return false;
            }
        });
        urlBar.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                if (focused == null) return;
                if (has) {
                    urlBar.setText(isStart(focused.url) ? "" : currentUrl(focused));
                    urlBar.selectAll();
                } else {
                    urlBar.setText(styledUrl(focused));
                }
            }
        });
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        ulp.leftMargin = dp(4);
        toolbar.addView(urlBar, ulp);

        menuBtn = new Ui.Dots(this);
        menuBtn.setColor(ICON);
        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showMenu(); }
        });
        toolbar.addView(menuBtn, new LinearLayout.LayoutParams(dp(40), dp(44)));

        progress = new Ui.Progress(this);

        // find-in-page bar
        findBar = new LinearLayout(this);
        findBar.setOrientation(LinearLayout.HORIZONTAL);
        findBar.setGravity(Gravity.CENTER_VERTICAL);
        findBar.setPadding(dp(8), dp(2), dp(4), dp(6));
        findBar.setVisibility(View.GONE);
        findInput = new EditText(this);
        findInput.setSingleLine(true);
        findInput.setHint("Find in page");
        findInput.setTextSize(15);
        findInput.setTextColor(FG);
        findInput.setHintTextColor(SUB);
        findInput.setPadding(dp(14), dp(8), dp(14), dp(8));
        Ui.bg(findInput, Ui.rounded(SURFACE, dp(12)));
        findInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        findInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { doFind(s.toString()); }
        });
        findInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                if (focused.web != null) focused.web.findNext(true);
                return true;
            }
        });
        findBar.addView(findInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        findCount = new TextView(this);
        findCount.setTextColor(SUB);
        findCount.setPadding(dp(6), 0, dp(6), 0);
        findBar.addView(findCount);
        findBar.addView(textButton("Prev", new View.OnClickListener() {
            @Override public void onClick(View v) { if (focused.web != null) focused.web.findNext(false); }
        }));
        findBar.addView(textButton("Next", new View.OnClickListener() {
            @Override public void onClick(View v) { if (focused.web != null) focused.web.findNext(true); }
        }));
        findBar.addView(textButton("Done", new View.OnClickListener() {
            @Override public void onClick(View v) { closeFind(); }
        }));

        // content: split panes + sidebar overlay
        content = new FrameLayout(this);
        panes = new LinearLayout(this);
        panes.setPadding(dp(6), 0, dp(6), dp(6));
        cardA = new Ui.Card(this);
        cardB = new Ui.Card(this);
        paneA = cardA;
        paneB = cardB;
        paneA.setBackgroundColor(CARD_BG);
        paneB.setBackgroundColor(CARD_BG);
        divider = new View(this); // the gap between split cards
        panes.addView(paneA);
        panes.addView(divider);
        panes.addView(paneB);
        content.addView(panes, new FrameLayout.LayoutParams(-1, -1));

        scrim = new View(this);
        scrim.setBackgroundColor(0x8C000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { closeSidebar(); }
        });
        content.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        buildSidebar();
        content.addView(sidebar, new FrameLayout.LayoutParams(sidebarWidth(), -1, Gravity.LEFT));

        setContentView(root);
        arrangeChrome();
        updateSplitOrientation();
    }

    void buildSidebar() {
        sidebar = new LinearLayout(this);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        sidebar.setVisibility(View.GONE);
        sidebar.setClickable(true); // don't let taps fall through to the scrim
        if (Build.VERSION.SDK_INT >= 21) sidebar.setElevation(dp(16));

        sidebarHeader = new LinearLayout(this);
        sidebarHeader.setOrientation(LinearLayout.HORIZONTAL);
        sidebarHeader.setGravity(Gravity.CENTER_VERTICAL);
        sidebarHeader.setPadding(dp(18), dp(16), dp(12), dp(12));
        wsDot = new View(this);
        sidebarHeader.addView(wsDot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        wsTitle = new TextView(this);
        wsTitle.setTextSize(16);
        wsTitle.setTypeface(Ui.medium());
        wsTitle.setSingleLine(true);
        wsTitle.setEllipsize(TextUtils.TruncateAt.END);
        wsTitle.setPadding(dp(10), 0, dp(6), 0);
        wsTitle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { workspaceOptions(ws); }
        });
        sidebarHeader.addView(wsTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        newTabBtn = textButton("+  New tab", new View.OnClickListener() {
            @Override public void onClick(View v) { newTab(START, false, null, true); closeSidebar(); }
        });
        newTabBtn.setTextSize(13);
        newTabBtn.setTextColor(FG);
        newTabBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
        Ui.bg(newTabBtn, Ui.rounded(RAISED, dp(16)));
        sidebarHeader.addView(newTabBtn);
        sidebar.addView(sidebarHeader);

        tabList = new ListView(this);
        tabList.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        tabList.setDividerHeight(dp(2));
        tabList.setCacheColorHint(Color.TRANSPARENT);
        android.graphics.drawable.StateListDrawable press = new android.graphics.drawable.StateListDrawable();
        press.addState(new int[] {android.R.attr.state_pressed}, Ui.rounded(0x14FFFFFF, dp(10)));
        press.addState(new int[0], new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        tabList.setSelector(press);
        tabList.setDrawSelectorOnTop(true);
        tabList.setPadding(dp(8), 0, dp(8), 0);
        tabList.setClipToPadding(false);
        adapter = new TabAdapter();
        tabList.setAdapter(adapter);
        tabList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                selectTab(ws.tabs.get(pos));
                closeSidebar();
            }
        });
        tabList.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                tabOptions(ws.tabs.get(pos));
                return true;
            }
        });
        sidebar.addView(tabList, new LinearLayout.LayoutParams(-1, 0, 1f));

        TextView wsLabel = new TextView(this);
        wsLabel.setText("Workspaces");
        wsLabel.setTextSize(12);
        wsLabel.setTypeface(Ui.medium());
        wsLabel.setTextColor(SUB);
        wsLabel.setPadding(dp(18), dp(10), dp(18), dp(2));
        sidebar.addView(wsLabel);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        wsStrip = new LinearLayout(this);
        wsStrip.setOrientation(LinearLayout.HORIZONTAL);
        wsStrip.setPadding(dp(12), dp(6), dp(12), dp(16));
        hs.addView(wsStrip);
        sidebar.addView(hs);
    }

    TextView textButton(String label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTypeface(Ui.medium());
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        t.setTextColor(LINK);
        t.setOnClickListener(l);
        return t;
    }

    int sidebarWidth() {
        DisplayMetrics m = getResources().getDisplayMetrics();
        return Math.min(dp(320), (int) (m.widthPixels * 0.86f));
    }

    /** Places the toolbar at the top or bottom (Floorp/Vivaldi-style option). */
    void arrangeChrome() {
        root.removeAllViews();
        LinearLayout.LayoutParams tb = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams pr = new LinearLayout.LayoutParams(-1, dp(2));
        LinearLayout.LayoutParams fb = new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams ct = new LinearLayout.LayoutParams(-1, 0, 1f);
        panes.setPadding(dp(6), toolbarBottom ? dp(6) : 0, dp(6), toolbarBottom ? 0 : dp(6));
        if (toolbarBottom) {
            root.addView(findBar, fb);
            root.addView(content, ct);
            root.addView(progress, pr);
            root.addView(toolbar, tb);
        } else {
            root.addView(toolbar, tb);
            root.addView(progress, pr);
            root.addView(findBar, fb);
            root.addView(content, ct);
        }
    }

    void applyLook() {
        boolean bottom = store.flag("toolbar_bottom", false);
        if (bottom != toolbarBottom) {
            toolbarBottom = bottom;
            arrangeChrome();
        }
        accent = store.accent();
        sidebarFg = FG;
        sidebarSub = SUB;
        wsTitle.setTextColor(sidebarFg);
        progress.setColor(Ui.mix(accent, 0xFFFFFFFF, 0.75f));
        divider.setBackgroundColor(Color.TRANSPARENT);
        Ui.bg(urlBar, Ui.rounded(SURFACE, dp(12)));
        refreshChrome();
    }

    void tintChrome() {
        boolean priv = focused != null && focused.incognito;
        // Zen-style: the whole frame takes a faint tint of the workspace colour (purple when private).
        frame = priv ? Ui.mix(PRIVATE_COLOR, FRAME, 0.55f) : Ui.mix(ws.color, FRAME, 0.07f);
        sidebarBg = frame;
        root.setBackgroundColor(frame);
        toolbar.setBackgroundColor(frame);
        progress.setBackgroundColor(frame);
        findBar.setBackgroundColor(frame);
        content.setBackgroundColor(frame);
        sidebar.setBackgroundColor(frame);
        cardA.setFrameColor(frame);
        cardB.setFrameColor(frame);
        urlBar.setHint(priv ? "Private tab \u00b7 search or enter address" : "Search or enter address");
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(frame);
            getWindow().setNavigationBarColor(frame);
        }
    }

    // ------------------------------------------------------------------ sidebar

    void toggleSidebar() {
        if (sidebar.getVisibility() == View.VISIBLE) closeSidebar(); else openSidebar();
    }

    void openSidebar() {
        hideKeyboard();
        refreshChrome();
        sidebar.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);
        TranslateAnimation in = new TranslateAnimation(-sidebarWidth(), 0, 0, 0);
        in.setDuration(180);
        sidebar.startAnimation(in);
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(180);
        scrim.startAnimation(fade);
        int i = ws.tabs.indexOf(focused);
        if (i >= 0) tabList.setSelection(Math.max(0, i - 2));
    }

    void closeSidebar() {
        if (sidebar.getVisibility() != View.VISIBLE) return;
        TranslateAnimation out = new TranslateAnimation(0, -sidebarWidth(), 0, 0);
        out.setDuration(150);
        out.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) { sidebar.setVisibility(View.GONE); }
        });
        sidebar.startAnimation(out);
        AlphaAnimation fade = new AlphaAnimation(1f, 0f);
        fade.setDuration(150);
        scrim.startAnimation(fade);
        scrim.setVisibility(View.GONE);
    }

    final class TabAdapter extends BaseAdapter {
        @Override public int getCount() { return ws == null ? 0 : ws.tabs.size(); }
        @Override public Object getItem(int i) { return ws.tabs.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            Row r;
            if (convert == null) {
                r = new Row();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(10), dp(6), dp(4), dp(6));
                row.setMinimumHeight(dp(52));
                FrameLayout iconBox = new FrameLayout(MainActivity.this);
                r.iconBox = iconBox;
                r.letter = new TextView(MainActivity.this);
                r.letter.setGravity(Gravity.CENTER);
                r.letter.setTextColor(FG);
                r.letter.setTextSize(12);
                r.letter.setTypeface(Ui.medium());
                iconBox.addView(r.letter, new FrameLayout.LayoutParams(-1, -1));
                r.icon = new ImageView(MainActivity.this);
                r.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iconBox.addView(r.icon, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
                row.addView(iconBox, new LinearLayout.LayoutParams(dp(28), dp(28)));
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                texts.setPadding(dp(12), 0, dp(4), 0);
                r.title = new TextView(MainActivity.this);
                r.title.setSingleLine(true);
                r.title.setEllipsize(TextUtils.TruncateAt.END);
                r.title.setTextSize(14);
                r.sub = new TextView(MainActivity.this);
                r.sub.setSingleLine(true);
                r.sub.setEllipsize(TextUtils.TruncateAt.END);
                r.sub.setTextSize(12);
                texts.addView(r.title);
                texts.addView(r.sub);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                r.close = new TextView(MainActivity.this);
                r.close.setText("\u00d7");
                r.close.setTextSize(20);
                r.close.setGravity(Gravity.CENTER);
                r.close.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { closeTab((Tab) v.getTag()); }
                });
                row.addView(r.close, new LinearLayout.LayoutParams(dp(40), dp(44)));
                row.setTag(r);
                convert = row;
            } else {
                r = (Row) convert.getTag();
            }
            Tab t = ws.tabs.get(pos);
            String title = tabTitle(t);
            String prefix = "";
            if (t.incognito) prefix += "Private · ";
            if (t == ws.secondary || (ws.secondary != null && t == ws.primary)) prefix += "Split · ";
            r.title.setText(title);
            r.sub.setText(prefix + (isStart(t.url) ? "New tab" : hostOf(t)));
            r.title.setTextColor(sidebarFg);
            r.sub.setTextColor(t.incognito ? 0xFF9F7AEA : sidebarSub);
            r.close.setTextColor(sidebarSub);
            r.close.setTag(t);
            if (t.icon != null) {
                r.icon.setImageBitmap(t.icon);
                r.icon.setVisibility(View.VISIBLE);
                r.letter.setVisibility(View.GONE);
                Ui.bg(r.iconBox, Ui.rounded(RAISED, dp(8)));
            } else {
                r.icon.setVisibility(View.GONE);
                r.letter.setVisibility(View.VISIBLE);
                r.letter.setText(title.length() > 0 ? title.substring(0, 1).toUpperCase() : "\u00b7");
                Ui.bg(r.iconBox, Ui.rounded(Ui.mix(t.incognito ? PRIVATE_COLOR : ws.color, RAISED, 0.35f), dp(8)));
            }
            // Helium: the selected tab shares the URL bar's surface; others sit on the frame.
            boolean active = t == ws.primary || t == ws.secondary;
            boolean isFocused = t == focused;
            Ui.bg(convert, isFocused ? Ui.rounded(SURFACE, dp(10))
                    : active ? Ui.rounded(Ui.withAlpha(SURFACE, 0x88), dp(10)) : null);
            return convert;
        }
    }

    static final class Row {
        TextView letter, title, sub, close;
        ImageView icon;
        FrameLayout iconBox;
    }

    void renderWorkspaces() {
        wsTitle.setText(ws.name);
        Ui.bg(wsDot, Ui.oval(ws.color));
        wsStrip.removeAllViews();
        for (final Workspace w : workspaces) {
            // Dot + name chips; the current workspace gets the raised surface.
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), 0, dp(14), 0);
            View dot = new View(this);
            Ui.bg(dot, Ui.oval(w.color));
            chip.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
            TextView name = new TextView(this);
            name.setText(w.name);
            name.setTextSize(13);
            name.setSingleLine(true);
            name.setPadding(dp(8), 0, 0, 0);
            name.setTypeface(w == ws ? Ui.medium() : Typeface.DEFAULT);
            name.setTextColor(w == ws ? FG : SUB);
            chip.addView(name);
            if (w == ws) Ui.bg(chip, Ui.rounded(RAISED, dp(16)));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { switchWorkspace(w); }
            });
            chip.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { workspaceOptions(w); return true; }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
            lp.rightMargin = dp(4);
            wsStrip.addView(chip, lp);
        }
        TextView add = new TextView(this);
        add.setText("+");
        add.setTextSize(17);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(SUB);
        Ui.bg(add, Ui.oval(RAISED));
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { addWorkspace(); }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(34), dp(34));
        alp.leftMargin = dp(4);
        wsStrip.addView(add, alp);
    }

    // ------------------------------------------------------------------ tabs & workspaces

    ArrayList<Tab> allTabs() {
        ArrayList<Tab> out = new ArrayList<Tab>();
        for (Workspace w : workspaces) out.addAll(w.tabs);
        return out;
    }

    Tab newTab(String url, boolean incognito, Tab parent, boolean select) {
        Tab t = new Tab(url, incognito);
        t.parent = parent;
        int at = ws.tabs.indexOf(parent != null ? parent : focused);
        if (at < 0) ws.tabs.add(t); else ws.tabs.add(at + 1, t);
        if (select) {
            selectTab(t);
        } else {
            refreshChrome();
            Toast.makeText(this, "Opened in background", Toast.LENGTH_SHORT).show();
        }
        scheduleSave();
        return t;
    }

    void selectTab(Tab t) {
        if (t == ws.primary || t == ws.secondary) {
            setFocused(t);
            return;
        }
        if (ws.secondary != null && focused == ws.secondary) ws.secondary = t; else ws.primary = t;
        focused = t;
        updateCookiePolicy();
        showWorkspace();
    }

    void closeTab(Tab t) {
        int idx = ws.tabs.indexOf(t);
        if (idx < 0) return;
        ws.tabs.remove(idx);
        destroyWeb(t);
        if (t == ws.secondary) ws.secondary = null;
        if (t == ws.primary) {
            Tab next = null;
            if (t.parent != null && ws.tabs.contains(t.parent)) next = t.parent;
            else if (!ws.tabs.isEmpty()) next = ws.tabs.get(Math.min(Math.max(0, idx - 1), ws.tabs.size() - 1));
            if (next == ws.secondary) ws.secondary = null;
            ws.primary = next;
        }
        if (focused == t) focused = null;
        showWorkspace();
        scheduleSave();
    }

    void destroyWeb(Tab t) {
        if (t.web == null) return;
        ViewParent p = t.web.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(t.web);
        t.web.stopLoading();
        t.web.setWebChromeClient(null);
        t.web.setWebViewClient(null);
        t.web.destroy();
        t.web = null;
    }

    void unloadHidden() {
        for (Workspace w : workspaces) {
            for (Tab t : w.tabs) {
                boolean visible = w == ws && (t == ws.primary || t == ws.secondary);
                if (!visible && t.web != null && !t.incognito) {
                    String u = t.web.getUrl();
                    if (u != null) t.url = u;
                    destroyWeb(t);
                }
            }
        }
    }

    void switchWorkspace(Workspace w) {
        if (w == ws) return;
        ws = w;
        focused = null;
        showWorkspace();
        closeSidebar();
        Toast.makeText(this, w.name, Toast.LENGTH_SHORT).show();
        scheduleSave();
    }

    /** Puts the current workspace's primary (and split partner) tab on screen. */
    void showWorkspace() {
        paneA.removeAllViews();
        paneB.removeAllViews();
        if (ws.tabs.isEmpty()) {
            Tab t = new Tab(START, false);
            ws.tabs.add(t);
            ws.primary = t;
        }
        if (ws.primary == null || !ws.tabs.contains(ws.primary)) ws.primary = ws.tabs.get(0);
        if (ws.secondary != null && (!ws.tabs.contains(ws.secondary) || ws.secondary == ws.primary)) {
            ws.secondary = null;
        }
        attach(ws.primary, paneA);
        boolean split = ws.secondary != null;
        if (split) attach(ws.secondary, paneB);
        paneB.setVisibility(split ? View.VISIBLE : View.GONE);
        divider.setVisibility(split ? View.VISIBLE : View.GONE);
        if (focused == null || (focused != ws.primary && focused != ws.secondary)) focused = ws.primary;
        resumeVisible();
        updateSplitOrientation();
        refreshChrome();
    }

    void attach(Tab t, FrameLayout pane) {
        if (t.web == null) {
            t.web = createWebView(t);
            if (t.url != null) loadInto(t, t.url);
        }
        ViewParent p = t.web.getParent();
        if (p instanceof ViewGroup) ((ViewGroup) p).removeView(t.web);
        pane.addView(t.web, new FrameLayout.LayoutParams(-1, -1));
    }

    void resumeVisible() {
        for (Workspace w : workspaces) {
            for (Tab t : w.tabs) {
                if (t.web == null) continue;
                boolean visible = w == ws && (t == ws.primary || t == ws.secondary);
                if (visible) t.web.onResume(); else t.web.onPause();
            }
        }
    }

    void setFocused(Tab t) {
        focused = t;
        refreshChrome();
    }

    /**
     * WebView keeps one cookie jar for every tab, so private tabs can't have a separate one.
     * Instead cookies are switched off while a private tab is in front: it neither sees the
     * normal tabs' logins nor leaves cookies behind.
     */
    void updateCookiePolicy() {
        CookieManager.getInstance().setAcceptCookie(focused == null || !focused.incognito);
    }

    void refreshChrome() {
        updateCookiePolicy();
        if (ws == null || focused == null) return;
        boolean split = ws.secondary != null;
        tintChrome();
        int ring = Ui.mix(accent, 0xFFFFFFFF, 0.75f); // lifted for 3:1+ on the dark frame
        cardA.setOutline(split && focused == ws.primary, ring);
        cardB.setOutline(split && focused == ws.secondary, ring);
        if (!urlBar.hasFocus()) urlBar.setText(styledUrl(focused));
        progress.setProgress(focused.progress);
        int n = ws.tabs.size();
        tabCount.setText(n > 99 ? ":D" : String.valueOf(n));
        adapter.notifyDataSetChanged();
        renderWorkspaces();
    }

    void updateSplitOrientation() {
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        panes.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        paneA.setLayoutParams(land ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, 1f));
        paneB.setLayoutParams(land ? new LinearLayout.LayoutParams(0, -1, 1f) : new LinearLayout.LayoutParams(-1, 0, 1f));
        divider.setLayoutParams(land ? new LinearLayout.LayoutParams(dp(6), -1) : new LinearLayout.LayoutParams(-1, dp(6)));
    }

    void toggleSplit() {
        if (ws.secondary != null) {
            ws.secondary = null;
            focused = ws.primary;
        } else {
            Tab other = null;
            int i = ws.tabs.indexOf(ws.primary);
            if (i + 1 < ws.tabs.size()) other = ws.tabs.get(i + 1);
            else if (i - 1 >= 0) other = ws.tabs.get(i - 1);
            if (other == null) {
                other = new Tab(START, false);
                ws.tabs.add(other);
            }
            ws.secondary = other;
            focused = other;
        }
        showWorkspace();
        scheduleSave();
    }

    void openInSplit(Tab t) {
        if (t == ws.primary) return;
        ws.secondary = t;
        focused = t;
        showWorkspace();
    }

    void tabOptions(final Tab t) {
        final ArrayList<String> labels = new ArrayList<String>();
        labels.add(t == ws.secondary ? "Close split view" : "Open in split view");
        labels.add("Duplicate");
        labels.add("Move to workspace…");
        labels.add("Close other tabs");
        labels.add("Close tab");
        new AlertDialog.Builder(this)
                .setTitle(tabTitle(t))
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                if (t == ws.secondary) toggleSplit(); else openInSplit(t);
                                closeSidebar();
                                break;
                            case 1: newTab(currentUrl(t), t.incognito, t, false); break;
                            case 2: moveTabDialog(t); break;
                            case 3: closeOthers(t); break;
                            default: closeTab(t);
                        }
                    }
                }).show();
    }

    void closeOthers(Tab keep) {
        for (Tab t : new ArrayList<Tab>(ws.tabs)) {
            if (t != keep) {
                ws.tabs.remove(t);
                destroyWeb(t);
            }
        }
        ws.primary = keep;
        ws.secondary = null;
        focused = keep;
        showWorkspace();
        scheduleSave();
    }

    void moveTabDialog(final Tab t) {
        final ArrayList<Workspace> targets = new ArrayList<Workspace>();
        ArrayList<String> names = new ArrayList<String>();
        for (Workspace w : workspaces) if (w != ws) { targets.add(w); names.add(w.name); }
        names.add("New workspace…");
        new AlertDialog.Builder(this)
                .setTitle("Move to")
                .setItems(names.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which < targets.size()) {
                            moveTab(t, targets.get(which));
                        } else {
                            promptText("New workspace", "", new TextResult() {
                                @Override public void onText(String name) {
                                    Workspace w = new Workspace(name, PALETTE[workspaces.size() % PALETTE.length]);
                                    workspaces.add(w);
                                    moveTab(t, w);
                                }
                            });
                        }
                    }
                }).show();
    }

    void moveTab(Tab t, Workspace target) {
        ws.tabs.remove(t);
        if (t == ws.primary) ws.primary = null;
        if (t == ws.secondary) ws.secondary = null;
        if (t.web != null) {
            ViewParent p = t.web.getParent();
            if (p instanceof ViewGroup) ((ViewGroup) p).removeView(t.web);
        }
        t.parent = null;
        target.tabs.add(t);
        if (target.primary == null) target.primary = t;
        if (focused == t) focused = null;
        showWorkspace();
        Toast.makeText(this, "Moved to " + target.name, Toast.LENGTH_SHORT).show();
        scheduleSave();
    }

    void addWorkspace() {
        promptText("New workspace", "", new TextResult() {
            @Override public void onText(String name) {
                Workspace w = new Workspace(name, PALETTE[workspaces.size() % PALETTE.length]);
                workspaces.add(w);
                switchWorkspace(w);
            }
        });
    }

    void workspaceOptions(final Workspace w) {
        String[] items = workspaces.size() > 1
                ? new String[] {"Rename", "Change colour", "Delete workspace"}
                : new String[] {"Rename", "Change colour"};
        new AlertDialog.Builder(this)
                .setTitle(w.name)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        if (which == 0) {
                            promptText("Rename workspace", w.name, new TextResult() {
                                @Override public void onText(String name) { w.name = name; refreshChrome(); scheduleSave(); }
                            });
                        } else if (which == 1) {
                            new AlertDialog.Builder(MainActivity.this).setTitle("Colour")
                                    .setItems(PALETTE_NAMES, new DialogInterface.OnClickListener() {
                                        @Override public void onClick(DialogInterface d2, int c) {
                                            w.color = PALETTE[c];
                                            refreshChrome();
                                            scheduleSave();
                                        }
                                    }).show();
                        } else {
                            confirm("Delete \"" + w.name + "\"?", "Its " + w.tabs.size() + " tab(s) will be closed.",
                                    new Runnable() {
                                        @Override public void run() { deleteWorkspace(w); }
                                    });
                        }
                    }
                }).show();
    }

    void deleteWorkspace(Workspace w) {
        for (Tab t : w.tabs) destroyWeb(t);
        workspaces.remove(w);
        if (ws == w) {
            ws = workspaces.get(0);
            focused = null;
        }
        showWorkspace();
        scheduleSave();
    }

    // ------------------------------------------------------------------ navigation

    static boolean isStart(String url) {
        return url == null || url.startsWith(START);
    }

    String currentUrl(Tab t) {
        if (t.web != null && t.web.getUrl() != null) return t.web.getUrl();
        return t.url == null ? START : t.url;
    }

    /** The address with the site's domain emphasised and the rest muted. */
    CharSequence styledUrl(Tab t) {
        String u = displayUrl(t);
        int cut = u.indexOf('/');
        if (cut <= 0) return u;
        android.text.SpannableString s = new android.text.SpannableString(u);
        s.setSpan(new android.text.style.ForegroundColorSpan(SUB), cut, u.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    String displayUrl(Tab t) {
        String u = currentUrl(t);
        if (isStart(u)) return "";
        if (u.startsWith("https://")) u = u.substring(8);
        else if (u.startsWith("http://")) u = u.substring(7);
        if (u.startsWith("www.")) u = u.substring(4);
        if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    String hostOf(Tab t) {
        String h = TrackerBlocker.host(currentUrl(t));
        return h == null ? currentUrl(t) : h;
    }

    String tabTitle(Tab t) {
        if (isStart(currentUrl(t))) return "New tab";
        if (t.title != null && t.title.length() > 0) return t.title;
        return hostOf(t);
    }

    String toUrl(String input) {
        String s = input.trim();
        if (s.length() == 0) return START;
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*") || s.startsWith("about:") || s.startsWith("data:")) return s;
        if (!s.contains(" ") && HOSTLIKE.matcher(s).matches()) {
            return (s.startsWith("localhost") || s.matches("^\\d.*") ? "http://" : "https://") + s;
        }
        try {
            return store.searchTemplate().replace("%s", URLEncoder.encode(s, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return store.searchTemplate().replace("%s", s);
        }
    }

    /** Like {@link #toUrl} but never another scheme: anything that isn't http(s) becomes a search. */
    String webUrl(String input) {
        String u = toUrl(input);
        if (u.startsWith("http://") || u.startsWith("https://")) return u;
        try {
            return store.searchTemplate().replace("%s", URLEncoder.encode(input.trim(), "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            return START;
        }
    }

    void navigate(String input) {
        if (input.trim().length() == 0) return;
        loadInto(focused, toUrl(input));
        hideKeyboard();
        urlBar.clearFocus();
        focused.web.requestFocus();
    }

    void loadInto(Tab t, String url) {
        if (t.incognito) CookieManager.getInstance().setAcceptCookie(false);
        t.appNav = true;
        t.url = url;
        t.host = TrackerBlocker.host(url);
        if (t.web == null) return;
        if (url.startsWith("http") && store.flag("send_dnt", true)) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("DNT", "1");
            headers.put("Sec-GPC", "1");
            t.web.loadUrl(url, headers);
        } else {
            t.web.loadUrl(url);
        }
    }

    void handleIntent(Intent i) {
        if (i == null || i.getAction() == null) return;
        String a = i.getAction();
        if (Intent.ACTION_VIEW.equals(a) && i.getDataString() != null) {
            // Other apps may only open web pages: a file: page could read this app's private
            // files (cookies) on Android 4.0, where file pages have universal access.
            String d = i.getDataString();
            if (d.startsWith("http://") || d.startsWith("https://")) newTab(d, false, null, true);
        } else if (Intent.ACTION_WEB_SEARCH.equals(a)) {
            String q = i.getStringExtra(SearchManager.QUERY);
            if (q != null) newTab(toUrl(q), false, null, true);
        } else if (Intent.ACTION_SEND.equals(a)) {
            String text = i.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = Patterns.WEB_URL.matcher(text);
                newTab(m.find() ? toUrl(m.group()) : toUrl(text), false, null, true);
            }
        }
        setIntent(new Intent()); // don't reopen on recreation
    }

    // ------------------------------------------------------------------ WebView

    @SuppressWarnings("deprecation")
    void applySettings(Tab t) {
        WebSettings s = t.web.getSettings();
        s.setJavaScriptEnabled(store.flag("javascript", true));
        s.setTextZoom(store.textZoom());
        s.setUserAgentString(t.desktop ? DESKTOP_UA : null);
        t.ua = s.getUserAgentString(); // read off the UI thread when Ghostbrowser fetches pages itself
        // Base colour for pages that paint no background, so dark mode never starts on a white flash.
        t.web.setBackgroundColor(darkPages(t) ? CARD_BG : Color.WHITE);
        if (Build.VERSION.SDK_INT >= 21) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(t.web,
                    !t.incognito && !store.flag("block_3p_cookies", true));
        }
    }

    @SuppressWarnings({"deprecation", "SetJavaScriptEnabled"})
    WebView createWebView(final Tab t) {
        final WebView w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setDomStorageEnabled(!t.incognito); // site storage is shared by all tabs, so private tabs get none
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setGeolocationEnabled(false);
        s.setSavePassword(false);
        s.setSaveFormData(!t.incognito);
        if (t.incognito) s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        t.web = w;
        applySettings(t);

        w.setWebViewClient(new Client(t));
        w.setWebChromeClient(new Chrome(t));
        w.setDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                confirmDownload(url, ua, cd, mime, len);
            }
        });
        w.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    t.lastTouch = android.os.SystemClock.uptimeMillis();
                    if (urlBar.hasFocus()) urlBar.clearFocus();
                    if (focused != t && ws.secondary != null) setFocused(t);
                }
                return false;
            }
        });
        w.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return onPageLongPress(t); }
        });
        return w;
    }

    final class Client extends WebViewClient {
        final Tab t;
        Client(Tab t) { this.t = t; }

        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith("ghost:")) {
                // Only the start page may search this way, and only to a web address: anything
                // else would let a page (or a frame inside one) run javascript: or open file: URLs.
                String q = Uri.parse(url).getQueryParameter("q");
                if (q != null && START.equals(view.getUrl())) loadInto(t, webUrl(q));
                return true;
            }
            if (url.startsWith("http://") && store.flag("https_only", false)) {
                loadInto(t, "https://" + url.substring(7));
                return true;
            }
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file:")
                    || url.startsWith("about:") || url.startsWith("data:") || url.startsWith("javascript:")) {
                t.host = TrackerBlocker.host(url);
                return false;
            }
            // mailto:, tel:, market:, intent: … hand off to other apps, but only right after a tap;
            // otherwise ask, so a page can't open apps on its own.
            if (android.os.SystemClock.uptimeMillis() - t.lastTouch > 5000) {
                final String target = url;
                String scheme = url.indexOf(':') > 0 ? url.substring(0, url.indexOf(':')) : url;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Open another app?")
                        .setMessage((t.host != null ? t.host : "This page") + " wants to open a " + scheme + ": link in another app.")
                        .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) { openExternal(t, target); }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return true;
            }
            openExternal(t, url);
            return true;
        }

        void openExternal(Tab t, String url) {
            try {
                Intent i = url.startsWith("intent:")
                        ? Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addCategory(Intent.CATEGORY_BROWSABLE);
                i.setComponent(null);
                // A selector would bypass the BROWSABLE restriction; selectors arrived in API 15.
                if (Build.VERSION.SDK_INT >= 15) i.setSelector(null);
                try {
                    startActivity(i);
                } catch (ActivityNotFoundException e) {
                    String fallback = i.getStringExtra("browser_fallback_url");
                    if (fallback != null) loadInto(t, fallback);
                    else Toast.makeText(MainActivity.this, "No app can open this link", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Can't open link", Toast.LENGTH_SHORT).show();
            }
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            t.appNav = false; // the navigation's requests (and redirects) are done by now
            t.url = url;
            t.host = TrackerBlocker.host(url);
            t.blocked.set(0);
            t.compatStage = 0;
            t.icon = favicon;
            t.progress = 10;
            if (t == focused) refreshChrome();
        }

        @Override public void onPageFinished(WebView view, String url) {
            t.url = url;
            t.progress = 100;
            injectCompat(t);
            String title = view.getTitle();
            if (title != null && title.length() > 0) t.title = title;
            if (!t.incognito && store.flag("save_history", true) && url.startsWith("http")) {
                store.addHistory(url, t.title);
            }
            if (t == focused) refreshChrome(); else adapter.notifyDataSetChanged();
            scheduleSave();
        }

        /** Android 5+: modern-JavaScript pages and the runtime, then the usual tracker check. */
        @android.annotation.TargetApi(21)
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            if (ModernJs.engineNeedsIt()) {
                String file = ModernJs.runtimeFile(req);
                if (file != null) return ModernJs.runtime(getApplicationContext(), file);
                if (req.isForMainFrame() && "GET".equals(req.getMethod()) && modernJs(t) && mayFetchPage(t, req)) {
                    String u = req.getUrl().toString();
                    if (u.startsWith("http")) {
                        Map<String, String> extra = new HashMap<String, String>();
                        if (store.flag("send_dnt", true)) {
                            extra.put("DNT", "1");
                            extra.put("Sec-GPC", "1");
                        }
                        WebResourceResponse r = ModernJs.fetchPage(req, t.ua, extra);
                        if (r != null) return r;
                    }
                }
            }
            return shouldInterceptRequest(view, req.getUrl().toString());
        }

        /**
         * Ghostbrowser sends every cookie when it fetches a page itself, including SameSite=Strict
         * ones a cross-site navigation must not carry. So it only does that for navigations the
         * person started (address bar, reload, back/forward, a tap on a link) or same-host ones;
         * anything else loads normally, with the WebView's own cookie rules.
         */
        @android.annotation.TargetApi(21)
        boolean mayFetchPage(Tab t, WebResourceRequest req) {
            if (t.appNav || req.hasGesture()) return true;
            String ref = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                if ("Referer".equalsIgnoreCase(e.getKey())) ref = e.getValue();
            }
            if (ref == null) return false;
            String target = TrackerBlocker.host(req.getUrl().toString());
            String from = TrackerBlocker.host(ref);
            if (target == null || from == null) return false;
            // Same host or one inside the other; site() is too loose here (it groups all of co.uk).
            target = target.toLowerCase(java.util.Locale.US);
            from = from.toLowerCase(java.util.Locale.US);
            return target.equals(from) || target.endsWith("." + from) || from.endsWith("." + target);
        }

        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            if (!url.startsWith("http") || !store.flag("block_trackers", true)) return null;
            String h = TrackerBlocker.host(url);
            if (TrackerBlocker.isTracker(h) && !TrackerBlocker.site(h).equals(TrackerBlocker.site(t.host))) {
                t.blocked.incrementAndGet();
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override public void onReceivedSslError(WebView view, final SslErrorHandler handler, final SslError error) {
            if (!CertRescue.applicable(error)) {
                sslWarning(handler, error);
                return;
            }
            final Uri u = Uri.parse(error.getUrl());
            new Thread(new Runnable() {
                @Override public void run() {
                    final boolean ok = CertRescue.verify(getApplicationContext(), u.getHost(),
                            u.getPort() > 0 ? u.getPort() : 443, error.getCertificate());
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (ok) handler.proceed(); else sslWarning(handler, error);
                        }
                    });
                }
            }, "cert-rescue").start();
        }
    }

    final class Chrome extends WebChromeClient {
        final Tab t;
        Chrome(Tab t) { this.t = t; }

        @Override public void onProgressChanged(WebView view, int p) {
            t.progress = p;
            // Convert as early as there is a document, then again as stylesheets arrive.
            if ((p >= 30 && t.compatStage < 1) || (p >= 80 && t.compatStage < 2)) {
                t.compatStage = p >= 80 ? 2 : 1;
                injectCompat(t);
            }
            if (t == focused) progress.setProgress(p);
        }

        @Override public void onReceivedTitle(WebView view, String title) {
            t.title = title;
            adapter.notifyDataSetChanged();
        }

        @Override public void onReceivedIcon(WebView view, Bitmap icon) {
            t.icon = icon;
            adapter.notifyDataSetChanged();
        }

        @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, Message msg) {
            if (!userGesture) return false; // popup blocker
            Tab child = newTab(null, t.incognito, t, true);
            WebView.WebViewTransport transport = (WebView.WebViewTransport) msg.obj;
            transport.setWebView(child.web);
            msg.sendToTarget();
            return true;
        }

        @Override public void onCloseWindow(WebView window) {
            if (ws.tabs.contains(t)) closeTab(t);
        }

        @Override public boolean onJsPrompt(WebView view, String url, String message, String value, JsPromptResult result) {
            if (!"ghostcompat:fetch".equals(message)) return false;
            fetchCssForPage(t, value);
            result.confirm("");
            return true;
        }

        @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
            cb.invoke(origin, false, false); // location is never shared
        }

        @Override public void onShowCustomView(View view, CustomViewCallback cb) {
            if (customView != null) { cb.onCustomViewHidden(); return; }
            customView = view;
            customCallback = cb;
            ((FrameLayout) getWindow().getDecorView()).addView(view, new FrameLayout.LayoutParams(-1, -1));
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        @Override public void onHideCustomView() { hideCustomView(); }

        // Android 4.0
        public void openFileChooser(ValueCallback<Uri> cb, String accept) { legacyChooser(cb, accept); }

        // Android 4.1 – 4.4
        public void openFileChooser(ValueCallback<Uri> cb, String accept, String capture) { legacyChooser(cb, accept); }

        // Android 5.0+
        @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (upload != null) upload.onReceiveValue(null);
            upload = cb;
            return launchChooser("*/*");
        }
    }

    void sslWarning(final SslErrorHandler handler, SslError error) {
        String reason;
        switch (error.getPrimaryError()) {
            case SslError.SSL_EXPIRED: reason = "certificate expired"; break;
            case SslError.SSL_IDMISMATCH: reason = "certificate is for a different site"; break;
            case SslError.SSL_NOTYETVALID: reason = "certificate not yet valid (check the device date)"; break;
            case SslError.SSL_UNTRUSTED: reason = "issuer not trusted, even by Ghostbrowser's updated root list"; break;
            default: reason = "invalid certificate";
        }
        new AlertDialog.Builder(this)
                .setTitle("Connection not secure")
                .setMessage(TrackerBlocker.host(error.getUrl()) + ": " + reason + ".\n\n"
                        + "Someone may be trying to intercept this connection.")
                .setNegativeButton("Go back", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { handler.cancel(); }
                })
                .setPositiveButton("Proceed anyway", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { handler.proceed(); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) { handler.cancel(); }
                })
                .show();
    }

    void legacyChooser(ValueCallback<Uri> cb, String accept) {
        if (legacyUpload != null) legacyUpload.onReceiveValue(null);
        legacyUpload = cb;
        launchChooser(accept == null || accept.length() == 0 ? "*/*" : accept);
    }

    boolean launchChooser(String type) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type.contains("/") ? type : "*/*");
        try {
            startActivityForResult(Intent.createChooser(i, "Choose file"), REQ_FILE);
            return true;
        } catch (ActivityNotFoundException e) {
            deliverUpload(null);
            return false;
        }
    }

    void deliverUpload(Uri uri) {
        if (legacyUpload != null) {
            legacyUpload.onReceiveValue(uri);
            legacyUpload = null;
        }
        if (upload != null) {
            upload.onReceiveValue(uri == null ? null : new Uri[] {uri});
            upload = null;
        }
    }

    @Override protected void onActivityResult(int req, int result, Intent data) {
        if (req == REQ_FILE) {
            deliverUpload(result == RESULT_OK && data != null ? data.getData() : null);
        } else {
            super.onActivityResult(req, result, data);
        }
    }

    void hideCustomView() {
        if (customView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(customView);
        customView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (customCallback != null) customCallback.onCustomViewHidden();
        customCallback = null;
    }

    boolean onPageLongPress(final Tab t) {
        WebView.HitTestResult r = t.web.getHitTestResult();
        if (r == null) return false;
        final int type = r.getType();
        final String extra = r.getExtra();
        if (type == WebView.HitTestResult.SRC_ANCHOR_TYPE && extra != null) {
            linkMenu(t, extra, null);
            return true;
        }
        if (type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE && extra != null) {
            Handler h = new Handler(new Handler.Callback() {
                @Override public boolean handleMessage(Message m) {
                    String href = m.getData().getString("url");
                    linkMenu(t, href != null ? href : extra, extra);
                    return true;
                }
            });
            t.web.requestFocusNodeHref(h.obtainMessage());
            return true;
        }
        if (type == WebView.HitTestResult.IMAGE_TYPE && extra != null) {
            linkMenu(t, null, extra);
            return true;
        }
        return false;
    }

    void linkMenu(final Tab t, final String link, final String image) {
        final ArrayList<String> labels = new ArrayList<String>();
        final ArrayList<Runnable> actions = new ArrayList<Runnable>();
        if (link != null) {
            labels.add("Open in new tab");
            actions.add(new Runnable() { public void run() { newTab(link, t.incognito, t, false); } });
            labels.add("Open in private tab");
            actions.add(new Runnable() { public void run() { newTab(link, true, t, true); } });
            labels.add("Open in split view");
            actions.add(new Runnable() { public void run() {
                Tab n = newTab(link, t.incognito, t, false);
                openInSplit(n);
            } });
            labels.add("Copy link");
            actions.add(new Runnable() { public void run() { copy(link); } });
            labels.add("Share link");
            actions.add(new Runnable() { public void run() { share(link); } });
        }
        if (image != null && image.startsWith("http")) {
            labels.add("Open image in new tab");
            actions.add(new Runnable() { public void run() { newTab(image, t.incognito, t, false); } });
            labels.add("Download image");
            actions.add(new Runnable() { public void run() {
                startDownload(image, t.web == null ? null : t.web.getSettings().getUserAgentString(), null, null);
            } });
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(link != null ? link : image)
                .setItems(labels.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { actions.get(which).run(); }
                }).show();
    }

    // ------------------------------------------------------------------ downloads

    /** Pages can start downloads on their own, so ask before saving anything. */
    void confirmDownload(final String url, final String ua, final String disposition, final String mime, long len) {
        String name = URLUtil.guessFileName(url, disposition, mime);
        String size = len > 0 ? android.text.format.Formatter.formatShortFileSize(this, len) + " · " : "";
        new AlertDialog.Builder(this)
                .setTitle("Download file?")
                .setMessage(name + "\n" + size + TrackerBlocker.host(url))
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { startDownload(url, ua, disposition, mime); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    void startDownload(String url, String ua, String disposition, String mime) {
        if (url == null || !url.startsWith("http")) {
            Toast.makeText(this, "This download type isn't supported", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingDownload = new String[] {url, ua, disposition, mime};
            requestPermissions(new String[] {Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        try {
            String name = URLUtil.guessFileName(url, disposition, mime);
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            if (mime != null) r.setMimeType(mime);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) r.addRequestHeader("Cookie", cookies);
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            r.setTitle(name);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(this, "Downloading " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_STORAGE && pendingDownload != null) {
            String[] d = pendingDownload;
            pendingDownload = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startDownload(d[0], d[1], d[2], d[3]);
            } else {
                Toast.makeText(this, "Storage permission is needed to download", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------ menu

    void showMenu() {
        hideKeyboard();
        PopupMenu pm = new PopupMenu(this, menuBtn);
        Menu m = pm.getMenu();
        WebView w = focused.web;
        String url = currentUrl(focused);
        boolean page = !isStart(url);
        m.add(0, M_BACK, 0, "Back").setEnabled(w != null && w.canGoBack());
        m.add(0, M_FORWARD, 1, "Forward").setEnabled(w != null && w.canGoForward());
        m.add(0, M_RELOAD, 2, focused.progress < 100 ? "Stop" : "Reload");
        m.add(0, M_NEW, 3, "New tab");
        m.add(0, M_PRIVATE, 4, "New private tab");
        m.add(0, M_SPLIT, 5, ws.secondary != null ? "Close split view" : "Split view");
        if (ws.secondary != null) m.add(0, M_SWAP, 6, "Swap split panes");
        if (page && store.flag("compat_mode", true)) {
            m.add(0, M_COMPAT, 7, "Compatibility fixes").setCheckable(true).setChecked(!focused.compatOff);
        }
        if (page && store.flag("dark_pages", true)) {
            m.add(0, M_DARK, 7, "Dark page").setCheckable(true).setChecked(!focused.darkOff);
        }
        m.add(0, M_FIND, 7, "Find in page").setEnabled(page);
        m.add(0, M_DESKTOP, 8, "Desktop site").setCheckable(true).setChecked(focused.desktop);
        m.add(0, M_BOOKMARK, 9, store.isBookmarked(url) ? "Remove bookmark" : "Bookmark this page").setEnabled(page);
        m.add(0, M_BOOKMARKS, 10, "Bookmarks");
        m.add(0, M_HISTORY, 11, "History");
        m.add(0, M_SHARE, 12, "Share page").setEnabled(page);
        m.add(0, M_COPY, 13, "Copy link").setEnabled(page);
        if (store.flag("block_trackers", true)) {
            m.add(0, M_BLOCKED, 14, "Trackers blocked here: " + focused.blocked.get()).setEnabled(false);
        }
        m.add(0, M_SETTINGS, 15, "Settings");
        m.add(0, M_QUIT, 16, store.flag("clear_on_exit", false) ? "Quit & clear data" : "Quit");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override public boolean onMenuItemClick(MenuItem item) { return onMenu(item.getItemId()); }
        });
        pm.show();
    }

    boolean onMenu(int id) {
        WebView w = focused.web;
        String url = currentUrl(focused);
        switch (id) {
            case M_BACK: if (w != null) { focused.appNav = true; w.goBack(); } return true;
            case M_FORWARD: if (w != null) { focused.appNav = true; w.goForward(); } return true;
            case M_COMPAT:
                focused.compatOff = !focused.compatOff;
                if (w != null) w.reload();
                return true;
            case M_DARK:
                focused.darkOff = !focused.darkOff;
                if (w != null) {
                    applySettings(focused);
                    w.reload();
                }
                return true;
            case M_RELOAD:
                if (w == null) return true;
                if (focused.progress < 100) {
                    w.stopLoading();
                } else {
                    focused.appNav = true;
                    w.reload();
                }
                return true;
            case M_NEW: newTab(START, false, null, true); focusUrlBar(); return true;
            case M_PRIVATE:
                newTab(START, true, null, true);
                Toast.makeText(this, "Private tab: not saved to history or session", Toast.LENGTH_SHORT).show();
                focusUrlBar();
                return true;
            case M_SPLIT: toggleSplit(); return true;
            case M_SWAP: {
                Tab a = ws.primary;
                ws.primary = ws.secondary;
                ws.secondary = a;
                showWorkspace();
                return true;
            }
            case M_FIND: openFind(); return true;
            case M_DESKTOP:
                focused.desktop = !focused.desktop;
                if (w != null) {
                    applySettings(focused);
                    w.reload();
                }
                return true;
            case M_BOOKMARK:
                if (store.isBookmarked(url)) {
                    store.removeBookmark(url);
                    Toast.makeText(this, "Bookmark removed", Toast.LENGTH_SHORT).show();
                } else {
                    store.addBookmark(url, tabTitle(focused));
                    Toast.makeText(this, "Bookmarked", Toast.LENGTH_SHORT).show();
                }
                return true;
            case M_BOOKMARKS: showList("Bookmarks", "bookmarks"); return true;
            case M_HISTORY: showList("History", "history"); return true;
            case M_SHARE: share(url); return true;
            case M_COPY: copy(url); return true;
            case M_SETTINGS: startActivity(new Intent(this, SettingsActivity.class)); return true;
            case M_QUIT: finish(); return true;
            default: return false;
        }
    }

    void showList(final String title, final String key) {
        final JSONArray items = store.list(key);
        if (items.length() == 0) {
            Toast.makeText(this, title + " is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[items.length()];
        for (int i = 0; i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            String t = o == null ? "" : o.optString("t");
            String u = o == null ? "" : o.optString("u");
            labels[i] = (t.length() > 0 ? t : u) + "\n" + TrackerBlocker.host(u);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        JSONObject o = items.optJSONObject(which);
                        if (o != null) loadInto(focused, o.optString("u"));
                    }
                });
        if ("history".equals(key)) {
            b.setNeutralButton("Clear history", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface d, int w) {
                    store.p.edit().remove("history").apply();
                    Toast.makeText(MainActivity.this, "History cleared", Toast.LENGTH_SHORT).show();
                }
            });
        }
        b.setNegativeButton("Close", null);
        AlertDialog dialog = b.show();
        dialog.getListView().setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, final int pos, long id) {
                final JSONObject o = items.optJSONObject(pos);
                if (o == null) return false;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(o.optString("t"))
                        .setItems(new String[] {"Open in new tab", "Copy link", "Delete"},
                                new DialogInterface.OnClickListener() {
                                    @Override public void onClick(DialogInterface d, int which) {
                                        String u = o.optString("u");
                                        if (which == 0) newTab(u, false, null, false);
                                        else if (which == 1) copy(u);
                                        else if ("history".equals(key)) store.removeHistory(u);
                                        else store.removeBookmark(u);
                                    }
                                }).show();
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ find in page

    void openFind() {
        findBar.setVisibility(View.VISIBLE);
        findInput.setText("");
        findCount.setText("");
        findInput.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(findInput, 0);
    }

    void closeFind() {
        findBar.setVisibility(View.GONE);
        hideKeyboard();
        if (focused.web != null) focused.web.clearMatches();
    }

    @SuppressWarnings("deprecation")
    void doFind(String q) {
        WebView w = focused.web;
        if (w == null) return;
        if (q.length() == 0) {
            w.clearMatches();
            findCount.setText("");
            return;
        }
        if (Build.VERSION.SDK_INT >= 16) {
            findAsync(w, q);
        } else {
            int n = w.findAll(q);
            findCount.setText(String.valueOf(n));
        }
    }

    void findAsync(WebView w, String q) {
        w.setFindListener(new WebView.FindListener() {
            @Override public void onFindResultReceived(int active, int count, boolean done) {
                findCount.setText(count == 0 ? "0" : (active + 1) + "/" + count);
            }
        });
        w.findAllAsync(q);
    }

    // ------------------------------------------------------------------ session

    void scheduleSave() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 1500);
    }

    void saveSession() {
        try {
            JSONArray arr = new JSONArray();
            for (Workspace w : workspaces) {
                JSONObject wo = new JSONObject();
                wo.put("n", w.name);
                wo.put("c", w.color);
                JSONArray tabs = new JSONArray();
                int p = -1, s = -1;
                for (Tab t : w.tabs) {
                    if (t.incognito) continue;
                    if (t == w.primary) p = tabs.length();
                    if (t == w.secondary) s = tabs.length();
                    JSONObject to = new JSONObject();
                    to.put("u", currentUrl(t));
                    to.put("t", t.title == null ? "" : t.title);
                    to.put("d", t.desktop);
                    tabs.put(to);
                }
                wo.put("tabs", tabs);
                wo.put("p", p);
                wo.put("s", s);
                arr.put(wo);
            }
            JSONObject o = new JSONObject();
            o.put("ws", arr);
            o.put("cur", workspaces.indexOf(ws));
            store.p.edit().putString("session", o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    void restoreSession() {
        try {
            JSONObject o = new JSONObject(store.p.getString("session", "{}"));
            JSONArray arr = o.optJSONArray("ws");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject wo = arr.getJSONObject(i);
                Workspace w = new Workspace(wo.optString("n", "Workspace"), wo.optInt("c", PALETTE[0]));
                JSONArray tabs = wo.optJSONArray("tabs");
                for (int j = 0; tabs != null && j < tabs.length(); j++) {
                    JSONObject to = tabs.getJSONObject(j);
                    Tab t = new Tab(to.optString("u", START), false);
                    t.title = to.optString("t", "");
                    t.desktop = to.optBoolean("d", false);
                    w.tabs.add(t);
                }
                int p = wo.optInt("p", 0), s = wo.optInt("s", -1);
                if (p >= 0 && p < w.tabs.size()) w.primary = w.tabs.get(p);
                if (s >= 0 && s < w.tabs.size()) w.secondary = w.tabs.get(s);
                workspaces.add(w);
            }
            int cur = o.optInt("cur", 0);
            if (cur >= 0 && cur < workspaces.size()) ws = workspaces.get(cur);
        } catch (JSONException ignored) {
        }
        if (workspaces.isEmpty()) {
            workspaces.add(new Workspace("Personal", PALETTE[0]));
            workspaces.add(new Workspace("Work", PALETTE[1]));
        }
        if (ws == null) ws = workspaces.get(0);
    }

    // ------------------------------------------------------------------ compatibility mode

    String compatJs;

    /** Runs assets/compat.js in the page: CSS downlevelling, grid/gap emulation, content reveal. */
    @SuppressWarnings("deprecation")
    void injectCompat(Tab t) {
        if (t.web == null) return;
        boolean compat = !t.compatOff && store.flag("compat_mode", true);
        boolean dark = darkPages(t);
        if (!compat && !dark) return;
        String url = t.web.getUrl();
        if (url == null || !url.startsWith("http")) return;
        if (compatJs == null) {
            try {
                compatJs = new String(readAsset("compat.js"), "UTF-8");
            } catch (java.io.IOException e) {
                return;
            }
        }
        String code = "window.__ghostCfg={compat:" + compat + ",dark:" + dark + "};" + compatJs;
        if (Build.VERSION.SDK_INT >= 19) t.web.evaluateJavascript(code, null);
        else t.web.loadUrl("javascript:" + code.replace("%", "%25"));
    }

    boolean modernJs(Tab t) {
        return !t.compatOff && store.flag("compat_mode", true) && store.flag("modern_js", true);
    }

    boolean darkPages(Tab t) {
        return !t.darkOff && store.flag("dark_pages", true);
    }

    byte[] readAsset(String name) throws java.io.IOException {
        java.io.InputStream in = getAssets().open(name);
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /**
     * Fetches a stylesheet the page can't read itself (cross-origin, no CORS) so compat.js can
     * rewrite it. Public http(s) addresses only, no cookies, CSS only, so a page can't use this
     * to read the local network or anything private.
     */
    void fetchCssForPage(final Tab t, final String href) {
        new Thread(new Runnable() {
            @Override public void run() {
                String text = null;
                try {
                    java.net.URL u = new java.net.URL(href);
                    String proto = u.getProtocol();
                    if (!"http".equals(proto) && !"https".equals(proto)) throw new java.io.IOException("scheme");
                    // Android caches DNS answers for 2 s, so the connection below reuses these
                    // checked addresses; a rebinding DNS server can't swap in a local one between.
                    for (java.net.InetAddress a : java.net.InetAddress.getAllByName(u.getHost())) {
                        if (!isPublicAddress(a)) throw new java.io.IOException("private address");
                    }
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setUseCaches(true);
                    String type = c.getContentType();
                    if (c.getResponseCode() == 200 && type != null && type.toLowerCase(java.util.Locale.US).contains("css")) {
                        java.io.InputStream in = c.getInputStream();
                        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) > 0 && out.size() < 3 * 1024 * 1024) out.write(buf, 0, r);
                        in.close();
                        text = out.toString("UTF-8");
                    }
                    c.disconnect();
                } catch (Exception ignored) {
                    // unreachable, TLS too new for the platform stack, or refused above
                }
                final String js = "window.__ghostCssDone&&window.__ghostCssDone(" + JSONObject.quote(href) + ","
                        + (text == null ? "null" : JSONObject.quote(text)) + ")";
                runOnUiThread(new Runnable() {
                    @SuppressWarnings("deprecation")
                    @Override public void run() {
                        if (t.web == null) return;
                        if (Build.VERSION.SDK_INT >= 19) t.web.evaluateJavascript(js, null);
                        else t.web.loadUrl("javascript:" + js.replace("%", "%25"));
                    }
                });
            }
        }, "compat-css").start();
    }

    static boolean isPublicAddress(java.net.InetAddress a) {
        if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                || a.isAnyLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            if (b[0] == 0) return false;                                // 0.0.0.0/8
            if (b[0] == 100 && (b[1] & 0xC0) == 64) return false;       // 100.64.0.0/10 carrier NAT
            if ((b[0] & 0xFF) == 198 && (b[1] & 0xFE) == 18) return false; // 198.18.0.0/15
        } else if ((b[0] & 0xFE) == 0xFC) {
            return false;                                               // fc00::/7 unique local
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    interface TextResult { void onText(String text); }

    void promptText(String title, String initial, final TextResult result) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = input.getText().toString().trim();
                        if (s.length() > 0) result.onText(s);
                    }
                }).show();
    }

    void confirm(String title, String message, final Runnable yes) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { yes.run(); }
                }).show();
    }

    void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    void share(String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(i, "Share"));
    }

    void focusUrlBar() {
        urlBar.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(urlBar, 0);
    }

    void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(), 0);
        }
    }
}
