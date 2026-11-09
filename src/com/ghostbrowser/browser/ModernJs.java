package com.ghostbrowser.browser;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Modern JavaScript" mode. WebView engines from Chrome 61 to 79 support ES modules but not the
 * syntax today's sites ship (??, ?., catch {}, dynamic import() …), so such sites stay inert.
 * For those engines Ghostbrowser fetches a page itself and, when it uses module scripts, rewrites
 * it ({@link HtmlRewriter}) so assets/esm.js translates every script before it runs.
 */
final class ModernJs {
    private static final int MAX_HTML = 8 * 1024 * 1024;
    private static final String PREFIX = "/__ghostbrowser/";
    private static final String[] FILES = {"esm.js", "babel.js"}; // served from assets
    private static volatile int engine = -1;
    private static final Map<String, byte[]> files = new HashMap<String, byte[]>();

    private ModernJs() {}

    /** Major Chrome version of the system WebView, from its default user agent. */
    static void detectEngine(Context c) {
        if (engine >= 0) return;
        int v = 0;
        if (Build.VERSION.SDK_INT >= 17) {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(c));
            if (m.find()) v = Integer.parseInt(m.group(1));
        }
        engine = v;
    }

    /** Modules are needed to run the translator; engines from Chrome 80 on don't need it. */
    static boolean engineNeedsIt() {
        return Build.VERSION.SDK_INT >= 21 && engine >= 61 && engine < 80;
    }

    static int engine() {
        return engine;
    }

    /** The asset a /__ghostbrowser/ request names, or null for anything else. */
    static String runtimeFile(WebResourceRequest req) {
        String path = req.getUrl().getPath();
        if (path == null || !path.startsWith(PREFIX)) return null;
        String name = path.substring(PREFIX.length());
        for (String f : FILES) if (f.equals(name)) return f;
        return null;
    }

    @TargetApi(21)
    static synchronized WebResourceResponse runtime(Context c, String name) {
        byte[] data = files.get(name);
        if (data == null) {
            try {
                data = readAll(c.getAssets().open(name), 8 * 1024 * 1024);
            } catch (IOException e) {
                return null;
            }
            files.put(name, data);
        }
        Map<String, String> h = new HashMap<String, String>();
        h.put("Cache-Control", "max-age=86400");
        h.put("Access-Control-Allow-Origin", "*");
        return new WebResourceResponse("application/javascript", "utf-8", 200, "OK", h,
                new ByteArrayInputStream(data));
    }

    /**
     * Fetches a top-level page. Returns null to let the WebView load it normally (redirects,
     * errors, non-HTML), or the page rewritten when it relies on module scripts.
     */
    @TargetApi(21)
    static WebResourceResponse fetchPage(WebResourceRequest req, String userAgent, Map<String, String> extra) {
        String url = req.getUrl().toString();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet()) {
                c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (userAgent != null) c.setRequestProperty("User-Agent", userAgent);
            for (Map.Entry<String, String> e : extra.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
            CookieManager cm = CookieManager.getInstance();
            String cookie = cm.getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);

            int code = c.getResponseCode();
            Map<String, List<String>> fields = c.getHeaderFields();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("Set-Cookie")) {
                    for (String v : e.getValue()) cm.setCookie(url, v);
                }
            }
            cm.flush();
            if (code >= 300 && code < 400) return null; // the WebView follows redirects itself

            String type = c.getContentType();
            if (code != 200 || type == null || !type.toLowerCase(Locale.US).startsWith("text/html")) return null;
            String charset = "UTF-8";
            Matcher cs = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(type);
            if (cs.find()) charset = cs.group(1);

            byte[] body = readAll(c.getInputStream(), MAX_HTML);
            String html = new String(body, charset);
            Map<String, String> headers = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> e : fields.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue().isEmpty()) continue;
                String kl = k.toLowerCase(Locale.US);
                if (kl.equals("content-length") || kl.equals("content-encoding") || kl.equals("transfer-encoding")
                        || kl.equals("set-cookie") || kl.equals("content-type")) continue;
                headers.put(k, joined(e.getValue()));
            }
            if (HtmlRewriter.needsRewrite(html)) {
                List<String> inline = new ArrayList<String>();
                html = HtmlRewriter.rewrite(html, inline);
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey().equalsIgnoreCase("Content-Security-Policy")) {
                        e.setValue(HtmlRewriter.adjustCsp(e.getValue(), inline));
                    }
                }
                body = html.getBytes(charset);
            }
            String reason = c.getResponseMessage();
            return new WebResourceResponse("text/html", charset, code,
                    reason == null || reason.length() == 0 ? "OK" : reason, headers, new ByteArrayInputStream(body));
        } catch (Exception e) {
            return null; // fall back to a normal load
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String joined(List<String> values) {
        StringBuilder b = new StringBuilder();
        for (String v : values) b.append(b.length() > 0 ? ", " : "").append(v);
        return b.toString();
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > limit) throw new IOException("too large");
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
