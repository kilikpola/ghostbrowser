# Ghostbrowser

A privacy-first Android browser for **Android 4.0 (API 14) and newer**, borrowing ideas from
Zen Browser, Floorp, Helium and LibreWolf.

| Feature | Inspired by |
|---|---|
| Workspaces: named, coloured tab groups (sidebar chips; long-press to rename/recolour/delete) | Zen |
| Side tab panel (tap the tab counter) | Zen / Floorp |
| Split view: two tabs side by side (landscape) or stacked (portrait); swap panes | Zen / Floorp |
| Tracker & ad blocking (`assets/blocklist.txt`, matches subdomains, first-party allowed) | LibreWolf / Helium |
| DNT + Global Privacy Control headers, third-party cookie blocking (5.0+), HTTPS-only mode | LibreWolf |
| No location sharing, popup blocker, private tabs, clear-data-on-quit | LibreWolf / Helium |
| Updated root certificates for old Android (see below) | Firefox's own CA store |
| Minimal chrome, accent colours, dark chrome, toolbar top/bottom, text size, desktop mode | Helium / Floorp |
| Tab unloading under memory pressure; lazily restored sessions | Zen |

Also: bookmarks, history, find in page, downloads, file upload, fullscreen video, link long-press
menu, share/copy, open links from other apps, choice of search engine (DuckDuckGo by default).

## Old-Android certificate rescue

Android 4.x root stores predate many current CAs, so its WebView rejects sites such as
duckduckgo.com and github.io as "issuer not trusted". `CertRescue` takes the exact
certificate the WebView rejected, fetches its intermediates from the certificate's AIA URLs and
validates the path with PKIX against the bundled Mozilla roots (`assets/cacerts.pem`, from
curl.se), plus a hostname check. The page is only allowed when that passes; any other
certificate error still shows a warning.

Refresh the roots occasionally:

    curl -fsSL https://curl.se/ca/cacert.pem -o assets/cacerts.pem

## Compatibility mode (modern sites on old engines)

On by default (Settings → Compatibility; per tab via Menu → Compatibility fixes). After a page
loads, Ghostbrowser injects `assets/compat.js`, which:

- rewrites stylesheets into CSS the Android 4.x engine (Chrome 30–37) understands: resolves
  `var()`, modern colour syntax and `oklch()`, unwraps `@layer`/`@container`, converts range media
  queries, logical properties, `inset`, `clamp()/min()/max()`, `dvh`; adds the `-webkit-` prefixes
  `transform`/`animation`/flexbox need; and drops only the selectors the engine can't parse, so
  the rest of each rule survives. Each sheet is replaced in place to keep cascade order;
- emulates CSS grid and flex `gap` with flexbox;
- reveals server-rendered content that waits for JavaScript the engine can't run (anti-flicker
  wrappers, `aria-busy` placeholders, fade-ins, `v-cloak`, `data-src` lazy images), and turns
  "scroll inside a full-screen box" layouts into normal page scrolling.

Together with "Run modern JavaScript" below, sites like solvely.ai become usable.

## Run modern JavaScript (Android 5+ with a Chrome 61–79 WebView)

Sites built for current browsers ship syntax older engines can't parse (`??`, `?.`, `catch {}`,
dynamic `import()`, top-level `await` …), so their buttons do nothing. With this setting on
(Settings → Compatibility), Ghostbrowser fetches a page that uses ES modules itself and rewrites it
(`HtmlRewriter.java`): it loads `assets/esm.js` first, marks module scripts `type="module-shim"` and
routes inline scripts through the translator so they still run in order.

A better fix, where possible, is a newer engine. Ghostbrowser uses whatever WebView the system
provides, so installing Chromium's SystemWebView (96 or later; on custom ROMs, replace
`/system/app/webview/webview.apk` from recovery and put its extracted `libwebviewchromium.so` in
`lib/arm/`) makes it run on that Chromium. The translator then turns itself off (engine ≥ 80), and
Google sign-in, whose Next button does nothing on old engines, responds again.

`assets/esm.js` is built from `jsruntime/` (`npm run build`, tests: `npm test`):

- es-module-shims loads modules, handling `import()` and `import.meta`;
- Sucrase translates most syntax; a token-level "precise" pass (`src/precise.js`, acorn's
  tokenizer, no syntax tree) handles what Sucrase leaves: logical assignment, `\p{…}`/named-group/
  dotAll/lookbehind regexes (also in `new RegExp` at runtime), `for await`, `export * as`, private
  `#fields`, `static {}` blocks;
- async generators are cut out and translated alone by Babel (`assets/babel.js`, loaded only
  when needed), so multi-megabyte modules never go through Babel whole;
- top-level `await` is emulated like bundler plugins do: function declarations stay hoisted,
  other declarations are assigned in an async body, and importers wait on `__ghostTla`, with
  real cycle detection so parallel loading never deadlocks or runs a module too early;
- polyfills for missing built-ins (`Promise.allSettled`, `Object.fromEntries`, `structuredClone`,
  `ResizeObserver`, `AbortController`, `Intl.PluralRules`/`RelativeTimeFormat`/`ListFormat` …);
- translated modules are cached (Cache Storage), so only the first visit pays the cost.

Known limits: pages inside iframes and scripts that pages add dynamically as classic `<script>`
tags aren't translated; `\p{…}` uses approximate ranges and lookbehind is dropped.

Debugging: Settings → Developer → Debug pages over USB (Android 4.4+), then
`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`. On Android 4.4,
`adb shell input swipe` can't scroll WebViews (each move gets a new down-time); use a
`monkey` script with `Drag(x1,y1,x2,y2,steps)` instead.

## Build

Needs JDK 17 and the Android SDK (build-tools + `platforms/android-34`). No Gradle.

    ./build.sh            # -> build/Ghostbrowser.apk
    adb install -r build/Ghostbrowser.apk

The first build creates `ghostbrowser-release.keystore` with a placeholder password set in
`build.sh`. Change it before publishing anywhere, and keep the keystore safe: updates must be
signed with the same key.

## Limits

Ghostbrowser renders with the system WebView, not Gecko or a bundled Chromium (neither runs on
Android 4.0). On Android 4.0–4.3 the WebView is the old pre-Chromium engine, and 4.0 has no
TLS 1.2, so many modern sites will load badly or not at all there. 4.4 and newer work much
better. Private tabs keep no history or session, but WebView cookies are shared app-wide.
