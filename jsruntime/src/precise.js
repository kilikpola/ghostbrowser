// Precise translation for the features Sucrase leaves behind. Instead of handing a whole
// (possibly multi-megabyte) module to Babel, it scans tokens with acorn's tokenizer, which builds
// no syntax tree, and rewrites only the exact spots that need it:
//
//   before Sucrase  private #fields -> ordinary names; static {} blocks -> static initialisers
//   after Sucrase   a ||= b / &&= / ??=; regex \p{…}, named groups, dotAll, lookbehind;
//                   for await (…); export * as ns; async generators (only that function goes to Babel)
import { tokenizer } from 'acorn';

// Token kinds
var NAME = 1, PRIV = 2, REGEX = 3, LOGIC_ASSIGN = 4, LP = 5, RP = 6, LB = 7, RB = 8, LC = 9, RC = 10,
  DOLLAR_BRACE = 11, SEMI = 12, COMMA = 13, DOT = 14, QDOT = 15, COLON = 16, QUESTION = 17, STAR = 18,
  FUNCTION = 19, THIS = 20, FOR = 21, STRING = 22, TEMPLATE_END = 23, IN = 24, SUPER = 25, KEYWORD = 26, OTHER = 27,
  ARROW = 28, IMPORT = 29, EXPORT = 30;

function kindOf(t) {
  var l = t.type.label;
  switch (l) {
    case 'name': return NAME;
    case 'privateId': return PRIV;
    case 'regexp': return REGEX;
    case '(': return LP;
    case ')': return RP;
    case '[': return LB;
    case ']': return RB;
    case '{': return LC;
    case '}': return RC;
    case '${': return DOLLAR_BRACE;
    case ';': return SEMI;
    case ',': return COMMA;
    case '.': return DOT;
    case '?.': return QDOT;
    case ':': return COLON;
    case '?': return QUESTION;
    case '*': return STAR;
    case 'function': return FUNCTION;
    case 'this': return THIS;
    case 'for': return FOR;
    case 'string': return STRING;
    case '`': return TEMPLATE_END;
    case 'in': return IN;
    case 'super': return SUPER;
    case '=>': return ARROW;
    case 'import': return IMPORT;
    case 'export': return EXPORT;
    case '_=': return /^(\|\||&&|\?\?)=$/.test(t.value) ? LOGIC_ASSIGN : OTHER;
  }
  return t.type.keyword ? KEYWORD : OTHER;
}

function lex(src) {
  var K = [], S = [], E = [];
  var it = tokenizer(src, { ecmaVersion: 'latest', sourceType: 'module', allowHashBang: true,
    allowAwaitOutsideFunction: true, allowReturnOutsideFunction: true, allowImportExportEverywhere: true });
  for (;;) {
    var t = it.getToken();
    if (t.type.label === 'eof') break;
    K.push(kindOf(t)); S.push(t.start); E.push(t.end);
  }
  // Pair up brackets.
  var M = new Array(K.length), stack = [];
  for (var i = 0; i < K.length; i++) {
    var k = K[i];
    if (k === LP || k === LB || k === LC || k === DOLLAR_BRACE) stack.push(i);
    else if ((k === RP || k === RB || k === RC) && stack.length) { var o = stack.pop(); M[o] = i; M[i] = o; }
  }
  return { K: K, S: S, E: E, M: M, src: src, text: function (i) { return src.slice(S[i], E[i]); } };
}

function applyEdits(src, edits) {
  // Keep outermost non-overlapping edits; nested ones are picked up by another pass.
  edits.sort(function (a, b) { return a.s - b.s || b.e - a.e; });
  var kept = [], end = -1;
  for (var i = 0; i < edits.length; i++) if (edits[i].s >= end) { kept.push(edits[i]); end = edits[i].e; }
  var out = '', pos = 0;
  for (var j = 0; j < kept.length; j++) { out += src.slice(pos, kept[j].s) + kept[j].r; pos = kept[j].e; }
  return out + src.slice(pos);
}

// ------------------------------------------------------------------ before Sucrase

var PRE_NEEDED = /#[a-zA-Z_$]|\bstatic\s*\{|\bsuper\([^()]*\),/;

