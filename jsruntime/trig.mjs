import { transform } from 'sucrase';
import * as acorn from 'acorn';
import fs from 'node:fs';
const conv = fs.readFileSync('src/convert.js', 'utf8');
const NEEDS = eval(conv.match(/var NEEDS_BABEL = (\/.*\/);/)[1]);
const UA = 'Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 Chrome/61.0 Safari/537.36';
const seen = new Map(); const q = ['/assets/index-CHsYE0nT.js'];
while (q.length) {
  const p = q.shift(); if (seen.has(p)) continue;
  let s; try { s = await (await fetch('https://solvely.ai' + p, { headers: { 'User-Agent': UA } })).text(); } catch { continue; }
  seen.set(p, s);
  for (const m of s.matchAll(/(?:from\s*|import\s*\(\s*|import\s*)["'](\.{0,2}\/[^"']+\.js)["']/g)) { const n = new URL(m[1], 'https://solvely.ai' + p).pathname; if (!seen.has(n)) q.push(n); }
}
const why = {}; let real = 0, realKB = 0;
for (const [p, s] of seen) {
  if (!NEEDS.test(s)) continue;
  const trig = s.match(NEEDS)[0].slice(0, 12);
  (why[trig] ||= 0); why[trig]++;
  // Does Sucrase's output still contain something Chrome 61 can't parse?
  const out = transform(s, { transforms: [], production: true }).code;
  let need = false;
  try {
    (function walk(n) {
      if (!n || typeof n !== 'object' || need) return; const t = n.type;
      if (t === 'PrivateIdentifier' || t === 'StaticBlock' || (t === 'ForOfStatement' && n.await) ||
          ((t === 'FunctionExpression' || t === 'FunctionDeclaration') && n.async && n.generator) ||
          (t === 'AssignmentExpression' && /^(\?\?|\|\||&&)=$/.test(n.operator)) || (t === 'ExportAllDeclaration' && n.exported) ||
          (t === 'Literal' && n.regex && (/\(\?<[a-zA-Z$=!]/.test(n.regex.pattern) || n.regex.flags.includes('s') || /\\[pP]\{/.test(n.regex.pattern)))) need = true;
      for (const k in n) walk(n[k]);
    })(acorn.parse(out, { ecmaVersion: 2022, sourceType: 'module' }));
  } catch (e) { need = true; }
  if (need) { real++; realKB += s.length / 1024; }
}
console.log('first match per Babel-routed module:', why);
console.log(`modules that truly need Babel after Sucrase: ${real} (${realKB.toFixed(0)} KB)`);
