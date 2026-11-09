package com.ghostbrowser.browser;

import java.security.MessageDigest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares a page that relies on modern JavaScript for an older engine (no Android dependencies,
 * so it can be tested on a desktop JVM):
 *
 *  - inserts the runtime (assets/esm.js, served at {@link #RUNTIME_PATH}) before any page script;
 *  - marks module scripts type="module-shim" so the runtime loads and translates them;
 *  - wraps inline classic scripts as __ghostRun("…") so they are translated yet still run in order.
 */
final class HtmlRewriter {
    static final String RUNTIME_PATH = "/__ghostbrowser/esm.js";

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final Pattern SCRIPT = Pattern.compile("<script\\b([^>]*)>([\\s\\S]*?)</script\\s*>", CI);
    private static final Pattern TYPE = Pattern.compile("\\btype\\s*=\\s*([\"']?)([^\"'\\s>]*)\\1", CI);
    private static final Pattern SRC = Pattern.compile("\\bsrc\\s*=", CI);
    private static final Pattern NONCE = Pattern.compile("\\bnonce\\s*=\\s*([\"']?)([^\"'\\s>]+)\\1", CI);
    private static final Pattern HEAD = Pattern.compile("<head\\b[^>]*>", CI);
    private static final Pattern MODULE = Pattern.compile("<script\\b[^>]*\\btype\\s*=\\s*[\"']?module[\"'\\s>]", CI);
    private static final Pattern CLASSIC_TYPES =
            Pattern.compile("^(|text/javascript|application/javascript|text/ecmascript|application/ecmascript)$", CI);

    private HtmlRewriter() {}

    static boolean needsRewrite(String html) {
        return MODULE.matcher(html).find();
    }

    /** Returns the rewritten page; adds every new inline script body to {@code inlineOut}. */
    static String rewrite(String html, List<String> inlineOut) {
        StringBuffer out = new StringBuffer(html.length() + 4096);
        Matcher m = SCRIPT.matcher(html);
        String nonce = null;
        while (m.find()) {
            String attrs = m.group(1), body = m.group(2);
            Matcher nm = NONCE.matcher(attrs);
            if (nonce == null && nm.find()) nonce = nm.group(2);
            Matcher tm = TYPE.matcher(attrs);
            String type = tm.find() ? tm.group(2).trim() : "";
            String replacement;
            if (type.equalsIgnoreCase("module")) {
                replacement = "<script" + attrs.substring(0, tm.start()) + "type=\"module-shim\""
                        + attrs.substring(tm.end()) + ">" + body + "</script>";
            } else if (!SRC.matcher(attrs).find() && CLASSIC_TYPES.matcher(type).matches()
                    && body.trim().length() > 0) {
                String wrapped = "__ghostRun(" + jsString(body) + ")";
                inlineOut.add(wrapped);
                replacement = "<script" + attrs + ">" + wrapped + "</script>";
            } else {
                replacement = m.group(0);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);

        String tag = "<script src=\"" + RUNTIME_PATH + "\"" + (nonce != null ? " nonce=\"" + nonce + "\"" : "")
                + "></script>";
        Matcher hm = HEAD.matcher(out);
        if (hm.find()) {
            out.insert(hm.end(), tag);
        } else {
            int first = out.toString().toLowerCase().indexOf("<script");
            out.insert(Math.max(first, 0), tag);
        }
        return out.toString();
    }

    /**
     * Lets the runtime work under a Content-Security-Policy: it needs eval (translated inline
     * scripts), blob: (modules it has translated) and its own same-origin file, plus hashes for
     * the rewritten inline scripts when the policy pins inline scripts by hash.
     */
    static String adjustCsp(String csp, List<String> inline) {
        String lower = csp.toLowerCase();
        String directive = lower.contains("script-src") ? "script-src" : lower.contains("default-src") ? "default-src" : null;
        if (directive == null) return csp;
        StringBuilder extra = new StringBuilder(" 'self' 'unsafe-eval' blob:");
        if (lower.contains("'sha256-") || lower.contains("'sha384-") || lower.contains("'sha512-")) {
            for (String s : inline) extra.append(" 'sha256-").append(sha256(s)).append('\'');
        }
        StringBuilder out = new StringBuilder();
        for (String part : csp.split(";")) {
            String p = part.trim();
            if (p.toLowerCase().startsWith(directive + " ") || p.equalsIgnoreCase(directive)) p = p + extra;
            if (p.length() > 0) out.append(out.length() > 0 ? "; " : "").append(p);
        }
        return out.toString();
    }

    static String jsString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\u2028': b.append("\\u2028"); break;
                case '\u2029': b.append("\\u2029"); break;
                case '<': b.append("\\x3c"); break; // never close the <script> element early
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
            }
        }
        return b.append('"').toString();
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
            return base64(d);
        } catch (Exception e) {
            return "";
        }
    }

    private static String base64(byte[] d) {
        String abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            b.append(abc.charAt(n >> 18 & 63)).append(abc.charAt(n >> 12 & 63));
            b.append(i + 1 < d.length ? abc.charAt(n >> 6 & 63) : '=').append(i + 2 < d.length ? abc.charAt(n & 63) : '=');
        }
        return b.toString();
    }
}
