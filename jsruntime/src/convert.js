// Translates modern JavaScript syntax into syntax Chrome 61 understands. Sucrase (fast) handles
// ??, ?., catch {}, class fields and numeric separators; the rarer features Sucrase can't do
// (async generators, \p{} regexes, private fields, logical assignment …) go to Babel, which is
// only loaded when a module needs it. Imports, dynamic import() and import.meta are left for
// es-module-shims, which runs after this.
import { transform } from 'sucrase';
import { prePass, postPass, fixSucrase, moduleInfo, wrapTla, wrapDynamicImports } from './precise.js';

var CACHE = 'ghostbrowser-js-v6';
var BABEL_URL = '/__ghostbrowser/babel.js';
// Run on Sucrase's output: what's left that Chrome 61 can't parse. Private fields are matched as
// `.#name` / `#name in` so CSS colours like "#fff;" inside strings don't send a module to Babel.
var LEFTOVER = /async\s*\*|async\s+function\s*\*|for\s+await\b|\.#[a-zA-Z_$]|#[a-zA-Z_$][\w$]*\s+in\b|\?\?=|\|\|=|&&=|export\s*\*\s*as\s|\bstatic\s*\{|\\[pP]\{[A-Za-z_=]+\}|\(\?<[a-zA-Z$=!]|(?:^|[=(,:!&|?{};\[])\s*\/(?:[^\/\\\n]|\\.)+\/[dgimuy]*s[dgimuy]*\b/;
var BABEL_PLUGINS = ['transform-async-generator-functions', 'transform-unicode-property-regex',
  'transform-named-capturing-groups-regex', 'transform-dotall-regex', 'transform-export-namespace-from',
  'transform-logical-assignment-operators', 'transform-class-static-block', 'transform-private-methods',
  'transform-private-property-in-object', 'transform-class-properties', 'transform-nullish-coalescing-operator',
  'transform-optional-chaining', 'transform-optional-catch-binding', 'transform-numeric-separator'];
var babelLoading = null;

function babelReady() { return self.Babel && self.Babel.transform; }

function loadBabel() {
  if (babelReady()) return Promise.resolve();
  if (!babelLoading) {
    babelLoading = fetch(BABEL_URL).then(function (r) { return r.text(); }).then(function (src) { (0, eval)(src); });
  }
  return babelLoading;
}

function loadBabelSync() {
  if (babelReady()) return;
  var x = new XMLHttpRequest();
  x.open('GET', BABEL_URL, false);
  x.send();
  (0, eval)(x.responseText);
}

// Only cache URLs with a content hash in the file name; they never change.
var IMMUTABLE = /[.\-_][A-Za-z0-9_-]{8,}\.m?js(\?|$)/;

function viaSucrase(code, name) {
  return transform(code, { transforms: [], disableESTransforms: false, production: true, filePath: name || 'script.js' }).code;
}


function viaSnippetBabel(code) {
  loadBabelSync();
  return self.Babel.transform(code, { sourceType: 'module', compact: false, plugins: BABEL_PLUGINS }).code;
}

var ASYNC_GEN = /async\s*(function\s*)?\*/;

// Pre-pass (private fields, static blocks) -> Sucrase -> precise post-passes for whatever is left.
// Babel only ever sees individual async generator functions, never a whole module.
export function translate(code, name) {
  var out = fixSucrase(viaSucrase(prePass(code), name));
  for (var pass = 0; pass < 3 && LEFTOVER.test(out); pass++) {
    var next;
    try { next = postPass(out, viaSnippetBabel); } catch (e) { console.warn('Ghostbrowser: ' + (name || 'script') + ': ' + e.message); break; }
    if (next === out) break;
    out = next;
  }
  return out;
}

async function translateAsync(code, name) {
  if (ASYNC_GEN.test(code)) await loadBabel(); // avoid a blocking load later
  return translate(code, name);
}

function jsResponse(code, url) {
  var res = new Response(new Blob([code], { type: 'application/javascript' }));
  // es-module-shims resolves relative imports from response.url, which a new Response lacks.
  Object.defineProperty(res, 'url', { value: url });
  return res;
}

var TLA_MARK = '/*ghost:tla*/';
var graph = {};
// Who is waiting for whom while modules are prepared. A dependency counts as circular only if
// waiting on it would come back round to us; modules merely being prepared in parallel (which
// es-module-shims does a lot) are waited for normally.
var waitsFor = {};

function reaches(from, to, seen) {
  if (from === to) return true;
  if (seen[from]) return false;
  seen[from] = 1;
  var w = waitsFor[from] || [];
  for (var i = 0; i < w.length; i++) if (reaches(w[i], to, seen)) return true;
  return false;
}

// Fetches and translates one module; null for anything that isn't JavaScript.
async function translatedSource(url, options) {
  var cache = null;
  if (IMMUTABLE.test(url) && self.caches) {
    try {
      cache = await caches.open(CACHE);
      var hit = await cache.match(url);
      if (hit) return { code: await hit.text(), cached: true, cache: cache, url: url };
    } catch (e) { cache = null; }
  }
  var res = await fetch(url, options);
  var type = res.headers.get('content-type') || '';
  if (!res.ok || !/javascript|ecmascript/.test(type)) return null;
  var source = await res.text(), out;
  try {
    out = wrapDynamicImports(await translateAsync(source, url));
  } catch (e) {
    console.warn('Ghostbrowser: could not convert ' + url + ': ' + e.message);
    out = source;
  }
  return { code: out, cached: false, cache: cache, url: res.url || url };
}

// Translates a module after its static dependencies, so it knows which of them use top-level
// await (and must be waited for). Results are memoised; circular imports are not waited on.
function prepare(url, options) {
  if (graph[url]) return graph[url];
  graph[url] = (async function () {
    try {
      var t = await translatedSource(url, options);
      if (!t) return null;
      if (t.cached) return { code: t.code, tla: t.code.indexOf(TLA_MARK) === 0, url: t.url };
      var info;
      try { info = moduleInfo(t.code); } catch (e) { return { code: t.code, tla: false, url: t.url }; }
      var deps = info.deps.map(function (d) { try { return new URL(d, url).href; } catch (e) { return null; } });
      var waits = deps.map(function (d) {
        if (!d || (graph[d] && reaches(d, url, {}))) return null; // circular: don't wait
        (waitsFor[url] || (waitsFor[url] = [])).push(d);
        return prepare(d, options).catch(function () { return null; });
      });
      var subs = await Promise.all(waits);
      var tlaDeps = [];
      subs.forEach(function (r, k) { if (r && r.tla) tlaDeps.push(info.deps[k]); });
      var code = t.code, tla = false;
      if (info.tla || tlaDeps.length) {
        var wrapped = wrapTla(code, info, tlaDeps);
        if (wrapped) { code = TLA_MARK + wrapped; tla = true; }
        else console.warn('Ghostbrowser: ' + url + ' needs top-level await but has unsupported exports');
      }
      if (t.cache) { try { await t.cache.put(url, new Response(code)); } catch (e) {} }
      return { code: code, tla: tla, url: t.url };
    } finally {
      delete waitsFor[url];
    }
  })();
  return graph[url];
}

export async function convertModule(url, options) {
  var r = await prepare(url, options);
  if (!r) return fetch(url, options);
  return jsResponse(r.code, r.url || url);
}

// Inline classic scripts, routed here by Ghostbrowser so they still run in document order.
self.__ghostRun = function (source) {
  var code;
  try { code = translate(source); } catch (e) { code = source; }
  try {
    (0, eval)(code);
  } catch (e) {
    console.error('Ghostbrowser: inline script failed: ' + (e && e.message));
  }
};