export function prePass(src) {
  if (!PRE_NEEDED.test(src)) return src;
  var x;
  try { x = lex(src); } catch (e) { return src; }
  var K = x.K, S = x.S, E = x.E, edits = [], n = 0;
  for (var i = 0; i < K.length; i++) {
    if (K[i] === PRIV) {
      var name = '__ghost_p_' + x.text(i).slice(1);
      // Minified code writes `async#m(` with no space; keep `async` a separate word.
      var glued = S[i] > 0 && /[\w$]/.test(src.charAt(S[i] - 1));
      // `#x in obj` is a brand check on the private name: test the renamed property instead.
      edits.push({ s: S[i], e: E[i], r: (glued ? ' ' : '') + (K[i + 1] === IN ? JSON.stringify(name) : name) });
    } else if (K[i] === SUPER && (K[i - 1] === LC || K[i - 1] === SEMI) && K[i + 1] === LP && x.M[i + 1] != null &&
        K[x.M[i + 1] + 1] === COMMA) {
      // `super(…),this.x=…` (minified constructor): Sucrase inserts field setup after super()
      // and would leave a stray comma. As a statement, `super(…);this.x=…` means the same.
      var comma = x.M[i + 1] + 1;
      edits.push({ s: S[comma], e: E[comma], r: ';' });
    } else if (K[i] === NAME && x.text(i) === 'static' && K[i + 1] === LC && x.M[i + 1] != null) {
      var close = x.M[i + 1];
      edits.push({ s: S[i], e: E[i + 1], r: 'static __ghost_sb' + (n++) + ' = (() => {' });
      edits.push({ s: S[close], e: E[close], r: '})();' });
    }
  }
  return edits.length ? applyEdits(src, edits) : src;
}

/** Repairs Sucrase output on minified input, where it can glue keywords together ("returnawait"). */
export function fixSucrase(out) {
  return out.replace(/\b(return|throw|typeof|void|delete|case|yield|await|new|in|of|else|do)(await\b|_(?:async|optional|nullish))/g, '$1 $2');
}

// ------------------------------------------------------------------ regex rewriting

var P = {
  L: 'A-Za-z\\xAA\\xB5\\xBA\\xC0-\\xD6\\xD8-\\xF6\\xF8-\\u02C1\\u02C6-\\u02D1\\u02E0-\\u02E4\\u0370-\\u0373\\u0376\\u0377\\u037A-\\u037D\\u0386-\\u03FF\\u0400-\\u0481\\u048A-\\u052F\\u0531-\\u0556\\u0561-\\u0587\\u05D0-\\u05EA\\u0620-\\u064A\\u066E-\\u06D3\\u0710-\\u072F\\u0780-\\u07A5\\u0904-\\u0939\\u0980-\\u09B9\\u0A05-\\u0A39\\u0A85-\\u0AB9\\u0B05-\\u0B39\\u0B85-\\u0BB9\\u0C05-\\u0C39\\u0C85-\\u0CB9\\u0D05-\\u0D3A\\u0E01-\\u0E30\\u0E40-\\u0E46\\u10A0-\\u10FF\\u1100-\\u11FF\\u1E00-\\u1FFF\\u3041-\\u3096\\u30A1-\\u30FA\\u3105-\\u312F\\u3131-\\u318E\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uA000-\\uA48C\\uAC00-\\uD7A3\\uF900-\\uFAFF\\uFF21-\\uFF3A\\uFF41-\\uFF5A\\uFF66-\\uFFDC',
  Lu: 'A-Z\\xC0-\\xD6\\xD8-\\xDE\\u0391-\\u03A9\\u0410-\\u042F', Ll: 'a-z\\xDF-\\xF6\\xF8-\\xFF\\u03B1-\\u03C9\\u0430-\\u044F',
  N: '0-9\\xB2\\xB3\\xB9\\xBC-\\xBE\\u0660-\\u0669\\u06F0-\\u06F9\\u0966-\\u096F\\uFF10-\\uFF19',
  Nd: '0-9\\u0660-\\u0669\\u06F0-\\u06F9\\u0966-\\u096F\\uFF10-\\uFF19',
  P: '!-#%-*,-/:;?@\\[-\\]_{}\\xA1\\xA7\\xAB\\xB6\\xB7\\xBB\\xBF\\u2010-\\u2027\\u2030-\\u205E\\u3001-\\u3003\\u3008-\\u3011\\uFF01-\\uFF0F',
  S: '$+<->^`|~\\xA2-\\xA6\\xA8\\xA9\\xAC\\xAE-\\xB1\\xB4\\xD7\\xF7\\u2100-\\u214F\\u2190-\\u23FF\\u2500-\\u27BF',
  Z: '\\s', Cc: '\\x00-\\x1F\\x7F-\\x9F', Cf: '\\xAD\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF',
  M: '\\u0300-\\u036F\\u0483-\\u0489\\u0591-\\u05BD\\u0610-\\u061A\\u064B-\\u065F\\u0900-\\u0903\\u093A-\\u094F\\u20D0-\\u20FF\\uFE20-\\uFE2F',
  Han: '\\u2E80-\\u2FDF\\u3005\\u3007\\u3021-\\u3029\\u3038-\\u303B\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\u{20000}-\\u{2FA1F}',
  Hiragana: '\\u3041-\\u309F', Katakana: '\\u30A0-\\u30FF\\u31F0-\\u31FF\\uFF66-\\uFF9F',
  Hangul: '\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7AF', Latin: 'A-Za-z\\xC0-\\u024F\\u1E00-\\u1EFF',
  Cyrillic: '\\u0400-\\u052F', Greek: '\\u0370-\\u03FF', Arabic: '\\u0600-\\u06FF\\u0750-\\u077F',
  Hebrew: '\\u0590-\\u05FF', Thai: '\\u0E00-\\u0E7F', Devanagari: '\\u0900-\\u097F',
  Emoji: '\\xA9\\xAE\\u203C\\u2049\\u2122\\u2139\\u2194-\\u21AA\\u231A-\\u23FF\\u24C2\\u25AA-\\u27BF\\u2934\\u2935\\u2B05-\\u2B55\\u3030\\u303D\\u3297\\u3299\\u{1F000}-\\u{1FAFF}',
  Emoji_Modifier: '\\u{1F3FB}-\\u{1F3FF}', Regional_Indicator: '\\u{1F1E6}-\\u{1F1FF}', ASCII: '\\x00-\\x7F',
  Any: '\\0-\\u{10FFFF}'
};
var P_ALIAS = { Letter: 'L', Alphabetic: 'L', Alpha: 'L', Uppercase_Letter: 'Lu', Uppercase: 'Lu', Lowercase_Letter: 'Ll',
  Lowercase: 'Ll', Number: 'N', Decimal_Number: 'Nd', digit: 'Nd', Punctuation: 'P', punct: 'P', Symbol: 'S',
  Separator: 'Z', White_Space: 'Z', space: 'Z', Control: 'Cc', Format: 'Cf', Mark: 'M', Mn: 'M', Mc: 'M',
  Hani: 'Han', Extended_Pictographic: 'Emoji', Emoji_Presentation: 'Emoji', Lo: 'L', Lt: 'L', Lm: 'L', Nl: 'N', No: 'N',
  Pc: 'P', Pd: 'P', Ps: 'P', Pe: 'P', Pi: 'P', Pf: 'P', Po: 'P', Sm: 'S', Sc: 'S', Sk: 'S', So: 'S', Zs: 'Z' };

