/*
 * Ghostbrowser compatibility mode. Injected into pages after they load, in the system WebView of
 * old Android releases (Chrome 30-37 era). Written in ES5 so that engine can run it.
 *
 *  1. Rewrites stylesheets into CSS that engine understands: resolves var(), modern colour syntax
 *     and oklch(), unwraps @layer/@container, converts range media queries, logical properties,
 *     inset, clamp()/min()/max(), adds -webkit- prefixes, and drops selectors it can't parse so the
 *     rest of each rule survives. Each original sheet is replaced in place to keep cascade order.
 *  2. Emulates display:grid and flex gap with flexbox.
 *  3. Reveals content that pages hide until their (modern, never-running) JavaScript starts.
 *
 * Engine-aware: it probes what the running WebView supports and converts only what is missing,
 * so a Chrome 61 WebView keeps native grid and variables while a Chrome 33 one gets fallbacks.
 * With window.__ghostCfg.dark it also remaps page colours to a dark scheme (light backgrounds
 * darken, dark text lightens, hues and images are kept), the way Dark Reader's dynamic mode does.
 *
 * Safe to inject more than once.
 */
(function () {
  if (window.__ghostCompat) { window.__ghostCompat.run(); return; }
  // Injected early while loading: wait until the new document has its root element.
  if (!document.documentElement || !document.head) {
    var self = arguments.callee;
    setTimeout(function () { self(); }, 150);
    return;
  }

  var CFG = window.__ghostCfg || {};
  var COMPAT = CFG.compat !== false;
  var DARK = !!CFG.dark;

  function sup(p, v) {
    try { if (window.CSS && CSS.supports) return CSS.supports(p, v); } catch (e) {}
    var st = document.createElement('div').style;
    st.cssText = p + ':' + v;
    return st.length > 0;
  }
  function selOk(sel) { try { document.querySelector(sel); return true; } catch (e) { return false; } }
  function canParse(code) { try { new Function(code); return true; } catch (e) { return false; } }
  function rangeMq() { try { return window.matchMedia('(width >= 0px)').matches; } catch (e) { return false; } }
  function layerOk() {
    try {
      var st = document.createElement('style');
      st.textContent = '@layer ghost{.ghost-l{color:red}}';
      (document.head || document.documentElement).appendChild(st);
      var ok = !!(st.sheet && st.sheet.cssRules && st.sheet.cssRules.length);
      st.parentNode.removeChild(st);
      return ok;
    } catch (e) { return false; }
  }
  function flexGapOk() {
    if (!document.body) return false;
    var d = document.createElement('div');
    d.style.cssText = 'display:flex;flex-direction:column;gap:1px;position:absolute;visibility:hidden';
    d.appendChild(document.createElement('div'));
    d.appendChild(document.createElement('div'));
    document.body.appendChild(d);
    var ok = d.scrollHeight === 1;
    document.body.removeChild(d);
    return ok;
  }

  // What this engine understands natively. With compatibility off, everything counts as supported.
  var F = {
    vars: sup('color', 'var(--g)'), grid: sup('display', 'grid'), flexGap: flexGapOk(),
    inset: sup('inset', '0px'), logical: sup('margin-inline-start', '0px'), place: sup('place-items', 'center'),
    math: sup('width', 'clamp(1px, 2px, 3px)') && sup('width', 'min(1px, 2px)'), dvh: sup('height', '1dvh'),
    aspect: sup('aspect-ratio', '1'), sticky: sup('position', 'sticky'), indiv: sup('translate', '1px'),
    oklch: sup('color', 'oklch(50% 0.1 100)'), spaceRgb: sup('color', 'rgb(0 0 0 / 50%)'),
    hex8: sup('color', '#0000ff80'), colorMix: sup('color', 'color-mix(in srgb, red, blue)'),
    transform: sup('transform', 'none'), animation: sup('animation', 'none'), flex: sup('display', 'flex'),
    is: selOk(':is(html)'), has: selOk(':has(*)'), focusVisible: selOk(':focus-visible'),
    focusWithin: selOk(':focus-within'), notList: selOk(':not(a, b)'),
    range: rangeMq(), layer: layerOk(), container: sup('container-type', 'inline-size')
  };
  var BLUR = sup('backdrop-filter', 'blur(1px)') || sup('-webkit-backdrop-filter', 'blur(1px)');
  if (!COMPAT) for (var fk in F) F[fk] = true;
  F.syntax = canParse('a ?? b') && canParse('a?.b');
  F.modules = 'noModule' in document.createElement('script');
  var MODERN_CSS = F.vars && F.grid && F.flexGap && F.inset && F.logical && F.place && F.math && F.dvh &&
      F.aspect && F.sticky && F.oklch && F.spaceRgb && F.hex8 && F.colorMix && F.transform && F.animation &&
      F.is && F.has && F.focusVisible && F.focusWithin && F.notList && F.range && F.layer && F.container;
  var COLOR_OK = F.oklch && F.spaceRgb && F.hex8 && F.colorMix;
  try { console.info('Ghostbrowser compat: missing=' + Object.keys(F).filter(function (k) { return !F[k]; }).join(',')); } catch (e) {}

  var VAR_ROOT = /^(:root|html|body|\*|:host)$/;
  var varRoles = {};         // custom property -> {bg, fg, border} usage counts (dark mode)
  var clipTextSels = [];     // selectors painting text with a clipped background (gradient text)
  var globals = {};          // custom property -> value
  var layouts = [];          // [{sel, media, grid, cols, colGap, rowGap, center}] in source order
  var pendingFetch = {};
  var running = false;

  // ------------------------------------------------------------ tiny CSS parser

  function stripComments(css) { return css.replace(/\/\*[\s\S]*?\*\//g, ''); }

  // Splits on `sep` outside quotes and parentheses.
  function splitTop(s, sep) {
    var out = [], depth = 0, q = null, start = 0, i, c;
    for (i = 0; i < s.length; i++) {
      c = s.charAt(i);
      if (q) { if (c === '\\') i++; else if (c === q) q = null; continue; }
      if (c === '"' || c === "'") q = c;
      else if (c === '(' || c === '[') depth++;
      else if (c === ')' || c === ']') depth--;
      else if (c === sep && depth === 0) { out.push(s.slice(start, i)); start = i + 1; }
    }
    out.push(s.slice(start));
    return out;
  }

  // Returns [{prelude, body}|{prelude, children}|{stmt}].
  function parse(css) {
    var nodes = [], i = 0, n = css.length;
    while (i < n) {
      var start = i, q = null, depth = 0, c;
      // read prelude up to '{' or ';'
      while (i < n) {
        c = css.charAt(i);
        if (q) { if (c === '\\') i++; else if (c === q) q = null; }
        else if (c === '"' || c === "'") q = c;
        else if (c === '(') depth++;
        else if (c === ')') depth--;
        else if ((c === '{' || c === ';') && depth === 0) break;
        else if (c === '}' && depth === 0) break;
        i++;
      }
      var prelude = css.slice(start, i).trim();
      if (i >= n) { break; }
      c = css.charAt(i);
      if (c === ';') { if (prelude) nodes.push({ stmt: prelude }); i++; continue; }
      if (c === '}') { i++; continue; }
      // find matching brace
      var bstart = ++i; depth = 1; q = null;
      while (i < n && depth > 0) {
        c = css.charAt(i);
        if (q) { if (c === '\\') i++; else if (c === q) q = null; }
        else if (c === '"' || c === "'") q = c;
        else if (c === '{') depth++;
        else if (c === '}') depth--;
        i++;
      }
      var body = css.slice(bstart, i - 1);
      if (/^@(media|supports|layer|container|document|-moz-document|scope|starting-style)\b/i.test(prelude)) {
        nodes.push({ prelude: prelude, children: parse(body) });
      } else {
        nodes.push({ prelude: prelude, body: body });
      }
    }
    return nodes;
  }

  function decls(body) {
    var out = [], parts = splitTop(body, ';'), i, p, k;
    for (i = 0; i < parts.length; i++) {
      p = parts[i];
      k = p.indexOf(':');
      if (k < 0) continue;
      var prop = p.slice(0, k).trim(), val = p.slice(k + 1).trim();
      if (!prop || /[{}]/.test(prop)) continue;
      var imp = '';
      if (/!important\s*$/i.test(val)) { imp = ' !important'; val = val.replace(/\s*!important\s*$/i, ''); }
      out.push({ p: prop, v: val, imp: imp });
    }
    return out;
  }

  // ------------------------------------------------------------ value rewriting

  function resolveVars(v, local, depth) {
    if (v.indexOf('var(') < 0) return v;
    if (depth > 8) return null;
    var out = '', i = 0, k;
    while ((k = v.indexOf('var(', i)) >= 0) {
      out += v.slice(i, k);
      var j = k + 4, d = 1;
      while (j < v.length && d > 0) { var c = v.charAt(j); if (c === '(') d++; else if (c === ')') d--; j++; }
      var inner = v.slice(k + 4, j - 1), comma = splitTop(inner, ',');
      var name = comma[0].trim(), fb = comma.length > 1 ? comma.slice(1).join(',').trim() : null;
      var val = local && local.hasOwnProperty(name) ? local[name] : (globals.hasOwnProperty(name) ? globals[name] : fb);
      if (val === null || val === undefined) return null;
      val = resolveVars(val, local, depth + 1);
      if (val === null) return null;
      out += val;
      i = j;
    }
    return out + v.slice(i);
  }

  function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }
  function num(s, pctScale) {
    s = String(s).trim();
    if (s === 'none') return 0;
    if (/%$/.test(s)) return parseFloat(s) / 100 * pctScale;
    return parseFloat(s);
  }
  function rgbaString(r, g, b, a) {
    r = Math.round(clamp01(r) * 255); g = Math.round(clamp01(g) * 255); b = Math.round(clamp01(b) * 255);
    return a < 1 ? 'rgba(' + r + ',' + g + ',' + b + ',' + (+a.toFixed(3)) + ')' : 'rgb(' + r + ',' + g + ',' + b + ')';
  }
  function gamma(x) { return x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1 / 2.4) - 0.055; }
  function oklab(L, a, b, alpha) {
    var l_ = L + 0.3963377774 * a + 0.2158037573 * b;
    var m_ = L - 0.1055613458 * a - 0.0638541728 * b;
    var s_ = L - 0.0894841775 * a - 1.2914855480 * b;
    var l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;
    return rgbaString(
      gamma(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
      gamma(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
      gamma(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s), alpha);
  }
  function splitColorArgs(inner) {
    var alpha = 1, parts;
    if (inner.indexOf('/') >= 0) {
      var sl = inner.split('/');
      inner = sl[0];
      alpha = num(sl[1], 1);
    }
    parts = inner.indexOf(',') >= 0 ? inner.split(',') : inner.trim().split(/\s+/);
    if (parts.length === 4) { alpha = num(parts[3], 1); parts = parts.slice(0, 3); }
    return { p: parts, a: isNaN(alpha) ? 1 : alpha };
  }

  function rewriteColors(v) {
    v = v.replace(/\b(oklch|oklab)\(([^()]*)\)/gi, function (m, fn, inner) {
      var c = splitColorArgs(inner), p = c.p;
      var L = num(p[0], 1), A, B;
      if (fn.toLowerCase() === 'oklch') {
        var C = num(p[1], 0.4), H = parseFloat(p[2]) || 0;
        A = C * Math.cos(H * Math.PI / 180); B = C * Math.sin(H * Math.PI / 180);
      } else { A = num(p[1], 0.4); B = num(p[2], 0.4); }
      return oklab(L, A, B, c.a);
    });
    v = v.replace(/\b(rgba?|hsla?)\(([^()]*)\)/gi, function (m, fn, inner) {
      if (inner.indexOf(',') >= 0 && inner.indexOf('/') < 0) return m;
      var c = splitColorArgs(inner), p = c.p, hsl = /^h/i.test(fn);
      if (p.length < 3) return m;
      var a = c.a;
      if (hsl) {
        return 'hsla(' + (parseFloat(p[0]) || 0) + ',' + p[1].trim() + ',' + p[2].trim() + ',' + a + ')';
      }
      var r = /%$/.test(p[0]) ? num(p[0], 255) : parseFloat(p[0]);
      var g = /%$/.test(p[1]) ? num(p[1], 255) : parseFloat(p[1]);
      var b = /%$/.test(p[2]) ? num(p[2], 255) : parseFloat(p[2]);
      return rgbaString(r / 255, g / 255, b / 255, a);
    });
    v = v.replace(/#([0-9a-f]{8}|[0-9a-f]{4})\b/gi, function (m, h) {
      if (h.length === 4) h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2) + h.charAt(3) + h.charAt(3);
      return rgbaString(parseInt(h.slice(0, 2), 16) / 255, parseInt(h.slice(2, 4), 16) / 255,
        parseInt(h.slice(4, 6), 16) / 255, parseInt(h.slice(6, 8), 16) / 255);
    });
    v = v.replace(/\bcolor-mix\([^()]*(\([^()]*\)[^()]*)*\)/gi, function (m) {
      var col = m.match(/(rgba?\([^)]*\)|#[0-9a-f]{3,8}\b|\b[a-z]+\b)(?=\s+\d+%|\s*\)|\s*,)/gi);
      return col && col.length > 1 ? col[1] : 'transparent';
    });
    return v;
  }

  function rewriteMath(v) {
    var guard = 0;
    while (/\b(clamp|min|max)\(/i.test(v) && guard++ < 10) {
      v = v.replace(/\b(clamp|min|max)\(((?:[^()]|\([^()]*\))*)\)/i, function (m, fn, inner) {
        var a = splitTop(inner, ',');
        fn = fn.toLowerCase();
        if (fn === 'clamp' && a.length === 3) return a[1].trim();
        // min(100%, 72rem) is almost always "a fixed size, but no wider than the box"
        for (var i = 0; i < a.length; i++) if (!/%/.test(a[i])) return a[i].trim();
        return a[0].trim();
      });
    }
    return v.replace(/(\d)(d|s|l)v(h|w)\b/g, '$1v$3');
  }

  function rewriteValue(v, local) {
    if (!F.vars && v.indexOf('var(') >= 0) { v = resolveVars(v, local, 0); if (v === null) return null; }
    // Tailwind's rgb(255 255 255 / var(--tw-bg-opacity)): resolve the variable inside the colour so
    // the colour can be converted (old engines reject the space syntax) and dark-mapped.
    if ((DARK || !COLOR_OK) && /(rgba?|hsla?)\((?:[^()]*)var\(/.test(v)) {
      v = v.replace(/\b(rgba?|hsla?)\(((?:[^()]|\([^()]*\))*)\)/g, function (m) {
        return m.indexOf('var(') >= 0 ? (resolveVars(m, local, 0) || m.replace(/var\([^()]*\)/g, '1')) : m;
      });
    }
    if ((DARK || !COLOR_OK) && /\b(oklch|oklab|rgba?|hsla?|color-mix)\(|#[0-9a-f]{4}\b|#[0-9a-f]{8}\b/i.test(v)) v = rewriteColors(v);
    if ((!F.math || !F.dvh) && /\b(clamp|min|max)\(|\d[dsl]v[hw]\b/i.test(v)) v = rewriteMath(v);
    return v;
  }

  // ------------------------------------------------------------ dark mode colour mapping

  var NAMED = { white: [255, 255, 255], black: [0, 0, 0], silver: [192, 192, 192], gray: [128, 128, 128],
    grey: [128, 128, 128], whitesmoke: [245, 245, 245], gainsboro: [220, 220, 220], lightgray: [211, 211, 211],
    lightgrey: [211, 211, 211], darkgray: [169, 169, 169], dimgray: [105, 105, 105], snow: [255, 250, 250],
    ivory: [255, 255, 240], beige: [245, 245, 220], linen: [250, 240, 230], aliceblue: [240, 248, 255],
    ghostwhite: [248, 248, 255], floralwhite: [255, 250, 240], navy: [0, 0, 128], blue: [0, 0, 255],
    red: [255, 0, 0], green: [0, 128, 0], yellow: [255, 255, 0], orange: [255, 165, 0], purple: [128, 0, 128] };
  var COLOR_TOKEN = /#[0-9a-f]{3,8}\b|rgba?\([^()]*\)|hsla?\([^()]*\)|\b(white|black|silver|gr[ae]y|whitesmoke|gainsboro|light(gray|grey)|darkgray|dimgray|snow|ivory|beige|linen|aliceblue|ghostwhite|floralwhite|navy|blue|red|green|yellow|orange|purple)\b/gi;

  function parseColor(c) {
    var m, h;
    c = c.toLowerCase();
    if (NAMED[c]) return NAMED[c].concat(1);
    if (c.charAt(0) === '#') {
      h = c.slice(1);
      if (h.length === 3 || h.length === 4) h = h.replace(/./g, '$&$&');
      if (h.length !== 6 && h.length !== 8) return null;
      return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16),
        h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1];
    }
    if ((m = c.match(/^rgba?\(([^()]*)\)$/))) {
      var p = m[1].split(/[\s,\/]+/).filter(function (x) { return x; });
      if (p.length < 3) return null;
      var ch = function (x) { return /%$/.test(x) ? parseFloat(x) * 2.55 : parseFloat(x); };
      var a = p.length > 3 ? (/%$/.test(p[3]) ? parseFloat(p[3]) / 100 : parseFloat(p[3])) : 1;
      return [ch(p[0]), ch(p[1]), ch(p[2]), isNaN(a) ? 1 : a];
    }
    if ((m = c.match(/^hsla?\(([^()]*)\)$/))) {
      var q = m[1].split(/[\s,\/]+/).filter(function (x) { return x; });
      if (q.length < 3) return null;
      var rgb = hslToRgb(parseFloat(q[0]) / 360, parseFloat(q[1]) / 100, parseFloat(q[2]) / 100);
      var al = q.length > 3 ? (/%$/.test(q[3]) ? parseFloat(q[3]) / 100 : parseFloat(q[3])) : 1;
      return rgb.concat(isNaN(al) ? 1 : al);
    }
    return null;
  }
  function rgbToHsl(r, g, b) {
    r /= 255; g /= 255; b /= 255;
    var mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, h = 0, s2 = 0, d = mx - mn;
    if (d) {
      s2 = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
      h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
      h /= 6;
    }
    return [h, s2, l];
  }
  function hslToRgb(h, s2, l) {
    function f(p, q, t) {
      if (t < 0) t += 1; if (t > 1) t -= 1;
      return t < 1 / 6 ? p + (q - p) * 6 * t : t < 1 / 2 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p;
    }
    if (!s2) return [l * 255, l * 255, l * 255];
    var q = l < 0.5 ? l * (1 + s2) : l + s2 - l * s2, p = 2 * l - q;
    return [f(p, q, h + 1 / 3) * 255, f(p, q, h) * 255, f(p, q, h - 1 / 3) * 255];
  }

  // Light surfaces become dark, dark ink becomes light; already-suitable colours are kept.
  function darkColor(rgba, role) {
    var hsl = rgbToHsl(rgba[0], rgba[1], rgba[2]), h = hsl[0], sat = hsl[1], l = hsl[2], l2 = l;
    if (role === 'bg') { if (l < 0.35) return null; l2 = 0.09 + (1 - l) * 0.3; sat *= 0.8; }
    else if (role === 'border') { if (l < 0.35) return null; l2 = 0.2 + (1 - l) * 0.35; sat *= 0.8; }
    else if (role === 'fg') { if (l > 0.6) return null; l2 = 0.9 - l * 0.4; }
    else { l2 = 0.9 - l * 0.8; }
    var c = hslToRgb(h, sat, l2), a = rgba[3];
    // Frosted surfaces (translucent headers) can't blur here: make them nearly opaque instead.
    if (role === 'bg' && !BLUR && a >= 0.5 && a < 1) a = 0.96;
    return rgbaString(c[0] / 255, c[1] / 255, c[2] / 255, a);
  }

  function darkValue(v, role) {
    var urls = [];
    // Shield url(...) and custom property names (var(--color-white)) from colour-name matching.
    v = v.replace(/url\([^)]*\)|--[\w-]+/gi, function (m) { urls.push(m); return '\u0001' + (urls.length - 1) + '\u0001'; });
    v = v.replace(COLOR_TOKEN, function (tok) {
      var c = parseColor(tok);
      if (!c) return tok;
      var out = darkColor(c, role);
      return out || tok;
    });
    return v.replace(/\u0001(\d+)\u0001/g, function (m, i) { return urls[+i]; });
  }

  function roleOf(p) {
    if (/^(background|background-color|background-image)$/.test(p)) return 'bg';
    if (/^(color|-webkit-text-fill-color|caret-color|fill|stroke|text-decoration-color|-webkit-text-stroke-color|text-emphasis-color)$/.test(p)) return 'fg';
    if (/^(border|outline|column-rule)(-|$)/.test(p) && !/(width|style|radius|image|collapse|spacing|offset)$/.test(p)) return 'border';
    return null;
  }

  // Custom properties: darken by how the page actually uses them (bg vs text vs border).
  function noteVarRoles(nodes) {
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.children) { noteVarRoles(n.children); continue; }
      if (!n.body || n.body.indexOf('var(') < 0) continue;
      var ds = decls(n.body);
      for (var j = 0; j < ds.length; j++) {
        var role = roleOf(ds[j].p.toLowerCase());
        if (!role) continue;
        var m, re = /var\(\s*(--[\w-]+)/g;
        while ((m = re.exec(ds[j].v))) {
          var r = varRoles[m[1]] || (varRoles[m[1]] = { bg: 0, fg: 0, border: 0 });
          r[role]++;
        }
      }
    }
  }
  function varRole(name) {
    var r = varRoles[name];
    if (!r) return 'any';
    return r.fg > r.bg && r.fg >= r.border ? 'fg' : r.border > r.bg ? 'border' : 'bg';
  }

  var PREFIX = { transform: 1, 'transform-origin': 1, 'transform-style': 1, perspective: 1, 'perspective-origin': 1,
    'backface-visibility': 1, animation: 1, 'animation-name': 1, 'animation-duration': 1, 'animation-delay': 1,
    'animation-fill-mode': 1, 'animation-iteration-count': 1, 'animation-timing-function': 1,
    'animation-direction': 1, 'animation-play-state': 1, filter: 1, 'user-select': 1, 'mask-image': 1,
    'backdrop-filter': 1, 'text-size-adjust': 1, 'box-decoration-break': 1, hyphens: 1 };

  function sides(v) {
    var p = splitTop(v.trim(), ' ').filter(function (x) { return x; });
    return p.length === 1 ? [p[0], p[0]] : [p[0], p[1]];
  }

  // Returns rewritten declarations (array of "prop:value") plus layout facts for this rule.
  function rewriteDecls(list, facts) {
    var local = {}, out = [], i, d;
    for (i = 0; i < list.length; i++) {
      if (list[i].p.slice(0, 2) === '--') local[list[i].p] = list[i].v;
      if (/background-clip$/i.test(list[i].p) && /\btext\b/.test(list[i].v)) facts.clipText = true;
    }
    for (i = 0; i < list.length; i++) {
      d = list[i];
      var p = d.p.toLowerCase(), v = d.p.slice(0, 2) === '--' ? d.v : rewriteValue(d.v, local), imp = d.imp;
      if (v === null) continue;
      if (p.slice(0, 2) === '--') {
        if (DARK && (F.vars || facts.inline)) v = darkValue(COLOR_OK ? v : rewriteColors(v), varRole(d.p));
        out.push(d.p + ':' + v + imp);
        continue;
      }
      if (DARK) {
        var role = roleOf(p);
        // Gradient text: the "background" is really the text colour.
        if (role === 'bg' && facts.clipText) role = 'fg';
        if (role) v = darkValue(v, role);
      }
      var push = function (pp, vv) { out.push(pp + ':' + vv + imp); };
      switch (F.inset ? '' : p) {
        case 'inset': {
          var s = splitTop(v.trim(), ' ').filter(function (x) { return x; });
          var t = s[0], r = s[1] || t, b = s[2] || t, l = s[3] || r;
          push('top', t); push('right', r); push('bottom', b); push('left', l);
          continue;
        }
      }
      switch (F.logical ? '' : p) {
        case 'inset-inline': { var ii = sides(v); push('left', ii[0]); push('right', ii[1]); continue; }
        case 'inset-block': { var ib = sides(v); push('top', ib[0]); push('bottom', ib[1]); continue; }
        case 'inset-inline-start': push('left', v); continue;
        case 'inset-inline-end': push('right', v); continue;
        case 'inset-block-start': push('top', v); continue;
        case 'inset-block-end': push('bottom', v); continue;
      }
      switch (F.place ? '' : p) {
        case 'place-items': { var pi = sides(v); push('align-items', pi[0]); push('justify-items', pi[1]); facts.placeCenter = /center/.test(v); continue; }
        case 'place-content': { var pc = sides(v); push('align-content', pc[0]); push('justify-content', pc[1]); continue; }
        case 'place-self': { var ps = sides(v); push('align-self', ps[0]); continue; }
      }
      if (p === 'aspect-ratio' && !F.aspect) continue;
      if (p === 'position' && !F.sticky && /sticky/.test(v)) { push('position', '-webkit-sticky'); push('position', 'relative'); continue; }
      if ((p === 'translate' || p === 'scale' || p === 'rotate') && F.indiv) { push(d.p, v); continue; }
      if (F.grid && /^(display|grid-template-columns|gap|grid-gap|row-gap|grid-row-gap|column-gap|grid-column-gap)$/.test(p)) {
        // Native grid: keep the declaration; only flex gap may still need emulating.
        if (!F.flexGap && /gap$/.test(p)) {
          if (p === 'gap' || p === 'grid-gap') { var fg2 = sides(v); facts.rowGap = fg2[0]; facts.colGap = fg2[1]; }
          else if (/column/.test(p)) facts.colGap = v; else facts.rowGap = v;
        }
        if (p === 'display' && /^(inline-)?flex$/.test(v.trim()) && !F.flex) push('display', '-webkit-' + v.trim());
        push(d.p, v);
        continue;
      }
      switch (p) {
        case 'display':
          if (/^(inline-)?grid$/.test(v.trim())) { facts.grid = true; push('display', '-webkit-flex'); push('display', 'flex'); push('-webkit-flex-wrap', 'wrap'); push('flex-wrap', 'wrap'); continue; }
          if (/^(inline-)?flex$/.test(v.trim())) push('display', '-webkit-' + v.trim());
          if (/contents/.test(v)) continue;
          break;
        case 'grid-template-columns': {
          var rep = v.match(/repeat\(\s*(\d+)\s*,/), cols;
          if (rep) cols = +rep[1];
          else if (/auto-(fill|fit)/.test(v)) cols = 0;
          else {
            var tracks = splitTop(v.trim(), ' ').filter(function (x) { return x && !/^\[/.test(x); });
            cols = tracks.length;
            if (tracks.some(function (x) { return !/^(minmax\(0,\s*)?1fr\)?$/.test(x); })) facts.tracks = tracks;
          }
          facts.cols = cols;
          continue;
        }
        case 'gap': { var g = sides(v); facts.rowGap = g[0]; facts.colGap = g[1]; continue; }
        case 'grid-gap': { var gg = sides(v); facts.rowGap = gg[0]; facts.colGap = gg[1]; continue; }
        case 'column-gap': case 'grid-column-gap': facts.colGap = v; continue;
        case 'row-gap': case 'grid-row-gap': facts.rowGap = v; continue;
        case 'translate': push('-webkit-transform', 'translate(' + splitTop(v.trim(), ' ').join(',') + ')'); continue;
        case 'scale': push('-webkit-transform', 'scale(' + splitTop(v.trim(), ' ').join(',') + ')'); continue;
        case 'rotate': push('-webkit-transform', 'rotate(' + v.trim() + ')'); continue;
      }
      var m = F.logical ? null : p.match(/^(margin|padding|border)-(inline|block)(-(start|end))?(-(width|style|color))?$/);
      if (m) {
        var axis = m[2] === 'inline' ? ['left', 'right'] : ['top', 'bottom'];
        var suffix = m[5] || '', which = m[4];
        if (which) push(m[1] + '-' + (which === 'start' ? axis[0] : axis[1]) + suffix, v);
        else { var lr = sides(v); push(m[1] + '-' + axis[0] + suffix, lr[0]); push(m[1] + '-' + axis[1] + suffix, lr[1]); }
        continue;
      }
      if (!F.flex && (p === 'flex' || p.indexOf('flex-') === 0 || p === 'order' || p === 'align-items' || p === 'align-self' ||
          p === 'align-content' || p === 'justify-content')) push('-webkit-' + p, v);
      if (!F.transform && PREFIX[p]) push('-webkit-' + p, v.replace(/\btransform\b/g, '-webkit-transform'));
      if (!F.transform && (p === 'transition' || p === 'transition-property')) push('-webkit-' + p, v.replace(/\btransform\b/g, '-webkit-transform'));
      push(d.p, v);
    }
    return out;
  }

  // ------------------------------------------------------------ selectors & at-rules

  var BAD_SELECTOR = /:(has|host|part|slotted|focus-within|placeholder-shown|is\(|where\(|dir\(|lang\([^)]*,|nth-[a-z-]+\([^)]*\bof\b)|::(part|slotted|backdrop|file-selector-button|marker|selection|placeholder)|&/i;

  function expandIs(sel) {
    var guard = 0, m;
    while ((m = sel.match(/:(is|where|matches|-webkit-any)\(/i)) && guard++ < 4) {
      var k = m.index, j = k + m[0].length, d = 1;
      while (j < sel.length && d > 0) { var c = sel.charAt(j); if (c === '(') d++; else if (c === ')') d--; j++; }
      var opts = splitTop(sel.slice(k + m[0].length, j - 1), ',');
      var pre = sel.slice(0, k), post = sel.slice(j);
      sel = opts.map(function (o) { return pre + o.trim() + post; }).join(',');
    }
    return sel;
  }

  function rewriteSelector(prelude) {
    if (F.is && F.has && F.focusVisible && F.focusWithin && F.notList) return prelude;
    var list = splitTop(expandIs(prelude), ','), keep = [], i, s;
    for (i = 0; i < list.length; i++) {
      s = list[i].trim()
        .replace(/:focus-visible/g, ':focus')
        .replace(/:not\(([^()]*,[^()]*)\)/g, function (m, inner) {
          return inner.split(',').map(function (x) { return ':not(' + x.trim() + ')'; }).join('');
        })
        .replace(/::?-moz-[a-z-]+/g, '\u0000');
      if (s && !BAD_SELECTOR.test(s) && s.indexOf('\u0000') < 0) keep.push(s);
    }
    return keep.join(',');
  }

  function rewriteMedia(prelude) {
    if (F.range) return prelude;
    return prelude
      .replace(/\(\s*([^()<>=]+?)\s*(<=|<)\s*(width|height)\s*(<=|<)\s*([^)]+)\)/g, function (m, lo, op1, dim, op2, hi) {
        return '(min-' + dim + ':' + lo.trim() + ') and (max-' + dim + ':' + hi.trim() + ')';
      })
      .replace(/\(\s*(width|height)\s*(>=|>)\s*([^)]+)\)/g, function (m, dim, op, val) { return '(min-' + dim + ':' + val.trim() + ')'; })
      .replace(/\(\s*(width|height)\s*(<=|<)\s*([^)]+)\)/g, function (m, dim, op, val) {
        val = val.trim();
        if (op === '<') { var n = parseFloat(val), u = val.replace(/^[\d.]+/, ''); val = (n - 0.02) + u; }
        return '(max-' + dim + ':' + val + ')';
      })
;
  }

  function collectVars(nodes) {
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.children) { collectVars(n.children); continue; }
      if (!n.body || n.body.indexOf('--') < 0) continue;
      var root = splitTop(n.prelude, ',').some(function (s) { return VAR_ROOT.test(s.trim()); });
      var ds = decls(n.body);
      for (var j = 0; j < ds.length; j++) {
        if (ds[j].p.slice(0, 2) !== '--') continue;
        if (root || !globals.hasOwnProperty(ds[j].p)) globals[ds[j].p] = ds[j].v;
      }
    }
  }

  function serialize(nodes, media) {
    var out = [], i;
    for (i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      if (n.stmt) { if (F.layer || !/^@layer\b/i.test(n.stmt)) out.push(n.stmt + ';'); continue; }
      if (n.children) {
        var pre = n.prelude;
        if ((!F.layer && /^@(layer|scope|starting-style)\b/i.test(pre)) || (!F.container && /^@container\b/i.test(pre))) {
          if (/^@starting-style/i.test(pre)) continue;
          out.push(serialize(n.children, media));
        } else if (/^@media\b/i.test(pre)) {
          var mq = rewriteMedia(pre);
          out.push(mq + '{' + serialize(n.children, (media ? media + ' and ' : '') + mq.replace(/^@media\s*/i, '')) + '}');
        } else {
          out.push(pre + '{' + serialize(n.children, media) + '}');
        }
        continue;
      }
      var p = n.prelude;
      if (!F.layer && /^@(property|font-feature-values|counter-style)\b/i.test(p)) continue;
      if (/^@(-webkit-)?keyframes\b/i.test(p)) {
        var kf = p.replace(/^@(-webkit-)?keyframes/i, '');
        var inner = parse(n.body).map(function (f) {
          return f.prelude + '{' + rewriteDecls(decls(f.body || ''), {}).join(';') + '}';
        }).join('');
        out.push((F.animation ? '' : '@-webkit-keyframes' + kf + '{' + inner + '}') + '@keyframes' + kf + '{' + inner + '}');
        continue;
      }
      if (p.charAt(0) === '@') { out.push(p + '{' + n.body + '}'); continue; }
      var sel = rewriteSelector(p);
      if (!sel) continue;
      var facts = {};
      var body = rewriteDecls(decls(n.body), facts);
      if (DARK && facts.clipText) clipTextSels.push(sel);
      if ((!F.grid && (facts.grid || facts.cols !== undefined)) || facts.colGap || facts.rowGap) {
        layouts.push({ sel: sel, media: media || '', grid: facts.grid, cols: facts.cols, colGap: facts.colGap,
          rowGap: facts.rowGap, center: facts.placeCenter, tracks: facts.tracks });
      }
      if (body.length) out.push(sel + '{' + body.join(';') + '}');
    }
    return out.join('\n');
  }

  function absolutizeUrls(css, base) {
    if (!base) return css;
    var a = document.createElement('a');
    return css.replace(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g, function (m, q, u) {
      if (/^(data:|https?:|\/\/|#)/i.test(u)) return m;
      var origin = (base.match(/^[a-z]+:\/\/[^\/]+/i) || [''])[0];
      a.href = u.charAt(0) === '/' ? origin + u : base.replace(/[^\/?#]*([?#].*)?$/, '') + u;
      return 'url("' + a.href + '")';
    });
  }

  // ------------------------------------------------------------ stylesheets

  function fetchCss(href, cb) {
    var x = new XMLHttpRequest();
    try {
      x.open('GET', href, true);
      x.onload = function () { cb(x.status >= 200 && x.status < 300 ? x.responseText : null); };
      x.onerror = function () { nativeFetch(href, cb); };
      x.send();
    } catch (e) { nativeFetch(href, cb); }
  }

  // Cross-origin sheets the page can't read: ask Ghostbrowser to fetch them (public CSS only).
  function nativeFetch(href, cb) {
    pendingFetch[href] = cb;
    try { window.prompt('ghostcompat:fetch', href); } catch (e) { delete pendingFetch[href]; cb(null); }
  }
  window.__ghostCssDone = function (href, text) {
    var cb = pendingFetch[href];
    delete pendingFetch[href];
    if (cb) cb(text);
  };

  function needsWork(css) {
    if (DARK) return true;
    if (MODERN_CSS) return false;
    return /var\(|@layer|oklch|oklab|\binset\s*:|-(inline|block)\b|clamp\(|min\(|max\(|display\s*:\s*(inline-)?grid|\bgap\s*:|:is\(|:where\(|\d+\s+\d+\s+\d+\s*\/|(width|height)\s*[<>]=?|\btransform\s*:|\banimation\s*:|@keyframes|#[0-9a-f]{8}\b|[ds]vh/i.test(css);
  }

  function processSheets(done) {
    var items = [], i;
    var styles = document.querySelectorAll('style:not([data-ghost]), link[rel~="stylesheet"]:not([data-ghost])');
    for (i = 0; i < styles.length; i++) {
      var el = styles[i];
      el.setAttribute('data-ghost', '1');
      if (el.tagName === 'STYLE') items.push({ el: el, css: el.textContent, base: location.href });
      else if (el.href && !/^print$/i.test(el.media)) items.push({ el: el, href: el.href, base: el.href });
    }
    if (!items.length) { done(); return; }
    var left = items.length;
    function oneDone() { if (--left === 0) finish(); }
    items.forEach(function (it) {
      if (it.css !== undefined) { oneDone(); return; }
      fetchCss(it.href, function (text) { it.css = text; oneDone(); });
    });
    function finish() {
      var parsed = [];
      items.forEach(function (it) {
        if (!it.css || !needsWork(it.css)) return;
        it.nodes = parse(stripComments(absolutizeUrls(it.css, it.base)));
        collectVars(it.nodes);
        if (DARK) noteVarRoles(it.nodes);
        parsed.push(it);
      });
      parsed.forEach(function (it) {
        var css = serialize(it.nodes, '');
        var st = document.createElement('style');
        st.setAttribute('data-ghost', 'out');
        if (it.el.media && it.el.media !== 'all') st.media = it.el.media;
        st.textContent = css;
        it.el.parentNode.insertBefore(st, it.el.nextSibling);
        if (it.el.tagName === 'STYLE') it.el.textContent = ''; else it.el.disabled = true;
      });
      done();
    }
  }

  // ------------------------------------------------------------ inline styles

  function processInline() {
    if (MODERN_CSS && !DARK) return;
    var els = document.querySelectorAll('[style*="var("],[style*="inset"],[style*="-inline"],[style*="-block"],[style*="oklch"],[style*="clamp("],[style*="display:grid"],[style*="display: grid"],[style*="transform"],[style*="gap"]' +
        (DARK ? ',[style*="color"],[style*="background"],[style*="border"],[style*="fill"]' : ''));
    for (var i = 0; i < els.length && i < 3000; i++) {
      var el = els[i], raw = el.getAttribute('style');
      if (el.__ghostStyle === raw) continue;
      var facts = { inline: true };
      if (DARK && (el.__ghostClip || /background-clip\s*:\s*text/.test(raw) ||
          getComputedStyle(el).webkitBackgroundClip === 'text')) facts.clipText = true;
      var out = rewriteDecls(decls(raw), facts).join(';');
      el.setAttribute('style', out);
      el.__ghostStyle = out;
      if (facts.grid || facts.cols !== undefined) { el.__ghostGrid = true; if (facts.cols !== undefined) { el.__ghostCols = facts.cols; el.__ghostTracks = facts.tracks; } }
      if (facts.colGap) el.__ghostColGap = facts.colGap;
      if (facts.rowGap) el.__ghostRowGap = facts.rowGap;
    }
  }

  // ------------------------------------------------------------ grid & gap emulation

  function matches(media) {
    if (!media) return true;
    try { return window.matchMedia(media).matches; } catch (e) { return false; }
  }

  function applyLayouts() {
    if (F.grid && F.flexGap) return;
    var touched = [], i, j;
    for (i = 0; i < layouts.length; i++) {
      var L = layouts[i];
      if (!matches(L.media)) continue;
      var els;
      try { els = document.querySelectorAll(L.sel); } catch (e) { continue; }
      for (j = 0; j < els.length && j < 2000; j++) {
        var el = els[j];
        if (!el.__ghostLayout) { el.__ghostLayout = 1; touched.push(el); }
        if (L.grid) el.__ghostGrid = true;
        if (L.cols !== undefined) { el.__ghostCols = L.cols; el.__ghostTracks = L.tracks; }
        if (L.colGap) el.__ghostColGap = L.colGap;
        if (L.rowGap) el.__ghostRowGap = L.rowGap;
        if (L.center) el.__ghostCenter = true;
      }
    }
    var marked = document.querySelectorAll('*');
    for (i = 0; i < touched.length; i++) layoutOne(touched[i]);
    for (i = 0; i < marked.length && i < 20000; i++) if (marked[i].__ghostStyle && !marked[i].__ghostLayout) layoutOne(marked[i]);
  }

  function kids(el) {
    var out = [], c = el.children;
    for (var i = 0; i < c.length; i++) {
      var cs = getComputedStyle(c[i]);
      if (cs.display !== 'none' && cs.position !== 'absolute' && cs.position !== 'fixed') out.push(c[i]);
    }
    return out;
  }

  function layoutOne(el) {
    var cs = getComputedStyle(el), k = kids(el), i;
    var colGap = el.__ghostColGap || '0px', rowGap = el.__ghostRowGap || '0px';
    if (el.__ghostGrid) {
      var cols = el.__ghostCols;
      if (!cols) {
        // auto-fill/auto-fit or unknown: fit as many ~260px columns as the width allows
        cols = cols === 0 ? Math.max(1, Math.floor(el.clientWidth / 260)) : 1;
      }
      el.style.display = 'flex';
      el.style.flexWrap = 'wrap';
      el.style.webkitFlexWrap = 'wrap';
      if (cols === 1 && el.__ghostCenter) {
        el.style.flexDirection = 'column';
        el.style.webkitFlexDirection = 'column';
        el.style.alignItems = 'center';
        el.style.justifyContent = 'center';
      }
      var tracks = el.__ghostTracks;
      if (tracks && k.length <= tracks.length) {
        // Mixed track sizes (e.g. "auto 1fr auto"): one row, fr tracks grow, the rest keep their size.
        // Absolutely positioned children still occupy their track index; if one sat in an fr track,
        // push the following items to the far side instead.
        el.style.flexWrap = 'nowrap';
        el.style.webkitFlexWrap = 'nowrap';
        if (!el.style.alignItems) el.style.alignItems = 'center';
        var all = el.children, idx = 0, push = false;
        for (i = 0; i < all.length; i++) {
          var cc = all[i], ccs = getComputedStyle(cc);
          if (ccs.display === 'none') continue;
          var tr = tracks[idx++] || 'auto', fr = tr.match(/^(?:minmax\([^,]*,\s*)?([\d.]+)fr\)?$/);
          if (ccs.position === 'absolute' || ccs.position === 'fixed') { if (fr) push = true; continue; }
          cc.style.boxSizing = 'border-box';
          cc.style.marginRight = idx < k.length ? colGap : '0';
          if (push) { cc.style.marginLeft = 'auto'; push = false; }
          if (fr) { cc.style.webkitFlex = fr[1] + ' 1 0px'; cc.style.flex = fr[1] + ' 1 0px'; cc.style.minWidth = '0'; }
          else if (/^(auto|min-content|max-content|fit-content)/.test(tr)) { cc.style.webkitFlex = '0 0 auto'; cc.style.flex = '0 0 auto'; }
          else { cc.style.webkitFlex = '0 0 auto'; cc.style.flex = '0 0 auto'; cc.style.width = tr.replace(/^minmax\(([^,]*),.*$/, '$1'); }
        }
        return;
      }
      for (i = 0; i < k.length; i++) {
        var c = k[i];
        c.style.boxSizing = 'border-box';
        if (cols > 1 || !el.__ghostCenter) {
          c.style.width = 'calc((100% - ' + (cols - 1) + ' * ' + colGap + ') / ' + cols + ')';
          c.style.marginRight = (i % cols === cols - 1) ? '0' : colGap;
        }
        c.style.marginBottom = (i < k.length - (k.length % cols || cols)) ? rowGap : '0';
      }
      return;
    }
    if (!/flex/.test(cs.display) || (!el.__ghostColGap && !el.__ghostRowGap)) return;
    var dir = cs.webkitFlexDirection || cs.flexDirection || 'row';
    var wrap = /wrap/.test(cs.webkitFlexWrap || cs.flexWrap || '');
    for (i = 0; i < k.length; i++) {
      if (/column/.test(dir)) { if (i > 0) k[i].style.marginTop = rowGap; }
      else {
        if (i > 0) k[i].style.marginLeft = colGap;
        if (wrap) k[i].style.marginBottom = rowGap;
      }
    }
  }

  // ------------------------------------------------------------ reveal JS-gated content

  function reveal() {
    var html = document.documentElement;
    html.className = html.className.replace(/\b(async-hide|no-js-hide|js-loading|is-loading)\b/g, '');
    var gated = document.querySelectorAll('[v-cloak],[x-cloak],[ng-cloak],[data-v-cloak],.aos-init,[data-aos]');
    for (var i = 0; i < gated.length; i++) {
      gated[i].removeAttribute('v-cloak'); gated[i].removeAttribute('x-cloak'); gated[i].removeAttribute('ng-cloak');
      if (gated[i].hasAttribute('data-aos')) gated[i].className += ' aos-animate';
    }
    // Server-rendered content hidden with inline styles until hydration (anti-flicker wrappers,
    // fade-in animations). Only when the page's scripts are modules this engine can't run;
    // otherwise those inline styles may be live dropdowns and dialogs.
    var modular = document.querySelector('script[type="module"]') && !document.querySelector('script[nomodule]');
    // With Ghostbrowser's JavaScript runtime on the page, the scripts do run: leave their UI alone.
    var jsDead = modular && (!F.modules || !F.syntax) && !window.__ghostJs;
    var hidden = document.querySelectorAll(jsDead ? '[style*="visibility"],[style*="opacity"],[aria-busy="true"]' : '[aria-busy="true"]');
    for (i = 0; i < hidden.length; i++) {
      var el = hidden[i], st = el.style;
      var big = el.offsetHeight > 40 || (el.textContent || '').replace(/\s+/g, '').length > 20;
      if (!big) continue;
      if (st.visibility === 'hidden') st.visibility = 'visible';
      if (st.opacity === '0') { st.opacity = '1'; st.webkitTransform = 'none'; st.transform = 'none'; }
      if (st.pointerEvents === 'none') st.pointerEvents = 'auto';
      if (el.getAttribute('aria-busy') === 'true') el.removeAttribute('aria-busy');
    }
    // Lazy-loaded media whose loader script never ran: an element without a real source shows
    // nothing anyway, so filling it in is always safe.
    var lazy = document.querySelectorAll('img[data-src],img[data-lazy-src],img[data-original],img[data-srcset],source[data-srcset],iframe[data-src],video[data-src],[data-bg],[data-background-image]');
    for (i = 0; i < lazy.length; i++) {
      var m = lazy[i], src = m.getAttribute('data-src') || m.getAttribute('data-lazy-src') || m.getAttribute('data-original');
      if (src && (!m.getAttribute('src') || /^data:|blank|placeholder|1x1/.test(m.getAttribute('src')))) m.setAttribute('src', src);
      var ss = m.getAttribute('data-srcset');
      if (ss && !m.getAttribute('srcset')) m.setAttribute('srcset', ss);
      var bg = m.getAttribute('data-bg') || m.getAttribute('data-background-image');
      if (bg && !m.style.backgroundImage) m.style.backgroundImage = /^url\(/.test(bg) ? bg : 'url("' + bg + '")';
    }
    var b = document.body;
    if (b && (b.style.display === 'none' || b.style.opacity === '0' || b.style.visibility === 'hidden')) {
      b.style.display = ''; b.style.opacity = '1'; b.style.visibility = 'visible';
    }
  }

  // ------------------------------------------------------------ dark mode page scaffolding

  function luminance(c) { var x = parseColor(c); return x && x[3] > 0.5 ? rgbToHsl(x[0], x[1], x[2])[2] : null; }

  // Pages that are dark already (or that set their own dark scheme) are left as they are.
  function pageIsDark() {
    var b = document.body, cands = [document.documentElement, b], i;
    for (i = 0; i < cands.length; i++) {
      if (!cands[i]) continue;
      var l = luminance(getComputedStyle(cands[i]).backgroundColor);
      if (l !== null) return l < 0.3;
    }
    return false;
  }

  function darkBase() {
    if (document.getElementById('ghost-dark-base')) return;
    var st = document.createElement('style');
    st.id = 'ghost-dark-base';
    st.setAttribute('data-ghost', 'base');
    // Element-level selectors only, inserted first: any page rule wins, and is darkened itself.
    st.textContent = 'html{background-color:#1a1c22;color:#e6e7eb}' +
        'a{color:#8e9eff}a:visited{color:#b4a7f5}' +
        'input,textarea,select,button{background-color:#23262e;color:#e6e7eb;border-color:#3a3d47}' +
        '::-webkit-input-placeholder{color:#9a9ca8}' +
        'img,video,canvas,picture,svg image{filter:none}';
    var head = document.head || document.documentElement;
    head.insertBefore(st, head.firstChild);
  }

  // Text clip set in one rule and the gradient in another (Tailwind bg-clip-text + bg-gradient-*):
  // lighten whatever gradient those elements ended up with.
  function fixClipText() {
    for (var i = 0; i < clipTextSels.length; i++) {
      var els;
      try { els = document.querySelectorAll(clipTextSels[i]); } catch (e) { continue; }
      for (var j = 0; j < els.length && j < 500; j++) {
        var el = els[j];
        if (el.__ghostClip) continue;
        el.__ghostClip = 1;
        var bi = getComputedStyle(el).backgroundImage;
        if (bi && bi !== 'none') el.style.backgroundImage = darkValue(bi, 'fg');
        el.__ghostStyle = el.getAttribute('style'); // our own write: don't reprocess it
      }
    }
  }

  function darkSvg() {
    var els = document.querySelectorAll('svg [fill],svg[fill],svg [stroke],svg[stroke]');
    for (var i = 0; i < els.length && i < 4000; i++) {
      var el = els[i];
      if (el.__ghostSvg) continue;
      el.__ghostSvg = 1;
      ['fill', 'stroke'].forEach(function (a) {
        var v = el.getAttribute(a);
        if (!v || v === 'none' || v === 'currentColor') return;
        var c = parseColor(v);
        if (c && rgbToHsl(c[0], c[1], c[2])[2] < 0.3) el.setAttribute(a, darkColor(c, 'fg') || v);
      });
    }
  }

  // ------------------------------------------------------------ driver

  function run() {
    if (running) return;
    if (DARK && document.body && !document.getElementById('ghost-dark-base') && pageIsDark()) DARK = false;
    if (DARK) darkBase();
    if (!F.flexGap && document.body) F.flexGap = !COMPAT || flexGapOk();
    running = true;
    try { reveal(); } catch (e) {}
    processSheets(function () {
      try { processInline(); applyLayouts(); reveal(); if (DARK) { darkSvg(); fixClipText(); } } catch (e) {}
      running = false;
    });
  }

  window.__ghostCompat = { run: run };
  run();
  setTimeout(run, 1500);
  setTimeout(run, 5000);
  var t = null;
  window.addEventListener('resize', function () { clearTimeout(t); t = setTimeout(applyLayouts, 300); }, false);
  if (window.MutationObserver) {
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        for (var j = 0; j < muts[i].addedNodes.length; j++) {
          var n = muts[i].addedNodes[j];
          if (n.nodeType === 1 && (n.tagName === 'STYLE' || n.tagName === 'LINK')) { clearTimeout(t); t = setTimeout(run, 200); return; }
        }
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }
})();