function propRanges(name) {
  name = name.replace(/^(Script(_Extensions)?|scx?|General_Category|gc)=/, '');
  return P[name] || P[P_ALIAS[name]] || null;
}

var REGEX_NEEDED = /\\[pP]\{|\(\?<|\\k</;

/**
 * Translates a regex pattern for an engine without \p{…}, named groups, lookbehind or the s flag.
 * Returns null when nothing needs changing, else { pattern, flags, names } (names: group -> index).
 */
export function translatePattern(pat, flags, label) {
  if (!REGEX_NEEDED.test(pat) && flags.indexOf('s') < 0) return null;
  var dotAll = flags.indexOf('s') >= 0, out = '', names = {}, group = 0, inClass = false, lossy = false;
  for (var i = 0; i < pat.length; i++) {
    var c = pat.charAt(i);
    if (c === '\\') {
      var nx = pat.charAt(i + 1);
      if ((nx === 'p' || nx === 'P') && pat.charAt(i + 2) === '{') {
        var close = pat.indexOf('}', i + 3), ranges = propRanges(pat.slice(i + 3, close));
        if (!ranges) { ranges = '\\S'; lossy = true; }
        if (nx === 'P') out += inClass ? '' : '[^' + ranges + ']';
        else out += inClass ? ranges : '[' + ranges + ']';
        i = close;
        continue;
      }
      if (nx === 'k' && pat.charAt(i + 2) === '<') {
        var kend = pat.indexOf('>', i), kn = pat.slice(i + 3, kend);
        out += names[kn] ? '\\' + names[kn] : '';
        i = kend;
        continue;
      }
      out += c + nx;
      i++;
      continue;
    }
    if (inClass) { if (c === ']') inClass = false; out += c; continue; }
    if (c === '[') { inClass = true; out += c; continue; }
    if (c === '.' && dotAll) { out += '[\\s\\S]'; continue; }
    if (c === '(') {
      if (pat.charAt(i + 1) !== '?') { group++; out += c; continue; }
      if (pat.charAt(i + 2) === '<' && pat.charAt(i + 3) !== '=' && pat.charAt(i + 3) !== '!') {
        var nend = pat.indexOf('>', i);
        names[pat.slice(i + 3, nend)] = ++group;
        out += '(';
        i = nend;
        continue;
      }
      if (pat.charAt(i + 2) === '<') {
        // Lookbehind can't be expressed for this engine: drop the assertion (matches a bit more).
        var depth = 0, j = i;
        for (; j < pat.length; j++) {
          var cj = pat.charAt(j);
          if (cj === '\\') { j++; continue; }
          if (cj === '(') depth++;
          else if (cj === ')' && --depth === 0) break;
        }
        i = j;
        lossy = true;
        continue;
      }
    }
    out += c;
  }
  if (lossy) try { console.warn('Ghostbrowser: approximated regex ' + (label || pat).slice(0, 60)); } catch (e) {}
  return { pattern: out, flags: flags.replace('s', ''), names: Object.keys(names).length ? names : null };
}

// Returns null when the literal can stay as it is.
function rewriteRegex(lit) {
  var slash = lit.lastIndexOf('/'), t = translatePattern(lit.slice(1, slash), lit.slice(slash + 1), lit);
  if (!t) return null;
  var re = '/' + t.pattern + '/' + t.flags;
  return t.names ? '__ghostRe(' + re + ',' + JSON.stringify(t.names) + ')' : re;
}

// Patterns built at runtime (new RegExp("\\p{L}", "u")) can't be rewritten in the source, so the
// constructor translates them. Native instances, prototype and statics are kept as they are.
(function () {
  var Native = self.RegExp;
  if (Native.__ghost) return;
  var Ghost = function RegExp(pattern, flags) {
    if (typeof pattern === 'string') {
      var t = translatePattern(pattern, flags === undefined ? '' : String(flags));
      if (t) {
        var re = new Native(t.pattern, t.flags);
        return t.names ? self.__ghostRe(re, t.names) : re;
      }
    }
    return arguments.length < 2 ? new Native(pattern) : new Native(pattern, flags);
  };
  Ghost.prototype = Native.prototype;
  Object.getOwnPropertyNames(Native).forEach(function (k) {
    if (k === 'prototype' || k === 'length' || k === 'name') return;
    try { Object.defineProperty(Ghost, k, Object.getOwnPropertyDescriptor(Native, k)); } catch (e) {}
  });
  Ghost.__ghost = true;
  self.RegExp = Ghost;
})();

// ------------------------------------------------------------------ after Sucrase

function lhsStart(x, op) {
  var K = x.K, j = op - 1;
  for (;;) {
    if (j < 0) return -1;
    var k = K[j];
    if (k === RP || k === RB) {
      if (x.M[j] == null) return -1;
      j = x.M[j];
      var b = K[j - 1];
      if (j > 0 && (b === NAME || b === THIS || b === RP || b === RB || b === TEMPLATE_END || b === SUPER)) { j--; continue; }
      return j;
    }
    if (k === NAME || k === THIS || k === SUPER || k === PRIV) {
      if (j > 0 && (K[j - 1] === DOT || K[j - 1] === QDOT)) { j -= 2; continue; }
      return j;
    }
    return -1;
  }
}

function rhsEnd(x, op) {
  var K = x.K, depth = 0, tern = 0;
  for (var m = op + 1; m < K.length; m++) {
    var k = K[m];
    if (k === LP || k === LB || k === LC || k === DOLLAR_BRACE) depth++;
    else if (k === RP || k === RB || k === RC) { if (depth === 0) return x.S[m]; depth--; }
    else if (depth === 0) {
      if (k === SEMI || k === COMMA) return x.S[m];
      if (k === QUESTION) tern++;
      else if (k === COLON) { if (tern === 0) return x.S[m]; tern--; }
    }
  }
  return x.src.length;
}

var uid = 0;

function asyncGenerators(x) {
  var K = x.K, S = x.S, E = x.E, found = [];
  for (var i = 0; i < K.length - 2; i++) {
    if (K[i] !== NAME || x.text(i) !== 'async') continue;
    var fn = K[i + 1] === FUNCTION && K[i + 2] === STAR, method = K[i + 1] === STAR;
    if (!fn && !method) continue;
    var p = i + (fn ? 3 : 2);
    while (p < K.length && K[p] !== LP) {
      if (K[p] === LB && x.M[p] != null) p = x.M[p];
      p++;
    }
    var pClose = x.M[p], body = pClose + 1;
    if (pClose == null || K[body] !== LC || x.M[body] == null) continue;
    var end = x.M[body];
    var head = x.src.slice(E[fn ? i + 2 : i + 1], S[p]).trim(); // function name or method key
    var g = { s: S[i], e: E[end], params: x.src.slice(S[p], E[pClose]), body: x.src.slice(S[body], E[end]),
      key: head, kind: method ? 'method' : 'expr' };
    if (fn && head) {
      var b = i > 0 ? K[i - 1] : SEMI, bt = i > 0 ? x.text(i - 1) : '';
      if (b === SEMI || b === RC || b === LC || bt === 'export') g.kind = 'decl';
    }
    found.push(g);
  }
  return found;
}

/** One rewrite pass over Sucrase output. `babel(snippet)` translates an async generator. */
export function postPass(src, babel) {
  var x = lex(src), K = x.K, S = x.S, E = x.E, edits = [], gens = asyncGenerators(x);
  var inGen = function (pos) { for (var g = 0; g < gens.length; g++) if (pos >= gens[g].s && pos < gens[g].e) return true; return false; };

  for (var a = 0; a < gens.length; a++) {
    var gn = gens[a];
    var expr = '(function(){' + babel('var __ghostG = (async function*' + (gn.kind === 'method' ? '' : ' ' + gn.key) +
        gn.params + gn.body + ');') + '\nreturn __ghostG;})()';
    var r = gn.kind === 'decl'
      ? 'function ' + gn.key + '(){return (' + gn.key + '.__ghostG||(' + gn.key + '.__ghostG=' + expr + ')).apply(this,arguments);}'
      : gn.kind === 'method' ? gn.key + '(){return ' + expr + '.apply(this,arguments);}' : '(' + expr + ')';
    edits.push({ s: gn.s, e: gn.e, r: r });
  }

  for (var i = 0; i < K.length; i++) {
    var k = K[i];
    if (inGen(S[i])) continue;
    if (k === LOGIC_ASSIGN) {
      var st = lhsStart(x, i);
      if (st < 0) continue;
      var lhs = src.slice(S[st], S[i]).trim(), end = rhsEnd(x, i), rhs = src.slice(E[i], end), op = x.text(i);
      var r2 = op === '??=' ? '(' + lhs + ' != null ? ' + lhs + ' : (' + lhs + ' = ' + rhs + '))'
        : '(' + lhs + ' ' + op.slice(0, 2) + ' (' + lhs + ' = ' + rhs + '))';
      edits.push({ s: S[st], e: end, r: r2 });
    } else if (k === REGEX) {
      var rr = rewriteRegex(x.text(i));
      if (rr) edits.push({ s: S[i], e: E[i], r: rr });
    } else if (k === FOR && K[i + 1] === NAME && x.text(i + 1) === 'await' && K[i + 2] === LP) {
      var lp = i + 2, rp = x.M[lp], of = -1;
      if (rp == null) continue;
      for (var q = lp + 1, d = 0; q < rp; q++) {
        if (K[q] === LP || K[q] === LB || K[q] === LC) d++;
        else if (K[q] === RP || K[q] === RB || K[q] === RC) d--;
        else if (d === 0 && K[q] === NAME && x.text(q) === 'of') { of = q; break; }
      }
      if (of < 0) continue;
      var bodyStart, bodyEnd, bodyText, id = uid++;
      if (K[rp + 1] === LC && x.M[rp + 1] != null) {
        bodyEnd = x.M[rp + 1];
        bodyText = src.slice(E[rp + 1], S[bodyEnd]);
      } else {
        // Single-statement body: runs to the first ; at this nesting level.
        for (bodyEnd = rp + 1, d = 0; bodyEnd < K.length; bodyEnd++) {
          if (K[bodyEnd] === LP || K[bodyEnd] === LB || K[bodyEnd] === LC) d++;
          else if (K[bodyEnd] === RP || K[bodyEnd] === RB || K[bodyEnd] === RC) { if (d === 0) { bodyEnd--; break; } d--; }
          else if (d === 0 && K[bodyEnd] === SEMI) break;
        }
        if (bodyEnd >= K.length) continue;
        bodyText = src.slice(E[rp], E[bodyEnd]);
      }
      var headText = src.slice(E[lp], S[of]).trim(), iter = src.slice(E[of], S[rp]);
      var assign = /^(const|let|var)\s/.test(headText) ? headText + ' = __ghostR' + id + '.value;'
        : headText + ' = __ghostR' + id + '.value;';
      edits.push({ s: S[i], e: E[bodyEnd], r: '{ let __ghostIt' + id + ' = __ghostAsyncIter(' + iter + '); for (;;) { let __ghostR' + id +
        ' = await __ghostIt' + id + '.next(); if (__ghostR' + id + '.done) break; ' + assign + ' ' +
        bodyText + ' } }' });
    } else if (k === EXPORT && K[i + 1] === STAR && K[i + 2] === NAME && x.text(i + 2) === 'as'
        && K[i + 3] === NAME && K[i + 4] === NAME && x.text(i + 4) === 'from' && K[i + 5] === STRING) {
      var ns = '__ghostNs' + (uid++);
      edits.push({ s: S[i], e: E[i + 5], r: 'import * as ' + ns + ' from ' + x.text(i + 5) + '; export { ' + ns + ' as ' + x.text(i + 3) + ' }' });
    }
  }
  return edits.length ? applyEdits(src, edits) : src;
}

// Runtime helpers used by the rewritten code.
self.__ghostRe = function (re, names) {
  function groups(m) {
    var g = {};
    for (var k in names) g[k] = m[names[k]];
    return g;
  }
  re.exec = function (s) {
    var m = RegExp.prototype.exec.call(this, s);
    if (m) m.groups = groups(m);
    return m;
  };
  re[Symbol.replace] = function (s, rep) {
    if (typeof rep === 'string') {
      rep = rep.replace(/\$<([^>]+)>/g, function (_, n) { return names[n] ? '$' + names[n] : ''; });
    } else if (typeof rep === 'function') {
      var f = rep;
      rep = function () {
        var args = Array.prototype.slice.call(arguments), g = {};
        for (var k in names) g[k] = args[names[k]];
        args.push(g);
        return f.apply(this, args);
      };
    }
    return RegExp.prototype[Symbol.replace].call(this, s, rep);
  };
  return re;
};

self.__ghostAsyncIter = function (obj) {
  if (obj[Symbol.asyncIterator]) return obj[Symbol.asyncIterator]();
  var it = obj[Symbol.iterator]();
  return {
    next: function () {
      var r = it.next();
      return Promise.resolve(r.value).then(function (v) { return { value: v, done: r.done }; });
    }
  };
};

// ------------------------------------------------------------------ module structure (top-level await)

function strValue(lit) {
  try { return JSON.parse(lit.charAt(0) === "'" ? '"' + lit.slice(1, -1).replace(/\\'/g, "'").replace(/"/g, '\\"') + '"' : lit); }
  catch (e) { return lit.slice(1, -1); }
}

// Is this `{` the body of a function (so `await` inside it is not top-level)?
function fnBrace(x, i) {
  var K = x.K, p = K[i - 1];
  if (p === ARROW) return true;
  if (p !== RP || x.M[i - 1] == null) return false;
  var before = K[x.M[i - 1] - 1];
  return !(before === FOR || (before === KEYWORD && /^(if|while|switch|with|catch)$/.test(x.text(x.M[i - 1] - 1))));
}

function insideConciseArrow(x, i) {
  var K = x.K;
  for (var j = i - 1; j >= 0; j--) {
    var k = K[j];
    if (k === RP || k === RB) { if (x.M[j] == null) return false; j = x.M[j]; continue; }
    if (k === ARROW) return true;
    if (k === SEMI || k === LC || k === RC || k === DOLLAR_BRACE) return false;
  }
  return false;
}

/**
 * Top-level structure of a translated module: static imports and re-exports (with their
 * specifiers), `export { … }` statements, whether it uses top-level await, and whether it has
 * export forms the top-level-await wrapper doesn't handle.
 */
export function moduleInfo(code) {
  var x = lex(code), K = x.K, S = x.S, E = x.E;
  var info = { keep: [], deps: [], local: [], tla: false, unsupported: false };
  var depth = 0, fnStack = [], fnDepth = 0;
  for (var i = 0; i < K.length; i++) {
    var k = K[i];
    if (k === LC || k === DOLLAR_BRACE) { var f = k === LC && fnBrace(x, i); fnStack.push(f); if (f) fnDepth++; depth++; continue; }
    if (k === RC) { if (fnStack.pop()) fnDepth--; depth--; continue; }
    if (k === NAME && fnDepth === 0 && !info.tla) {
      var t = x.text(i);
      if ((t === 'await' && !insideConciseArrow(x, i)) || false) info.tla = true;
    }
    if (k === FOR && fnDepth === 0 && K[i + 1] === NAME && x.text(i + 1) === 'await' && !insideConciseArrow(x, i)) info.tla = true;
    if (depth !== 0) continue;
    if (k === IMPORT && K[i + 1] !== LP && K[i + 1] !== DOT) {
      var j = i + 1;
      while (j < K.length && K[j] !== STRING) j++;
      var end = K[j + 1] === SEMI ? E[j + 1] : E[j];
      info.keep.push({ s: S[i], e: end });
      info.deps.push(strValue(x.text(j)));
      i = j;
    } else if (k === EXPORT) {
      if (K[i + 1] === STAR) {
        var j2 = i + 1;
        while (j2 < K.length && K[j2] !== STRING) j2++;
        info.keep.push({ s: S[i], e: K[j2 + 1] === SEMI ? E[j2 + 1] : E[j2] });
        info.deps.push(strValue(x.text(j2)));
        i = j2;
      } else if (K[i + 1] === LC && x.M[i + 1] != null) {
        var rc = x.M[i + 1];
        if (K[rc + 1] === NAME && x.text(rc + 1) === 'from' && K[rc + 2] === STRING) {
          info.keep.push({ s: S[i], e: K[rc + 3] === SEMI ? E[rc + 3] : E[rc + 2] });
          info.deps.push(strValue(x.text(rc + 2)));
          i = rc + 2;
        } else {
          var pairs = [], cur = [];
          for (var q = i + 2; q < rc; q++) {
            if (K[q] === COMMA) { if (cur.length) pairs.push(cur); cur = []; }
            else cur.push(K[q] === STRING ? x.text(q) : x.text(q));
          }
          if (cur.length) pairs.push(cur);
          info.local.push({ s: S[i], e: K[rc + 1] === SEMI ? E[rc + 1] : E[rc],
            pairs: pairs.map(function (p) { return [p[0], p.length >= 3 ? p[2] : p[0]]; }) });
          i = rc;
        }
      } else {
        info.unsupported = true; // export const / function / class / default: not produced by bundlers' chunks
      }
    }
  }
  return info;
}

// Binding names in a declaration target (`a`, `{a, b: c, ...d}`, `[e, , f = 1]`).
function patternNames(x, a, b) {
  var K = x.K, names = [];
  if (b - a === 1 && K[a] === NAME) return [x.text(a)];
  for (var t = a; t < b; t++) {
    if (K[t] !== NAME) continue;
    var next = t + 1 < b ? K[t + 1] : RC, nextText = t + 1 < b ? x.text(t + 1) : '';
    var prev = t > a ? K[t - 1] : LC;
    if (prev === DOT || prev === QDOT) continue;
    if (next === COMMA || next === RC || next === RB || nextText === '=') names.push(x.text(t));
  }
  return names;
}

/**
 * Emulates top-level await (and waiting for dependencies that use it) on engines without it,
 * the way bundler plugins do: function declarations stay at the module's top level (so they are
 * hoisted, as natively, for circular importers); const/let/var/class names are declared at the
 * top level and assigned inside an async function that runs the module's statements. The
 * original `export { … }` statements keep their live bindings. Exports `__ghostTla`, a promise
 * for completion, which importers of this module wait on.
 */
export function wrapTla(code, info, tlaDeps) {
  if (info.unsupported) return null;
  var x = lex(code), K = x.K, S = x.S, E = x.E, M = x.M;
  var cut = info.keep.concat(info.local);
  var inCut = function (pos) { for (var c = 0; c < cut.length; c++) if (pos >= cut[c].s && pos < cut[c].e) return cut[c]; return null; };
  var edits = cut.map(function (r) { return { s: r.s, e: r.e, r: '' }; });
  var hoisted = [], seenName = {}, funcs = [];
  var hoist = function (n) { if (!seenName[n]) { seenName[n] = 1; hoisted.push(n); } };
  var depth = 0, atStart = true;
  for (var i = 0; i < K.length; i++) {
    var c = inCut(S[i]);
    if (c) { while (i + 1 < K.length && S[i + 1] < c.e) i++; atStart = true; continue; }
    var k = K[i], t = x.text(i);
    if (depth === 0 && atStart) {
      var isFn = k === FUNCTION || (k === NAME && t === 'async' && K[i + 1] === FUNCTION);
      if (isFn) {
        var lp = i; while (lp < K.length && K[lp] !== LP) lp++;
        var body = M[lp] != null ? M[lp] + 1 : -1;
        if (body > 0 && K[body] === LC && M[body] != null) {
          funcs.push(code.slice(S[i], E[M[body]]));
          edits.push({ s: S[i], e: E[M[body]], r: '' });
          i = M[body]; atStart = true; continue;
        }
      } else if (k === KEYWORD && t === 'class' && K[i + 1] === NAME) {
        // Find the class body, stepping over class expressions in the heritage
        // (`class B extends class { … } { … }`).
        var lc = i + 2, d2 = 0, inner = 0;
        for (; lc < K.length; lc++) {
          if (K[lc] === LP || K[lc] === LB) d2++; else if (K[lc] === RP || K[lc] === RB) d2--;
          else if (d2 === 0 && K[lc] === KEYWORD && x.text(lc) === 'class') inner++;
          else if (K[lc] === LC && d2 === 0) {
            if (inner > 0 && M[lc] != null) { inner--; lc = M[lc]; continue; }
            break;
          }
        }
        if (lc < K.length && M[lc] != null) {
          hoist(x.text(i + 1));
          // Replace real tokens (not zero-width inserts) so neighbouring edits can't swallow these.
          edits.push({ s: S[i], e: E[i], r: x.text(i + 1) + ' = class' });
          edits.push({ s: S[M[lc]], e: E[M[lc]], r: '};' });
          i = M[lc]; atStart = true; continue;
        }
      } else if ((k === KEYWORD && (t === 'const' || t === 'var')) || (k === NAME && t === 'let' && (K[i + 1] === NAME || K[i + 1] === LC || K[i + 1] === LB))) {
        // Declarators up to the ; at this level.
        var end = i + 1, d3 = 0, starts = [i + 1];
        for (; end < K.length; end++) {
          var ke = K[end];
          if (ke === LP || ke === LB || ke === LC || ke === DOLLAR_BRACE) d3++;
          else if (ke === RP || ke === RB || ke === RC) { if (d3 === 0) break; d3--; }
          else if (d3 === 0 && ke === COMMA) starts.push(end + 1);
          else if (d3 === 0 && ke === SEMI) break;
        }
        var parts = [];
        for (var q = 0; q < starts.length; q++) {
          var a = starts[q], b = q + 1 < starts.length ? starts[q + 1] - 1 : end, eq = -1;
          for (var r = a, d4 = 0; r < b; r++) {
            if (K[r] === LP || K[r] === LB || K[r] === LC) d4++; else if (K[r] === RP || K[r] === RB || K[r] === RC) d4--;
            else if (d4 === 0 && x.text(r) === '=') { eq = r; break; }
          }
          var tgtEnd = eq < 0 ? b : eq;
          patternNames(x, a, tgtEnd).forEach(hoist);
          if (eq >= 0) {
            var target = code.slice(S[a], E[tgtEnd - 1]), init = code.slice(E[eq], b < K.length ? S[b] : code.length);
            parts.push(K[a] === LC ? '(' + target + ' = ' + init + ')' : target + ' = ' + init);
          }
        }
        var stmtEnd = end < K.length && K[end] === SEMI ? E[end] : (end < K.length ? S[end] : code.length);
        edits.push({ s: S[i], e: stmtEnd, r: parts.length ? parts.join(', ') + ';' : ';' });
        i = end < K.length && K[end] === SEMI ? end : end - 1;
        atStart = true; continue;
      }
    }
    if (k === LC || k === LP || k === LB || k === DOLLAR_BRACE) depth++;
    else if (k === RC || k === RP || k === RB) depth--;
    atStart = depth === 0 && (k === SEMI || k === RC);
  }
  var body2 = applyEdits(code, edits);
  var head = '';
  info.keep.forEach(function (r) { head += code.slice(r.s, r.e) + '\n'; });
  var waits = [];
  for (var dd = 0; dd < tlaDeps.length; dd++) {
    head += 'import { __ghostTla as __ghostD' + dd + ' } from ' + JSON.stringify(tlaDeps[dd]) + ';\n';
    waits.push('__ghostD' + dd);
  }
  if (hoisted.length) head += 'let ' + hoisted.join(', ') + ';\n';
  head += funcs.join('\n') + '\n';
  info.local.forEach(function (r) { head += code.slice(r.s, r.e) + '\n'; });
  return head + 'export const __ghostTla = (async () => {' + (waits.length ? 'await Promise.all([' + waits.join(',') + ']);' : '') +
      '\n' + body2 + '\n})();\n';
}

/** import(x) resolves before a wrapped module has finished: wait for its __ghostTla too. */
export function wrapDynamicImports(code) {
  if (!/\bimport\s*\(/.test(code)) return code;
  var x = lex(code), K = x.K, edits = [];
  for (var i = 0; i < K.length; i++) {
    if (K[i] === IMPORT && K[i + 1] === LP && x.M[i + 1] != null && (i === 0 || K[i - 1] !== DOT)) {
      edits.push({ s: x.S[i], e: x.S[i], r: '__ghostImport(' });
      edits.push({ s: x.E[x.M[i + 1]], e: x.E[x.M[i + 1]], r: ')' });
    }
  }
  return edits.length ? applyEdits(code, edits) : code;
}

self.__ghostImport = function (p) {
  return p.then(function (m) { return m && m.__ghostTla ? m.__ghostTla.then(function () { return m; }) : m; });
};
